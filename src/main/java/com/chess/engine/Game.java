package com.chess.engine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

public class Game {
    // The Server-Side Memory Bank
    private List<Map<String, Object>> moveHistory = new ArrayList<>();
    private List<Map<String, Object>> chatHistory = new ArrayList<>();
    private Board board;
    private Color currentTurn;

    // SECRET TOKEN TRACKERS & REVOKED TOKENS REGISTRY
    private String whiteToken = null;
    private String blackToken = null;
    private final Set<String> revokedTokens = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> activeSpectators = Collections.synchronizedSet(new LinkedHashSet<>());

    // Lock for synchronizing critical state transitions
    private final Object stateLock = new Object();

    // Match generation token — increments on each new match start.
    // Used to ensure stale scheduled tasks cannot affect a newer match.
    private volatile long matchGeneration = 0;

    // Current authoritative match status and terminal flag
    private volatile String currentStatus = "White's Turn";
    private volatile boolean matchEnded = false;

    public enum AbortPhase {
        NONE,
        WHITE_GRACE,
        WHITE_WARNING,
        BLACK_GRACE,
        BLACK_WARNING
    }

    private volatile AbortPhase abortPhase = AbortPhase.NONE;
    private volatile boolean whiteFirstMoveDone = false;
    private volatile boolean blackFirstMoveDone = false;
    private volatile long turnGeneration = 0;
    private volatile long phaseGeneration = 0;

    // Authoritative abort deadline and target color
    private volatile long abortDeadline = 0;
    private volatile Color abortColor = null;
    public static final long ABORT_TIMEOUT_SECONDS = 15;
    public static final long ABORT_TIMEOUT_MS = ABORT_TIMEOUT_SECONDS * 1000L;
    public static final long PHASE_DURATION_SECONDS = 15;
    public static final long PHASE_DURATION_MS = PHASE_DURATION_SECONDS * 1000L;

    private volatile SimpMessagingTemplate messagingTemplate = null;

