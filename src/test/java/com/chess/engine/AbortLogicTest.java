package com.chess.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AbortLogicTest {

    private Game game;
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    public void setUp() {
        game = new Game();
        messagingTemplate = mock(SimpMessagingTemplate.class);
    }

    @Test
    public void testMatchStartInitiatesSilentGracePeriodForWhite() {
        game.assignPlayer(null); // White
        game.assignPlayer(null); // Black

        game.setPlayerReady("WHITE");
        boolean starting = game.setPlayerReady("BLACK");
        assertTrue(starting, "Match should start when both players ready");

        // Controller starts the abort timer
        game.startAbortTimer(messagingTemplate);

        Map<String, Object> state = game.getAbortTimerState();
        assertTrue((Boolean) state.get("active"), "Abort timer should be active");
        assertFalse((Boolean) state.get("warningActive"), "Grace period must be SILENT (warningActive false)");
        assertEquals("WHITE_GRACE", state.get("phase"), "Phase should be WHITE_GRACE");
        assertEquals("WHITE", state.get("color"), "Abort countdown should target White on turn 1");
        assertEquals(15, (Integer) state.get("remainingSeconds"), "Grace period should be 15s");
        assertFalse(game.isWhiteFirstMoveDone(), "White first move should not be done yet");
    }

    @Test
    public void testWhiteGracePeriodExpiryTransitionsToWarning() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // Simulate 15s grace period expiring
        game.triggerGracePeriodExpiryForTesting();

        Map<String, Object> state = game.getAbortTimerState();
        assertTrue((Boolean) state.get("active"), "Abort timer should remain active");
        assertTrue((Boolean) state.get("warningActive"), "Warning must now be active (warningActive true)");
        assertEquals("WHITE_WARNING", state.get("phase"), "Phase should be WHITE_WARNING");
        assertEquals("WHITE", state.get("color"));

        // Verify warning broadcast occurred
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/game"), any(Object.class));
    }

    @Test
    public void testWhiteWarningExpiryAbortsMatch() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // Transition to warning
        game.triggerGracePeriodExpiryForTesting();

        // Simulate 15s warning period expiring (30s total)
        game.triggerAbortTimeoutForTesting(messagingTemplate);

        assertFalse(game.isMatchStarted(), "Match should no longer be marked as started");
        assertTrue(game.hasMatchEnded(), "Match should be marked as ended");
        assertTrue(game.getCurrentStatus().contains("MATCH ABORTED"), "Status should indicate abort: " + game.getCurrentStatus());
        assertTrue(game.getCurrentStatus().contains("WHITE"), "Status should identify White as inactive player");
        assertFalse((Boolean) game.getAbortTimerState().get("active"), "Abort timer should no longer be active");

        // Verify timeout broadcast occurred
        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/game"), any(Object.class));
    }

    @Test
    public void testWhiteMoveTransitionsToBlackSilentGracePeriod() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // White moves before timeout (e2 -> e4: 1,4 -> 3,4)
        String status = game.playerMove(1, 4, 3, 4, null);
        assertFalse(status.contains("ERROR"), "Move e2-e4 should be valid: " + status);
        assertTrue(game.isWhiteFirstMoveDone(), "White first move must be recorded as done");
        assertFalse(game.isBlackFirstMoveDone(), "Black first move not yet done");

        Map<String, Object> state = game.getAbortTimerState();
        assertTrue((Boolean) state.get("active"), "Abort timer should be active for Black");
        assertFalse((Boolean) state.get("warningActive"), "Black must start with a SILENT grace period");
        assertEquals("BLACK_GRACE", state.get("phase"));
        assertEquals("BLACK", state.get("color"));
    }

    @Test
    public void testBlackGracePeriodExpiryTransitionsToWarning() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // White moves
        game.playerMove(1, 4, 3, 4, null);

        // Black grace period expires
        game.triggerGracePeriodExpiryForTesting();

        Map<String, Object> state = game.getAbortTimerState();
        assertTrue((Boolean) state.get("active"));
        assertTrue((Boolean) state.get("warningActive"), "Black warning must be active");
        assertEquals("BLACK_WARNING", state.get("phase"));
        assertEquals("BLACK", state.get("color"));
    }

    @Test
    public void testBlackWarningExpiryAbortsMatch() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // White moves
        game.playerMove(1, 4, 3, 4, null);

        // Black grace expires -> warning
        game.triggerGracePeriodExpiryForTesting();

        // Black warning expires (30s from White's move)
        game.triggerAbortTimeoutForTesting(messagingTemplate);

        assertTrue(game.hasMatchEnded());
        assertTrue(game.getCurrentStatus().contains("MATCH ABORTED"));
        assertTrue(game.getCurrentStatus().contains("BLACK"), "Status should identify Black: " + game.getCurrentStatus());
        assertFalse((Boolean) game.getAbortTimerState().get("active"));
    }

    @Test
    public void testBlackMovePermanentlyDisablesAutoAbortForRemainderOfMatch() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // 1. White plays move 1 (e2 -> e4)
        game.playerMove(1, 4, 3, 4, null);
        assertTrue(game.isWhiteFirstMoveDone());

        // 2. Black plays move 1 (e7 -> e5: 6,4 -> 4,4)
        String statusBlack = game.playerMove(6, 4, 4, 4, null);
        assertFalse(statusBlack.contains("ERROR"));
        assertTrue(game.isBlackFirstMoveDone(), "Black first move must be recorded as done");

        // Auto-abort must now be permanently disabled
        Map<String, Object> stateAfterBothFirstMoves = game.getAbortTimerState();
        assertFalse((Boolean) stateAfterBothFirstMoves.get("active"), "Auto-abort must be permanently disabled");
        assertFalse((Boolean) stateAfterBothFirstMoves.get("warningActive"));
        assertEquals("NONE", stateAfterBothFirstMoves.get("phase"));
        assertEquals(Game.AbortPhase.NONE, game.getAbortPhase());

        // 3. Subsequent moves (move 2, 3...) must NEVER reactivate auto-abort
        // White plays Nf3 (0,6 -> 2,5)
        String statusWhite2 = game.playerMove(0, 6, 2, 5, null);
        assertFalse(statusWhite2.contains("ERROR"));
        assertFalse((Boolean) game.getAbortTimerState().get("active"), "Turn 2 must NOT activate abort timer");

        // Black plays Nc6 (7,1 -> 5,2)
        String statusBlack2 = game.playerMove(7, 1, 5, 2, null);
        assertFalse(statusBlack2.contains("ERROR"));
        assertFalse((Boolean) game.getAbortTimerState().get("active"), "Turn 2 for Black must NOT activate abort timer");
    }

    @Test
    public void testIllegalMoveDoesNotResetOrCancelCountdown() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        long initialDeadline = (Long) game.getAbortTimerState().get("deadline");
        long initialPhaseGen = game.getPhaseGeneration();

        // Try an illegal move (pawn e2 to e5)
        String errorStatus = game.playerMove(1, 4, 4, 4, null);
        assertTrue(errorStatus.contains("ERROR"), "Move should be rejected");

        // Abort timer must remain completely untouched
        Map<String, Object> stateAfterError = game.getAbortTimerState();
        assertTrue((Boolean) stateAfterError.get("active"));
        assertEquals("WHITE", stateAfterError.get("color"));
        assertEquals(initialDeadline, stateAfterError.get("deadline"), "Deadline must not change on illegal move");
        assertEquals(initialPhaseGen, game.getPhaseGeneration(), "Phase generation must not change");
        assertFalse(game.isWhiteFirstMoveDone());
    }

    @Test
    public void testGameEndCancelsAbortTimer() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // Player resigns
        game.resign("WHITE");

        Map<String, Object> state = game.getAbortTimerState();
        assertFalse((Boolean) state.get("active"), "Abort timer must be cancelled after resignation");
        assertTrue(game.hasMatchEnded());
    }

    @Test
    public void testRematchResetsFirstMoveFlags() {
        game.assignPlayer(null);
        game.assignPlayer(null);
        game.setPlayerReady("WHITE");
        game.setPlayerReady("BLACK");
        game.startAbortTimer(messagingTemplate);

        // End match via abort
        game.abort();
        assertFalse((Boolean) game.getAbortTimerState().get("active"));

        // Both request rematch
        game.requestRematch("WHITE");
        Map<String, Object> rematchResult = game.requestRematch("BLACK");
        assertTrue((Boolean) rematchResult.get("matchStarted"), "Rematch should start");

        // Start abort timer for new match
        game.startAbortTimer(messagingTemplate);

        Map<String, Object> state = game.getAbortTimerState();
        assertTrue((Boolean) state.get("active"));
        assertFalse((Boolean) state.get("warningActive"), "New match starts with silent grace period");
        assertEquals("WHITE_GRACE", state.get("phase"));
        assertEquals("WHITE", state.get("color"), "Rematch should begin with White abort countdown");
        assertFalse(game.isWhiteFirstMoveDone(), "White first move flag must reset in rematch");
        assertFalse(game.isBlackFirstMoveDone(), "Black first move flag must reset in rematch");
    }
}
