package com.chess.engine;

import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;


@RestController
@CrossOrigin
public class ChessController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    private Game game = new Game();

    // ==========================================
    // PUBLIC ACCESSOR: used by WebSocketEventListener
    // to decide whether to start an abandon timer
    // ==========================================
    public boolean isMatchActive() {
        return game.isMatchStarted();
    }

    // ==========================================
    // THE RECONNECT FIX: Link STOMP Sessions to Players
    // ==========================================
    @MessageMapping("/register")
    public void registerSession(@Payload String color, @Header("simpSessionId") String sessionId) {
        // STOMP sometimes wraps strings with literal quotes. This cleans it up.
        String cleanColor = color.replace("\"", "").trim();
        System.out.println("🔗 HANDSHAKE SUCCESS: " + cleanColor + " registered to session " + sessionId);

        webSocketEventListener.registerPlayerSession(sessionId, cleanColor);
        disarmGhostTimer(cleanColor);
    }

    // Endpoint for players to claim an identity when they open the link
    @GetMapping("/join")
    public String join(@RequestParam(required = false) String token) {
        return game.assignPlayer(token);
    }

    @GetMapping("/sync")
    public Map<String, Object> syncGameState() {
        Map<String, Object> state = new HashMap<>();
        state.put("grid", getBoardState());
        state.put("whiteTime", game.getWhiteTimeRemaining());
        state.put("blackTime", game.getBlackTimeRemaining());
        state.put("moveHistory", game.getMoveHistory());
        state.put("matchStarted", game.isMatchStarted());
        // Tell the client whose turn it is so the correct clock ticks
        state.put("currentTurn", game.getCurrentTurn().toString());
        return state;
    }

    // Endpoint for the Ready Button
    @GetMapping("/ready")
    public String playerReady(@RequestParam String color) {
        boolean isStarting = game.setPlayerReady(color);

        if (isStarting) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "START");
            // Include whose turn it is so clients can initialize the correct clock
            payload.put("currentTurn", game.getCurrentTurn().toString());
            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return "OK";
    }

    // Endpoint for Resign / Abort
    @GetMapping("/action")
    public String playerAction(@RequestParam String action, @RequestParam String color) {
        disarmGhostTimer(color);

        // game.resign() / game.abort() both call endMatch() internally
        String status = "RESIGN".equals(action) ? game.resign(color) : game.abort();

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "MOVE");
        payload.put("status", status);
        payload.put("grid", getBoardState());
        payload.put("pieceCode", "");
        payload.put("startX", 0); payload.put("startY", 0);
        payload.put("endX", 0); payload.put("endY", 0);
        payload.put("whiteTime", game.getWhiteTimeRemaining());
        payload.put("blackTime", game.getBlackTimeRemaining());

        game.addMoveToHistory(payload);
        messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        return status;
    }

    @GetMapping("/validMoves")
    public List<int[]> getValidMoves(@RequestParam int startX, @RequestParam int startY) {
        return game.getValidMoves(startX, startY);
    }

    @GetMapping("/move")
    public String movePiece(@RequestParam int startX, @RequestParam int startY,
                            @RequestParam int endX, @RequestParam int endY,
                            @RequestParam(required = false) String promotion,
                            @RequestParam String pieceCode) {

        // Disarm the ghost bomb so making a move counts as being active
        if (pieceCode != null && !pieceCode.isEmpty()) {
            String activeColor = pieceCode.startsWith("w") ? "WHITE" : "BLACK";
            disarmGhostTimer(activeColor);
        }

        // game.playerMove() now guards against !matchStarted internally
        String status = game.playerMove(startX, startY, endX, endY, promotion);

        if (!status.contains("ERROR")) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MOVE");
            payload.put("status", status);
            payload.put("grid", getBoardState());
            payload.put("pieceCode", pieceCode);
            payload.put("startX", startX);
            payload.put("startY", startY);
            payload.put("endX", endX);
            payload.put("endY", endY);
            payload.put("promotion", promotion);
            payload.put("whiteTime", game.getWhiteTimeRemaining());
            payload.put("blackTime", game.getBlackTimeRemaining());
            // Tell clients whose turn it is now so the correct clock starts ticking
            payload.put("currentTurn", game.getCurrentTurn().toString());

            game.addMoveToHistory(payload);
            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return status;
    }

    @GetMapping("/reset")
    public String reset() {
        game.resetGame();

        Map<String, String> payload = new HashMap<>();
        payload.put("type", "RESET");
        messagingTemplate.convertAndSend("/topic/game", payload);

        return "Reset successful";
    }

    // Arcade Cabinet Reset — wipes seats and board, kicks everyone to refresh
    @GetMapping("/leave")
    public String leaveTable() {
        game.clearSeats();
        game.resetGame();

        Map<String, String> payload = new HashMap<>();
        payload.put("type", "KICK");
        messagingTemplate.convertAndSend("/topic/game", payload);

        return "Table Cleared";
    }

    // ==========================================
    // ABANDONMENT: Award win to remaining player
    // ==========================================
    public void handleAbandonment(String droppedColor) {
        if (!game.isMatchStarted()) {
            System.out.println("Match is already over. Ignoring abandonment for " + droppedColor);
            return;
        }

        // Force the backend to officially end the match
        game.resign(droppedColor);

        String winner = droppedColor.equals("WHITE") ? "Black" : "White";
        String status = droppedColor + " ABANDONED. " + winner + " wins!";

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "MOVE");
        payload.put("status", status);
        payload.put("grid", getBoardState());
        payload.put("pieceCode", "");
        payload.put("startX", 0); payload.put("startY", 0);
        payload.put("endX", 0); payload.put("endY", 0);
        payload.put("whiteTime", game.getWhiteTimeRemaining());
        payload.put("blackTime", game.getBlackTimeRemaining());

        game.addMoveToHistory(payload);
        messagingTemplate.convertAndSend("/topic/game", (Object) payload);
    }

    // Board state snapshot helper
    @GetMapping("/board")
    public String[][] getBoardState() {
        String[][] grid = new String[8][8];

        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = game.getBoard().getBox(i, j).getPiece();

                if (p == null) {
                    grid[i][j] = "";
                } else {
                    String color = p.getColor() == Color.WHITE ? "w" : "b";
                    String type;

                    switch (p) {
                        case King king     -> type = "K";
                        case Queen queen   -> type = "Q";
                        case Rook rook     -> type = "R";
                        case Bishop bishop -> type = "B";
                        case Knight knight -> type = "N";
                        case Pawn pawn     -> type = "P";
                        default            -> type = "";
                    }

                    grid[i][j] = color + type;
                }
            }
        }
        return grid;
    }

    // Endpoint for the frontend to trigger a timeout check
    @GetMapping("/timeout")
    public String triggerTimeout() {
        String status = game.checkTimeout();

        if (status.contains("TIME_OUT")) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MOVE");
            payload.put("status", status);
            payload.put("grid", getBoardState());
            payload.put("pieceCode", "");
            payload.put("startX", 0); payload.put("startY", 0);
            payload.put("endX", 0); payload.put("endY", 0);
            payload.put("whiteTime", game.getWhiteTimeRemaining());
            payload.put("blackTime", game.getBlackTimeRemaining());

            game.addMoveToHistory(payload);
            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return status;
    }

    // ==========================================
    // GHOST BOMB DISARMER
    // ==========================================
    private void disarmGhostTimer(String color) {
        if (color == null) return;

        java.util.concurrent.ScheduledFuture<?> activeTimer =
                WebSocketEventListener.disconnectTimers.remove(color.toUpperCase());

        if (activeTimer != null) {
            activeTimer.cancel(true);
            System.out.println("🛡️ GHOST BOMB DISARMED: Canceled lingering disconnect timer for " + color);

            Map<String, String> reconnectPayload = new HashMap<>();
            reconnectPayload.put("type", "RECONNECT_SUCCESS");
            reconnectPayload.put("color", color.toUpperCase());
            messagingTemplate.convertAndSend("/topic/game", (Object) reconnectPayload);
        }
    }

    // ==========================================
    // IN-GAME CHAT SYSTEM
    // ==========================================
    @MessageMapping("/chat")
    public void handleChat(Map<String, String> payload) {
        // Disarm ghost timer — chatting counts as being active
        disarmGhostTimer(payload.get("sender"));

        payload.put("type", "CHAT");
        messagingTemplate.convertAndSend("/topic/game", (Object) payload);
    }
}