    // Abort timer for "no move made" scenario (first-move-only 2-stage state machine)
    // Use a thread pool so that a failure in one task doesn't kill the executor.
    private final ScheduledExecutorService abortScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "abort-timer-pool");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> abortTimer = null;

    public String assignPlayer(String returningToken) {
        synchronized (stateLock) {
            String cleanToken = (returningToken != null) ? returningToken.trim() : null;

            // 1. Welcome back returning active players only if valid and not revoked
            if (cleanToken != null && !cleanToken.isEmpty() && !revokedTokens.contains(cleanToken)) {
                if (cleanToken.equals(whiteToken)) return whiteToken;
                if (cleanToken.equals(blackToken)) return blackToken;
            }

            // 2. An old revoked player token cannot reclaim a seat or be promoted
            if (cleanToken != null && revokedTokens.contains(cleanToken)) {
                return "SPECTATOR";
            }

            // 3. If an active match is in progress, do not allow spectator promotion or new player seat assignment
            if (matchStarted && !matchEnded) {
                if (cleanToken != null && !cleanToken.isEmpty()) {
                    activeSpectators.add(cleanToken);
                }
                return "SPECTATOR";
            }

            // 4. Check available seats:
            // Seats are available if whiteToken == null or blackToken == null.
            boolean whiteAvailable = (whiteToken == null);
            boolean blackAvailable = (blackToken == null);

            if (whiteAvailable || blackAvailable) {
                // Determine seat assignment priority: WHITE first, then BLACK
                String assignedToken;
                if (whiteAvailable) {
                    assignedToken = "WHITE-" + java.util.UUID.randomUUID().toString();
                    whiteToken = assignedToken;
                    whiteReady = false;
                } else {
                    assignedToken = "BLACK-" + java.util.UUID.randomUUID().toString();
                    blackToken = assignedToken;
                    blackReady = false;
                }

                // If this was a registered spectator being promoted, remove from activeSpectators
                if (cleanToken != null) {
                    activeSpectators.remove(cleanToken);
                }
                return assignedToken;
            }

            // 5. Both seats are occupied -> Client becomes or remains a SPECTATOR
            if (cleanToken != null && !cleanToken.isEmpty()) {
                activeSpectators.add(cleanToken);
            }
            return "SPECTATOR";
        }
    }

    public String getPlayerColor(String token) {
        synchronized (stateLock) {
            if (token == null || token.isEmpty() || revokedTokens.contains(token)) {
                return "SPECTATOR";
            }
            if (token.equals(whiteToken)) return "WHITE";
            if (token.equals(blackToken)) return "BLACK";
            return "SPECTATOR";
        }
    }

    // Allows the Controller to ask if the match is running
    public boolean isMatchStarted() {
        synchronized (stateLock) {
            return matchStarted;
        }
    }

    public boolean hasMatchEnded() {
        synchronized (stateLock) {
            return matchEnded;
        }
    }

    public String getCurrentStatus() {
        synchronized (stateLock) {
            return currentStatus;
        }
    }

    public void setCurrentStatus(String status) {
        synchronized (stateLock) {
            this.currentStatus = status;
        }
    }

    // Per-player readiness/rematch state accessors
    public boolean isWhiteReady() {
        synchronized (stateLock) {
            return whiteReady;
        }
    }
    public boolean isBlackReady() {
        synchronized (stateLock) {
            return blackReady;
        }
    }
    public boolean getWhiteWantsRematch() {
        synchronized (stateLock) {
            return "WHITE".equals(rematchRequester) && rematchStatus == RematchStatus.PENDING;
        }
    }
    public boolean getBlackWantsRematch() {
        synchronized (stateLock) {
            return "BLACK".equals(rematchRequester) && rematchStatus == RematchStatus.PENDING;
        }
    }

    // LOBBY TRACKERS
    private boolean whiteReady = false;
    private boolean blackReady = false;
    private boolean matchStarted = false;

    // PER-PLAYER REMATCH STATE MACHINE
    public enum RematchStatus {
        NONE,
        PENDING,
        ACCEPTED,
        DECLINED,
        CANCELLED,
        EXPIRED
    }

    public static final long REMATCH_DURATION_SECONDS = 15;
    public static final long REMATCH_DURATION_MS = REMATCH_DURATION_SECONDS * 1000L;

    private volatile RematchStatus rematchStatus = RematchStatus.NONE;
    private volatile String rematchRequester = null;
    private volatile String rematchTarget = null;
    private volatile long rematchDeadline = 0L;
    private volatile long rematchRequestId = 0L;
    private volatile ScheduledFuture<?> rematchTimer = null;

    public RematchStatus getRematchStatus() {
        synchronized (stateLock) {
            return rematchStatus;
        }
    }

    public String getRematchRequester() {
        synchronized (stateLock) {
            return rematchRequester;
        }
    }

    public String getRematchTarget() {
        synchronized (stateLock) {
            return rematchTarget;
        }
    }

    public long getRematchDeadline() {
        synchronized (stateLock) {
            return rematchDeadline;
        }
    }

    // Mutual Rematch Request: initiates or confirms a rematch
    public Map<String, Object> requestRematch(String color) {
        synchronized (stateLock) {
            Map<String, Object> result = new HashMap<>();

            // Spectators cannot request or participate in rematches
            if (color == null || (!"WHITE".equals(color) && !"BLACK".equals(color))) {
                result.put("success", false);
                result.put("error", "Spectators cannot request or participate in rematches.");
                result.put("matchStarted", false);
                result.put("rematchState", getRematchStateInternal());
                return result;
            }

            // Rematch only allowed when match has ended
            if (!matchEnded && matchStarted) {
                result.put("success", false);
                result.put("error", "Cannot request rematch while match is in progress.");
                result.put("matchStarted", false);
                result.put("rematchState", getRematchStateInternal());
                return result;
            }

            // If a rematch request is already active
            if (rematchStatus == RematchStatus.PENDING) {
                // If the same player requests again, treat as idempotent (do not create new timer/overwrite)
                if (color.equals(rematchRequester)) {
                    result.put("success", true);
                    result.put("alreadyPending", true);
                    result.put("status", "PENDING");
                    result.put("matchStarted", false);
                    result.put("rematchState", getRematchStateInternal());
                    return result;
                }

                // If the opponent (target) calls requestRematch, treat as acceptance!
                if (color.equals(rematchTarget)) {
                    boolean accepted = acceptRematchInternal(color);
                    result.put("success", accepted);
                    result.put("status", accepted ? "ACCEPTED" : "EXPIRED");
                    result.put("matchStarted", accepted);
                    result.put("rematchState", getRematchStateInternal());
                    return result;
                }
            }

            // Otherwise, start a new pending rematch request
            cancelRematchTimerInternal();

            rematchRequestId++;
            rematchStatus = RematchStatus.PENDING;
            rematchRequester = color;
            rematchTarget = "WHITE".equals(color) ? "BLACK" : "WHITE";
            rematchDeadline = System.currentTimeMillis() + REMATCH_DURATION_MS;

            long reqId = rematchRequestId;
            long mGen = matchGeneration;
            rematchTimer = abortScheduler.schedule(() -> {
                handleRematchExpiredInternal(reqId, mGen);
            }, REMATCH_DURATION_MS, TimeUnit.MILLISECONDS);

            result.put("success", true);
            result.put("status", "PENDING");
            result.put("matchStarted", false);
            result.put("rematchState", getRematchStateInternal());
            return result;
        }
    }

    public boolean acceptRematch(String color) {
        synchronized (stateLock) {
            return acceptRematchInternal(color);
        }
    }

    private boolean acceptRematchInternal(String color) {
        if (color == null || (!"WHITE".equals(color) && !"BLACK".equals(color))) {
            return false;
        }
        if (rematchStatus != RematchStatus.PENDING) {
            return false;
        }
        if (!color.equals(rematchTarget)) {
            return false;
        }
        if (System.currentTimeMillis() > rematchDeadline) {
            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.EXPIRED;
            return false;
        }

        cancelRematchTimerInternal();
        rematchStatus = RematchStatus.ACCEPTED;

        // Cancel any pending abort timer from previous match
        cancelAbortTimerInternal(matchGeneration);
        // Reset the shared game state for a fresh match (preserves seats and tokens)
        resetBoardForNewMatchInternal();
        matchStarted = true;
        matchEnded = false;
        matchGeneration++;
        turnGeneration = 0;
        lastMoveTimestamp = System.currentTimeMillis();

        // Clear rematch flags for the new match
        rematchStatus = RematchStatus.NONE;
        rematchRequester = null;
        rematchTarget = null;
        rematchDeadline = 0L;
        whiteReady = false;
        blackReady = false;

        return true;
    }

    public boolean declineRematch(String color) {
        synchronized (stateLock) {
            if (color == null || (!"WHITE".equals(color) && !"BLACK".equals(color))) {
                return false;
            }
            if (rematchStatus != RematchStatus.PENDING) {
                return false;
            }
            if (!color.equals(rematchTarget)) {
                return false;
            }

            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.DECLINED;
            return true;
        }
    }

    public boolean cancelRematch(String color) {
        synchronized (stateLock) {
            if (color == null || (!"WHITE".equals(color) && !"BLACK".equals(color))) {
                return false;
            }
            if (rematchStatus != RematchStatus.PENDING) {
                return false;
            }
            if (!color.equals(rematchRequester)) {
                return false;
            }

            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.CANCELLED;
            return true;
        }
    }

    public void handleRematchExpiredInternal(long reqId, long mGen) {
        boolean shouldBroadcast = false;
        synchronized (stateLock) {
            if (rematchRequestId != reqId || matchGeneration != mGen || rematchStatus != RematchStatus.PENDING) {
                return;
            }
            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.EXPIRED;
            shouldBroadcast = true;
        }

        if (shouldBroadcast && messagingTemplate != null) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "REMATCH_EXPIRED");
                payload.put("rematchState", getRematchState());
                messagingTemplate.convertAndSend("/topic/game", (Object) payload);
            } catch (Exception e) {
                System.err.println("❌ REMATCH_EXPIRED broadcast failed: " + e.getMessage());
            }
        }
    }

    private void cancelRematchTimerInternal() {
        if (rematchTimer != null) {
            rematchTimer.cancel(false);
            rematchTimer = null;
        }
    }

    public Map<String, Object> getRematchState() {
        synchronized (stateLock) {
            return getRematchStateInternal();
        }
    }

    private Map<String, Object> getRematchStateInternal() {
        Map<String, Object> state = new HashMap<>();
        state.put("status", rematchStatus.name());
        state.put("requester", rematchRequester);
        state.put("target", rematchTarget);
        state.put("deadline", rematchDeadline);
        long remainingMs = Math.max(0, rematchDeadline - System.currentTimeMillis());
        int remainingSeconds = (int) Math.ceil(remainingMs / 1000.0);
        state.put("remainingSeconds", rematchStatus == RematchStatus.PENDING ? remainingSeconds : 0);
        state.put("requestId", rematchRequestId);
        return state;
    }

    public void expireRematchForTesting() {
        long reqId;
        long mGen;
        synchronized (stateLock) {
            reqId = rematchRequestId;
            mGen = matchGeneration;
        }
        handleRematchExpiredInternal(reqId, mGen);
    }

    // Call this when a player clicks "Ready"
    public boolean setPlayerReady(String color) {
        synchronized (stateLock) {
            if (matchStarted) return false;
            if ("WHITE".equals(color)) whiteReady = true;
            if ("BLACK".equals(color)) blackReady = true;

            if (whiteReady && blackReady) {
                // Cancel any pending abort timer from previous match
                cancelAbortTimerInternal(matchGeneration);
                matchStarted = true;
                matchEnded = false;
                matchGeneration++;
                turnGeneration = 0;
                lastMoveTimestamp = System.currentTimeMillis();
                // Reset board for fresh game (preserves readiness/seats)
                resetBoardForNewMatchInternal();
                return true;
            }
            return false;
        }
    }

    // Marks the game as officially over and freezes the clocks
    private void endMatch() {
        synchronized (stateLock) {
            // Cancel any pending abort timer
            cancelAbortTimerInternal(matchGeneration);
            // Cancel any pending rematch timer and clean rematch state
            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.NONE;
            rematchRequester = null;
            rematchTarget = null;
            rematchDeadline = 0L;

            // Freeze the remaining time before marking match as not started
            long frozenWhite = getWhiteTimeRemaining();
            long frozenBlack = getBlackTimeRemaining();
            matchStarted = false;
            matchEnded = true;
            // Reset readiness so a new match requires both players to explicitly agree
            whiteReady = false;
            blackReady = false;
            // Freeze the remaining time so subsequent /sync calls return stable values
            whiteTimeRemaining = frozenWhite;
            blackTimeRemaining = frozenBlack;
            lastMoveTimestamp = System.currentTimeMillis();
        }
    }

    // Internal endMatch used by abort timer (doesn't broadcast, just updates state)
    private void endMatchInternal() {
        synchronized (stateLock) {
            // Cancel any pending abort timer
            cancelAbortTimerInternal(matchGeneration);
            // Cancel any pending rematch timer and clean rematch state
            cancelRematchTimerInternal();
            rematchStatus = RematchStatus.NONE;
            rematchRequester = null;
            rematchTarget = null;
            rematchDeadline = 0L;

            long frozenWhite = getWhiteTimeRemaining();
            long frozenBlack = getBlackTimeRemaining();
            matchStarted = false;
            matchEnded = true;
            whiteReady = false;
            blackReady = false;
            whiteTimeRemaining = frozenWhite;
            blackTimeRemaining = frozenBlack;
            lastMoveTimestamp = System.currentTimeMillis();
        }
    }

    // ==========================================
    // ABORT TIMER LOGIC (authoritative, backend-driven)
    // ==========================================

    // Starts the 2-stage first-move auto-abort process for White.
    // Stage A: 15-second silent grace period (no warning displayed).
    private void startWhiteGracePeriodInternal(long matchGen, SimpMessagingTemplate messagingTemplate) {
        if (abortTimer != null) {
            abortTimer.cancel(false);
            abortTimer = null;
        }
        if (!matchStarted || matchEnded) return;

        abortPhase = AbortPhase.WHITE_GRACE;
        abortColor = Color.WHITE;
        abortDeadline = System.currentTimeMillis() + PHASE_DURATION_MS;
        turnGeneration++;
        phaseGeneration++;
        final long currentPhaseGen = phaseGeneration;

        abortTimer = abortScheduler.schedule(() -> {
            try {
                handleWhiteGraceExpired(matchGen, currentPhaseGen, messagingTemplate);
            } catch (Exception e) {
                System.err.println("❌ WHITE GRACE TIMER FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }, PHASE_DURATION_SECONDS, TimeUnit.SECONDS);

        System.out.println("⏱️ WHITE FIRST MOVE: 15s silent grace period started (matchGen " + matchGen + ", phaseGen " + currentPhaseGen + ")");
    }

    // Called when White's 15s silent grace period expires without a move.
    // Transitions to WHITE_WARNING: displays 15s red warning countdown in White's clock area.
    private void handleWhiteGraceExpired(long matchGen, long expectedPhaseGen, SimpMessagingTemplate messagingTemplate) {
        synchronized (stateLock) {
            if (matchGeneration != matchGen || phaseGeneration != expectedPhaseGen || !matchStarted || matchEnded || abortPhase != AbortPhase.WHITE_GRACE) {
                System.out.println("⏭️ WHITE GRACE EXPIRED SKIPPED: Stale state");
                return;
            }

            abortPhase = AbortPhase.WHITE_WARNING;
            abortColor = Color.WHITE;
            abortDeadline = System.currentTimeMillis() + PHASE_DURATION_MS;
            phaseGeneration++;
            final long warnPhaseGen = phaseGeneration;

            abortTimer = abortScheduler.schedule(() -> {
                try {
                    handleWarningExpired(matchGen, warnPhaseGen, Color.WHITE, messagingTemplate);
                } catch (Exception e) {
                    System.err.println("❌ WHITE WARNING TIMER FAILED: " + e.getMessage());
                    e.printStackTrace();
                }
            }, PHASE_DURATION_SECONDS, TimeUnit.SECONDS);

            System.out.println("⚠️ WHITE FIRST MOVE: 15s warning countdown started (matchGen " + matchGen + ", phaseGen " + warnPhaseGen + ")");
        }

        broadcastAbortWarning(messagingTemplate);
    }

    // Starts the 2-stage first-move auto-abort process for Black (after White moves).
    // Stage B: 15-second silent grace period (no warning displayed).
    private void startBlackGracePeriodInternal(long matchGen, SimpMessagingTemplate messagingTemplate) {
        if (abortTimer != null) {
            abortTimer.cancel(false);
            abortTimer = null;
        }
        if (!matchStarted || matchEnded) return;

        abortPhase = AbortPhase.BLACK_GRACE;
        abortColor = Color.BLACK;
        abortDeadline = System.currentTimeMillis() + PHASE_DURATION_MS;
        turnGeneration++;
        phaseGeneration++;
        final long currentPhaseGen = phaseGeneration;

        abortTimer = abortScheduler.schedule(() -> {
            try {
                handleBlackGraceExpired(matchGen, currentPhaseGen, messagingTemplate);
            } catch (Exception e) {
                System.err.println("❌ BLACK GRACE TIMER FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }, PHASE_DURATION_SECONDS, TimeUnit.SECONDS);

        System.out.println("⏱️ BLACK FIRST MOVE: 15s silent grace period started (matchGen " + matchGen + ", phaseGen " + currentPhaseGen + ")");
    }

    // Called when Black's 15s silent grace period expires without a move.
    // Transitions to BLACK_WARNING: displays 15s red warning countdown in Black's clock area.
    private void handleBlackGraceExpired(long matchGen, long expectedPhaseGen, SimpMessagingTemplate messagingTemplate) {
        synchronized (stateLock) {
            if (matchGeneration != matchGen || phaseGeneration != expectedPhaseGen || !matchStarted || matchEnded || abortPhase != AbortPhase.BLACK_GRACE) {
                System.out.println("⏭️ BLACK GRACE EXPIRED SKIPPED: Stale state");
                return;
            }

            abortPhase = AbortPhase.BLACK_WARNING;
            abortColor = Color.BLACK;
            abortDeadline = System.currentTimeMillis() + PHASE_DURATION_MS;
            phaseGeneration++;
            final long warnPhaseGen = phaseGeneration;

            abortTimer = abortScheduler.schedule(() -> {
                try {
                    handleWarningExpired(matchGen, warnPhaseGen, Color.BLACK, messagingTemplate);
                } catch (Exception e) {
                    System.err.println("❌ BLACK WARNING TIMER FAILED: " + e.getMessage());
                    e.printStackTrace();
                }
            }, PHASE_DURATION_SECONDS, TimeUnit.SECONDS);

            System.out.println("⚠️ BLACK FIRST MOVE: 15s warning countdown started (matchGen " + matchGen + ", phaseGen " + warnPhaseGen + ")");
        }

        broadcastAbortWarning(messagingTemplate);
    }

    // Handles expiration of the 15s warning period (30s total inactivity from turn start).
    private void handleWarningExpired(long matchGen, long expectedPhaseGen, Color targetColor, SimpMessagingTemplate messagingTemplate) {
        synchronized (stateLock) {
            if (matchGeneration != matchGen || phaseGeneration != expectedPhaseGen || !matchStarted || matchEnded) {
                System.out.println("⏭️ WARNING EXPIRED SKIPPED: Stale state");
                return;
            }
            if ((targetColor == Color.WHITE && abortPhase != AbortPhase.WHITE_WARNING) ||
                (targetColor == Color.BLACK && abortPhase != AbortPhase.BLACK_WARNING)) {
                System.out.println("⏭️ WARNING EXPIRED SKIPPED: Wrong phase " + abortPhase);
                return;
            }
        }
        abortDueToInactivity(matchGen, expectedPhaseGen, targetColor, messagingTemplate);
    }

    // Broadcasts an ABORT_WARNING message with current abort timer state to /topic/game
    private void broadcastAbortWarning(SimpMessagingTemplate messagingTemplate) {
        try {
            SimpMessagingTemplate template = messagingTemplate != null ? messagingTemplate : this.messagingTemplate;
            if (template != null) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "ABORT_WARNING");
                payload.put("abortTimer", getAbortTimerState());
                template.convertAndSend("/topic/game", (Object) payload);
            }
        } catch (Exception e) {
            System.err.println("❌ ABORT_WARNING BROADCAST FAILED: " + e.getMessage());
        }
    }

    // Public entry point to start the first-move auto-abort cycle on match start
    public void startAbortTimer(SimpMessagingTemplate messagingTemplate) {
        synchronized (stateLock) {
            this.messagingTemplate = messagingTemplate;
            if (!matchStarted || matchEnded) return;
            whiteFirstMoveDone = false;
            blackFirstMoveDone = false;
            startWhiteGracePeriodInternal(matchGeneration, messagingTemplate);
        }
    }

    // Cancels active abort timer and resets abort state
    private void cancelAbortTimerInternal(long matchGen) {
        if (abortTimer != null) {
            abortTimer.cancel(false);
            abortTimer = null;
        }
        abortDeadline = 0;
        abortColor = null;
        abortPhase = AbortPhase.NONE;
        System.out.println("🛑 ABORT TIMER CANCELLED for match generation " + matchGen);
    }

    // Public entry point to cancel the abort timer on match end or reset
    public void cancelAbortTimer() {
        synchronized (stateLock) {
            cancelAbortTimerInternal(matchGeneration);
        }
    }

    // Aborts the match due to inactivity (no move made in 30 seconds total: 15s grace + 15s warning).
    private void abortDueToInactivity(long matchGen, long expectedPhaseGen, Color targetColor, SimpMessagingTemplate messagingTemplate) {
        String finalStatus = "MATCH ABORTED! " + targetColor + " did not make a move in 30 seconds.";
        List<List<String>> boardGrid;
        synchronized (stateLock) {
            if (matchGeneration != matchGen || phaseGeneration != expectedPhaseGen || !matchStarted || matchEnded) {
                System.out.println("⏭️ ABORT SKIPPED: Stale generation or match not active");
                return;
            }
            // End the match
            endMatchInternal();
            currentStatus = finalStatus;
            matchEnded = true;
            abortPhase = AbortPhase.NONE;
            abortDeadline = 0;
            abortColor = null;
            boardGrid = getBoardStateForBroadcast();
            System.out.println("💥 MATCH ABORTED: " + finalStatus);
        }
        // Broadcast abort notification
        try {
            SimpMessagingTemplate template = messagingTemplate != null ? messagingTemplate : this.messagingTemplate;
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "ABORT_TIMEOUT");
            payload.put("status", finalStatus);
            payload.put("color", targetColor.toString());
            payload.put("grid", boardGrid);
            if (template != null) {
                template.convertAndSend("/topic/game", (Object) payload);
            }
        } catch (Exception e) {
            System.err.println("❌ ABORT_TIMEOUT BROADCAST FAILED for matchGen " + matchGen + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper to get board state for broadcast (used in abort timeout)
    private List<List<String>> getBoardStateForBroadcast() {
        List<List<String>> grid = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<String> row = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p == null) {
                    row.add("");
                } else {
                    String color = p.getColor() == Color.WHITE ? "w" : "b";
                    String type;
                    if (p instanceof King) type = "K";
                    else if (p instanceof Queen) type = "Q";
                    else if (p instanceof Rook) type = "R";
                    else if (p instanceof Bishop) type = "B";
                    else if (p instanceof Knight) type = "N";
                    else if (p instanceof Pawn) type = "P";
                    else type = "";
                    row.add(color + type);
                }
            }
            grid.add(row);
        }
        return grid;
    }

    public List<Map<String, Object>> getMoveHistory() {
        synchronized (stateLock) {
            return new ArrayList<>(moveHistory);
        }
    }

    // Returns the state of the abort timer for synchronization and reconnection recovery.
    // warningActive is true ONLY during the 15-second warning phase (WHITE_WARNING / BLACK_WARNING).
    public Map<String, Object> getAbortTimerState() {
        synchronized (stateLock) {
            Map<String, Object> state = new HashMap<>();
            if (!matchStarted || matchEnded || abortPhase == AbortPhase.NONE || abortDeadline <= 0) {
                state.put("active", false);
                state.put("warningActive", false);
                state.put("phase", abortPhase.name());
                return state;
            }
            long now = System.currentTimeMillis();
            long remainingMs = Math.max(0, abortDeadline - now);
            int remainingSeconds = (int) Math.ceil(remainingMs / 1000.0);
            boolean isWarning = (abortPhase == AbortPhase.WHITE_WARNING || abortPhase == AbortPhase.BLACK_WARNING);

            state.put("active", true);
            state.put("phase", abortPhase.name());
            state.put("warningActive", isWarning);
            state.put("color", abortColor != null ? abortColor.toString() : currentTurn.toString());
            state.put("deadline", abortDeadline);
            state.put("remainingSeconds", remainingSeconds);
            state.put("matchGeneration", matchGeneration);
            state.put("turnGeneration", turnGeneration);
            state.put("phaseGeneration", phaseGeneration);
            state.put("whiteFirstMoveDone", whiteFirstMoveDone);
            state.put("blackFirstMoveDone", blackFirstMoveDone);
            return state;
        }
    }

    // Testing accessors for controlled unit testing without weakening production timeouts
    public long getTurnGeneration() {
        synchronized (stateLock) {
            return turnGeneration;
        }
    }

    public long getPhaseGeneration() {
        synchronized (stateLock) {
            return phaseGeneration;
        }
    }

    public long getAbortDeadline() {
        synchronized (stateLock) {
            return abortDeadline;
        }
    }

    public Color getAbortColor() {
        synchronized (stateLock) {
            return abortColor;
        }
    }

    public AbortPhase getAbortPhase() {
        synchronized (stateLock) {
            return abortPhase;
        }
    }

    public boolean isWhiteFirstMoveDone() {
        synchronized (stateLock) {
            return whiteFirstMoveDone;
        }
    }

    public boolean isBlackFirstMoveDone() {
        synchronized (stateLock) {
            return blackFirstMoveDone;
        }
    }

    public void setMessagingTemplate(SimpMessagingTemplate template) {
        this.messagingTemplate = template;
    }

    // Testing helper: triggers expiration of the current silent grace period
    public void triggerGracePeriodExpiryForTesting() {
        long mGen;
        long pGen;
        AbortPhase phase;
        SimpMessagingTemplate template;
        synchronized (stateLock) {
            mGen = matchGeneration;
            pGen = phaseGeneration;
            phase = abortPhase;
            template = this.messagingTemplate;
        }
        if (phase == AbortPhase.WHITE_GRACE) {
            handleWhiteGraceExpired(mGen, pGen, template);
        } else if (phase == AbortPhase.BLACK_GRACE) {
            handleBlackGraceExpired(mGen, pGen, template);
        }
    }

    // Testing helper: triggers timeout abort during warning phase
    public void triggerAbortTimeoutForTesting(SimpMessagingTemplate messagingTemplate) {
        long mGen;
        long pGen;
        Color col;
        SimpMessagingTemplate template;
        synchronized (stateLock) {
            mGen = matchGeneration;
            pGen = phaseGeneration;
            col = abortColor;
            template = messagingTemplate != null ? messagingTemplate : this.messagingTemplate;
        }
        handleWarningExpired(mGen, pGen, col, template);
    }

    public void addMoveToHistory(Map<String, Object> movePayload) {
        synchronized (stateLock) {
            moveHistory.add(movePayload);
        }
    }

    public List<Map<String, Object>> getChatHistory() {
        synchronized (stateLock) {
            return new ArrayList<>(chatHistory);
        }
    }

    public void addChatToHistory(Map<String, Object> chatPayload) {
        synchronized (stateLock) {
            chatHistory.add(chatPayload);
        }
    }

    // Action Handlers for Resign and Abort
    public String resign(String color) {
        synchronized (stateLock) {
            String status = "RESIGNATION! " + ("WHITE".equals(color) ? "BLACK wins!" : "WHITE wins!");
            endMatch();
            currentStatus = status;
            return status;
        }
    }

    public String abort() {
        synchronized (stateLock) {
            String status = "MATCH ABORTED! Game cancelled.";
            endMatch();
            currentStatus = status;
            return status;
        }
    }

    // DRAW TRACKERS
    // Counts half-moves. Resets to 0 on pawn move or capture. At 100 = 50-Move Draw.
    private int halfMoveClock = 0;

    // Remembers board positions to check for Threefold Repetition
    private java.util.Map<String, Integer> positionHistory = new java.util.HashMap<>();

    // CLOCK TRACKERS
    private long whiteTimeRemaining = 10 * 60 * 1000; // 10 minutes in ms
    private long blackTimeRemaining = 10 * 60 * 1000;
    private long lastMoveTimestamp = System.currentTimeMillis();

    // LIVE TIME CALCULATION — returns frozen time when match is not active
    public long getWhiteTimeRemaining() {
        synchronized (stateLock) {
            if (matchStarted && currentTurn == Color.WHITE) {
                long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
                return Math.max(0, whiteTimeRemaining - elapsed);
            }
            return Math.max(0, whiteTimeRemaining);
        }
    }

    public long getBlackTimeRemaining() {
        synchronized (stateLock) {
            if (matchStarted && currentTurn == Color.BLACK) {
                long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
                return Math.max(0, blackTimeRemaining - elapsed);
            }
            return Math.max(0, blackTimeRemaining);
        }
    }

    public Game() {
        board = new Board();
        currentTurn = Color.WHITE;
    }

    // Returns whose turn it currently is (used by controller for clock sync)
    public Color getCurrentTurn() {
        synchronized (stateLock) {
            return currentTurn;
        }
    }

    // ==========================================
    // MASTER RESET METHOD
    // ==========================================
    public void resetGame() {
        synchronized (stateLock) {
            cancelAbortTimerInternal(matchGeneration);
            cancelRematchTimerInternal();
            matchGeneration++;

            this.board = new Board();
            this.currentTurn = Color.WHITE;
            this.moveHistory.clear(); // Clear move history so fetchBoard() doesn't rebuild from stale data

            // Reset Lobby & Match State
            this.whiteReady = false;
            this.blackReady = false;
            this.matchStarted = false;
            this.matchEnded = false;
            this.whiteFirstMoveDone = false;
            this.blackFirstMoveDone = false;
            this.abortPhase = AbortPhase.NONE;
            this.currentStatus = "White's Turn";

            // Reset Rematch State Machine
            this.rematchStatus = RematchStatus.NONE;
            this.rematchRequester = null;
            this.rematchTarget = null;
            this.rematchDeadline = 0L;

            // Reset Draw Trackers
            this.halfMoveClock = 0;
            this.positionHistory.clear();

            // Reset Clocks
            this.whiteTimeRemaining = 10 * 60 * 1000;
            this.blackTimeRemaining = 10 * 60 * 1000;
            this.lastMoveTimestamp = System.currentTimeMillis();

            // Wipe the Server Memory Bank
            this.moveHistory.clear();
            this.chatHistory.clear();
        }
    }

    // Reset board for a new match WITHOUT clearing player readiness/seats
    // Called when both players click "I AM READY" to start a fresh game
    // INTERNAL version - assumes caller holds stateLock
    private void resetBoardForNewMatchInternal() {
        this.board = new Board();
        this.currentTurn = Color.WHITE;
        this.moveHistory.clear();
        this.currentStatus = "White's Turn";
        this.matchEnded = false;
        this.whiteFirstMoveDone = false;
        this.blackFirstMoveDone = false;
        this.abortPhase = AbortPhase.NONE;

        // Reset Draw Trackers
        this.halfMoveClock = 0;
        this.positionHistory.clear();

        // Reset Clocks
        this.whiteTimeRemaining = 10 * 60 * 1000;
        this.blackTimeRemaining = 10 * 60 * 1000;
        this.lastMoveTimestamp = System.currentTimeMillis();

        // Note: Preserves whiteReady, blackReady, matchStarted, whiteToken, blackToken, chatHistory
    }

    // Public synchronized version for /sync consistency
    public void resetBoardForNewMatch() {
        synchronized (stateLock) {
            resetBoardForNewMatchInternal();
        }
    }

    // LEAVE TABLE METHOD
    public void clearSeats() {
        synchronized (stateLock) {
            if (whiteToken != null) revokedTokens.add(whiteToken);
            if (blackToken != null) revokedTokens.add(blackToken);
            this.whiteToken = null;
            this.blackToken = null;
            this.activeSpectators.clear();
        }
    }

    private void cleanInitialLobbyStateInternal() {
        cancelAbortTimerInternal(matchGeneration);
        cancelRematchTimerInternal();
        matchGeneration++;

        this.board = new Board();
        this.currentTurn = Color.WHITE;
        this.moveHistory.clear();
        this.positionHistory.clear();
        this.halfMoveClock = 0;

        this.whiteReady = false;
        this.blackReady = false;
        this.matchStarted = false;
        this.matchEnded = false;
        this.whiteFirstMoveDone = false;
        this.blackFirstMoveDone = false;
        this.abortPhase = AbortPhase.NONE;
        this.currentStatus = "White's Turn";

        this.rematchStatus = RematchStatus.NONE;
        this.rematchRequester = null;
        this.rematchTarget = null;
        this.rematchDeadline = 0L;

        this.whiteTimeRemaining = 10 * 60 * 1000;
        this.blackTimeRemaining = 10 * 60 * 1000;
        this.lastMoveTimestamp = System.currentTimeMillis();
    }

    // RELEASES ONLY THE SPECIFIED PLAYER'S SEAT (Pre-match readiness / waiting screen departure)
    public boolean releasePlayerSeat(String token, String color) {
        synchronized (stateLock) {
            // Guard 1: Cannot release seats if a match is actively in progress
            if (matchStarted && !matchEnded) {
                return false;
            }

            // Guard 2: Token and color must be non-null and non-empty
            if (token == null || token.trim().isEmpty() || color == null) {
                return false;
            }
            String cleanToken = token.trim();

            boolean released = false;

            if ("WHITE".equalsIgnoreCase(color)) {
                if (whiteToken != null && whiteToken.equals(cleanToken)) {
                    revokedTokens.add(whiteToken);
                    whiteToken = null;
                    whiteReady = false;
                    released = true;
                }
            } else if ("BLACK".equalsIgnoreCase(color)) {
                if (blackToken != null && blackToken.equals(cleanToken)) {
                    revokedTokens.add(blackToken);
                    blackToken = null;
                    blackReady = false;
                    released = true;
                }
            }

            // If both seats are now empty and no match is active, clean the lobby state
            if (released && whiteToken == null && blackToken == null && (!matchStarted || matchEnded)) {
                cleanInitialLobbyStateInternal();
            }

            return released;
        }
    }

    public boolean isTokenRevoked(String token) {
        return token != null && revokedTokens.contains(token);
    }

    public boolean isPlayerTokenActive(String token) {
        synchronized (stateLock) {
            if (token == null || token.isEmpty()) return false;
            if (revokedTokens.contains(token)) return false;
            return token.equals(whiteToken) || token.equals(blackToken);
        }
    }

    public Board getBoard() {
        synchronized (stateLock) {
            return board;
        }
    }

    // ==========================================
    // MAIN MOVE ENGINE
    // ==========================================
    public String playerMove(int startX, int startY, int endX, int endY, String promotion) {
        synchronized (stateLock) {
            // Guard: reject moves if the match is not active
            if (!matchStarted) {
                return "ERROR: Match has not started!";
            }

        Spot startBox = board.getBox(startX, startY);
        Spot endBox = board.getBox(endX, endY);
        Piece pieceToMove = startBox.getPiece();

        // --- PHASE 1: BASIC VALIDATION ---
        if (pieceToMove == null) {
            return "ERROR: No piece at the starting position!";
        }
        if (pieceToMove.getColor() != currentTurn) {
            return "ERROR: It is " + currentTurn + "'s turn!";
        }
        if (!pieceToMove.canMove(board, startBox, endBox)) {
            return "ERROR: Invalid move for that piece!";
        }

        // Prevent Castling while in Check
        if (pieceToMove instanceof King && Math.abs(startY - endY) == 2) {
            if (isKingInCheck(currentTurn)) {
                return "ERROR: You cannot castle while in Check!";
            }
            // Prevent Castling THROUGH an attacked square (transit square check)
            int direction = (endY > startY) ? 1 : -1;
            Spot transitSpot = board.getBox(startX, startY + direction);
            if (isSquareAttackedBy(transitSpot, currentTurn == Color.WHITE ? Color.BLACK : Color.WHITE)) {
                return "ERROR: You cannot castle through an attacked square!";
            }
        }

        // --- PHASE 2: CHECK SIMULATION ---
        // Temporarily make the move to see if it exposes our King
        Piece destPiece = endBox.getPiece();
        endBox.setPiece(pieceToMove);
        startBox.setPiece(null);

        // For En Passant: also remove the captured pawn during simulation
        Spot epVictimSpot = null;
        Piece epVictim = null;
        if (pieceToMove instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
            epVictimSpot = board.getBox(startX, endY);
            epVictim = epVictimSpot.getPiece();
            epVictimSpot.setPiece(null);
        }

        boolean stillInCheck = isKingInCheck(currentTurn);

        // Undo the simulation
        startBox.setPiece(pieceToMove);
        endBox.setPiece(destPiece);
        if (epVictimSpot != null) {
            epVictimSpot.setPiece(epVictim);
        }

        if (stillInCheck) {
            return "ERROR: You cannot make a move that leaves your King in Check!";
        }

        // --- TIME MANAGEMENT ---
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastMoveTimestamp;

        if (currentTurn == Color.WHITE) {
            whiteTimeRemaining -= elapsed;
            if (whiteTimeRemaining <= 0) {
                whiteTimeRemaining = 0;
                endMatch();
                String status = "TIME_OUT! Black wins on time!";
                currentStatus = status;
                return status;
            }
        } else {
            blackTimeRemaining -= elapsed;
            if (blackTimeRemaining <= 0) {
                blackTimeRemaining = 0;
                endMatch();
                String status = "TIME_OUT! White wins on time!";
                currentStatus = status;
                return status;
            }
        }
        lastMoveTimestamp = currentTime;

        // --- PHASE 3: PERMANENT EXECUTION ---

        // Actually move the piece to its new position on the board.
        // (The simulation in Phase 2 was fully undone — we must re-apply it permanently here.)
        endBox.setPiece(pieceToMove);
        startBox.setPiece(null);

        // 50-Move Rule tracker
        if (pieceToMove instanceof Pawn || destPiece != null) {
            halfMoveClock = 0;
            positionHistory.clear(); // Irreversible moves break repetition chains
        } else {
            halfMoveClock++;
        }

        if (destPiece != null) {
            destPiece.setKilled(true);
        }

        // Execute En Passant capture
        if (pieceToMove instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
            Spot victim = board.getBox(startX, endY);
            if (victim.getPiece() != null) {
                victim.getPiece().setKilled(true);
                victim.setPiece(null);
            }
        }

        // Handle Pawn Promotion
        if (pieceToMove instanceof Pawn) {
            if ((currentTurn == Color.WHITE && endX == 7) || (currentTurn == Color.BLACK && endX == 0)) {
                // Default to Queen if no promotion choice was provided
                String promoChoice = (promotion != null && !promotion.isEmpty()) ? promotion.toUpperCase() : "Q";
                Piece newPiece;
                switch (promoChoice) {
                    case "R": newPiece = new Rook(currentTurn); break;
                    case "B": newPiece = new Bishop(currentTurn); break;
                    case "N": newPiece = new Knight(currentTurn); break;
                    case "Q":
                    default:  newPiece = new Queen(currentTurn); break;
                }
                newPiece.setHasMoved(true);
                endBox.setPiece(newPiece);
            }
        }

        // Handle Castling Rook Teleportation
        if (pieceToMove instanceof King && Math.abs(startY - endY) == 2) {
            int direction = (endY > startY) ? 1 : -1;
            int rookOriginalY = (direction == 1) ? 7 : 0;
            int rookNewY = endY - direction;

            Spot rookStartBox = board.getBox(startX, rookOriginalY);
            Spot rookEndBox = board.getBox(startX, rookNewY);
            Piece rook = rookStartBox.getPiece();

            rookEndBox.setPiece(rook);
            rookStartBox.setPiece(null);
            if (rook != null) rook.setHasMoved(true);
        }

        // Set the En Passant Target for next turn
        board.setEnPassantTarget(null);
        if (pieceToMove instanceof Pawn && Math.abs(startX - endX) == 2) {
            int targetX = (startX + endX) / 2;
            board.setEnPassantTarget(board.getBox(targetX, startY));
        }

        pieceToMove.setHasMoved(true);

        Color enemyColor = (currentTurn == Color.WHITE) ? Color.BLACK : Color.WHITE;
        currentTurn = enemyColor;

        // Threefold Repetition snapshot (taken AFTER move and turn switch)
        String currentState = getBoardStateString();
        positionHistory.put(currentState, positionHistory.getOrDefault(currentState, 0) + 1);
        boolean isThreefold = positionHistory.get(currentState) >= 3;

        // --- WIN / DRAW CHECKS ---
        String resultMessage;
        if (isKingInCheck(enemyColor)) {
            if (isCheckmate(enemyColor)) {
                // enemyColor is the one in checkmate; the winner is the opposite (the one who just moved)
                resultMessage = "CHECKMATE! " + (enemyColor == Color.WHITE ? "BLACK" : "WHITE") + " wins!";
                endMatch();
            } else {
                resultMessage = "CHECK! " + enemyColor + "'s King is under attack!";
            }
        } else {
            if (isStalemate(enemyColor)) {
                resultMessage = "DRAW! Game ended in Stalemate.";
                endMatch();
            } else if (isInsufficientMaterial()) {
                resultMessage = "DRAW! Insufficient Material.";
                endMatch();
            } else if (halfMoveClock >= 100) {
                resultMessage = "DRAW! 50-Move Rule.";
                endMatch();
            } else if (isThreefold) {
                resultMessage = "DRAW! Threefold Repetition.";
                endMatch();
            } else {
                resultMessage = enemyColor + "'s Turn";
            }
        }

        currentStatus = resultMessage;

        // Auto-abort first-move progression: only applies to move 1 of White and move 1 of Black
        if (pieceToMove.getColor() == Color.WHITE && !whiteFirstMoveDone) {
            whiteFirstMoveDone = true;
            if (abortTimer != null) {
                abortTimer.cancel(false);
                abortTimer = null;
            }
            if (!matchEnded && matchStarted) {
                startBlackGracePeriodInternal(matchGeneration, this.messagingTemplate);
            }
        } else if (pieceToMove.getColor() == Color.BLACK && !blackFirstMoveDone) {
            blackFirstMoveDone = true;
            cancelAbortTimerInternal(matchGeneration);
            abortPhase = AbortPhase.NONE;
            System.out.println("✅ BOTH FIRST MOVES COMPLETE: Auto-abort permanently disabled for remainder of match.");
        }

        return resultMessage;
        }
    }

    // ==========================================
    // HELPER: Is a given Spot attacked by any piece of attackerColor?
    // Used for castling transit-square validation.
    // ==========================================
    public boolean isSquareAttackedBy(Spot target, Color attackerColor) {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p != null && p.getColor() == attackerColor) {
                    if (p.canMove(board, board.getBox(i, j), target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isKingInCheck(Color kingColor) {
        Spot kingSpot = null;

        // Step 1: Find the King on the board
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p instanceof King && p.getColor() == kingColor) {
                    kingSpot = board.getBox(i, j);
                    break;
                }
            }
            if (kingSpot != null) break;
        }

        if (kingSpot == null) return false;

        // Step 2: Check if any enemy piece can attack the King's spot
        Color enemyColor = (kingColor == Color.WHITE) ? Color.BLACK : Color.WHITE;
        return isSquareAttackedBy(kingSpot, enemyColor);
    }

    public boolean isCheckmate(Color color) {
        if (!isKingInCheck(color)) {
            return false;
        }
        return !hasAnyLegalMove(color);
    }

    // Allows the server to explicitly check for a timeout without needing a move
    public String checkTimeout() {
        synchronized (stateLock) {
            if (!matchStarted) return "SAFE";

            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - lastMoveTimestamp;

            if (currentTurn == Color.WHITE) {
                if (whiteTimeRemaining - elapsed <= 0) {
                    whiteTimeRemaining = 0;
                    endMatch();
                    String status = "TIME_OUT! Black wins on time!";
                    currentStatus = status;
                    return status;
                }
            } else {
                if (blackTimeRemaining - elapsed <= 0) {
                    blackTimeRemaining = 0;
                    endMatch();
                    String status = "TIME_OUT! White wins on time!";
                    currentStatus = status;
                    return status;
                }
            }
            return "SAFE";
        }
    }

    // Returns a list of valid [x, y] coordinates for a given piece
    public List<int[]> getValidMoves(int startX, int startY) {
        List<int[]> validMoves = new ArrayList<>();
        Spot startBox = board.getBox(startX, startY);
        Piece piece = startBox.getPiece();

        if (piece == null) {
            return validMoves;
        }

        for (int endX = 0; endX < 8; endX++) {
            for (int endY = 0; endY < 8; endY++) {
                Spot endBox = board.getBox(endX, endY);

                if (piece.canMove(board, startBox, endBox)) {

                    // Skip castling if in check or if the transit square is attacked
                    if (piece instanceof King && Math.abs(startY - endY) == 2) {
                        if (isKingInCheck(piece.getColor())) continue;
                        int direction = (endY > startY) ? 1 : -1;
                        Spot transitSpot = board.getBox(startX, startY + direction);
                        Color enemyColor = (piece.getColor() == Color.WHITE) ? Color.BLACK : Color.WHITE;
                        if (isSquareAttackedBy(transitSpot, enemyColor)) continue;
                    }

                    // SIMULATION: temporarily make the move
                    Piece destPiece = endBox.getPiece();

                    // Handle EP victim removal during simulation
                    Piece epVictim = null;
                    Spot epVictimSpot = null;
                    if (piece instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
                        epVictimSpot = board.getBox(startX, endY);
                        epVictim = epVictimSpot.getPiece();
                        epVictimSpot.setPiece(null);
                    }

                    endBox.setPiece(piece);
                    startBox.setPiece(null);

                    boolean isSafe = !isKingInCheck(piece.getColor());

                    // UNDO SIMULATION
                    startBox.setPiece(piece);
                    endBox.setPiece(destPiece);
                    if (epVictimSpot != null) {
                        epVictimSpot.setPiece(epVictim);
                    }

                    if (isSafe) {
                        validMoves.add(new int[]{endX, endY});
                    }
                }
            }
        }
        return validMoves;
    }

    public boolean isStalemate(Color color) {
        if (isKingInCheck(color)) {
            return false;
        }
        return !hasAnyLegalMove(color);
    }

    // Shared helper: does `color` have at least one legal move?
    // Used by isCheckmate() and isStalemate() to avoid code duplication.
    private boolean hasAnyLegalMove(Color color) {
        for (int startX = 0; startX < 8; startX++) {
            for (int startY = 0; startY < 8; startY++) {
                Spot startBox = board.getBox(startX, startY);
                Piece piece = startBox.getPiece();

                if (piece == null || piece.getColor() != color) continue;

                for (int endX = 0; endX < 8; endX++) {
                    for (int endY = 0; endY < 8; endY++) {
                        Spot endBox = board.getBox(endX, endY);

                        if (!piece.canMove(board, startBox, endBox)) continue;

                        // SIMULATE THE MOVE
                        Piece destPiece = endBox.getPiece();

                        // EP victim removal
                        Piece epVictim = null;
                        Spot epVictimSpot = null;
                        if (piece instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
                            epVictimSpot = board.getBox(startX, endY);
                            epVictim = epVictimSpot.getPiece();
                            epVictimSpot.setPiece(null);
                        }

                        endBox.setPiece(piece);
                        startBox.setPiece(null);

                        boolean stillInCheck = isKingInCheck(color);

                        // UNDO
                        startBox.setPiece(piece);
                        endBox.setPiece(destPiece);
                        if (epVictimSpot != null) {
                            epVictimSpot.setPiece(epVictim);
                        }

                        if (!stillInCheck) {
                            return true; // At least one legal move exists
                        }
                    }
                }
            }
        }
        return false;
    }

    // Converts the board to a canonical string for threefold repetition tracking.
    // Uses 'N' explicitly for Knight to avoid collision with King ('K').
    private String getBoardStateString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p == null) {
                    sb.append("-");
                } else {
                    char colorChar = (p.getColor() == Color.WHITE) ? 'w' : 'b';
                    char typeChar;
                    if (p instanceof Knight) {
                        typeChar = 'N'; // Explicitly 'N' to avoid collision with King 'K'
                    } else {
                        typeChar = p.getClass().getSimpleName().charAt(0);
                    }
                    sb.append(colorChar).append(typeChar);
                }
            }
        }
        sb.append(currentTurn); // Whose turn matters for repetition
        return sb.toString();
    }

    // Checks if checkmate is mathematically impossible (insufficient material)
    private boolean isInsufficientMaterial() {
        int whiteMinors = 0, blackMinors = 0;
        int majorPieces = 0;

        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p != null) {
                    if (p instanceof Queen || p instanceof Rook || p instanceof Pawn) {
                        majorPieces++;
                    } else if (p instanceof Knight || p instanceof Bishop) {
                        if (p.getColor() == Color.WHITE) whiteMinors++;
                        else blackMinors++;
                    }
                }
            }
        }

        if (majorPieces > 0) return false;

        // King vs King
        if (whiteMinors == 0 && blackMinors == 0) return true;

        // King + 1 minor vs King
        if ((whiteMinors == 1 && blackMinors == 0) || (blackMinors == 1 && whiteMinors == 0)) return true;

        return false;
    }
}
