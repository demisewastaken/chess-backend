package com.chess.engine;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;


@RestController // Tells Spring Boot: "This class is a Waiter that listens to the internet"
@CrossOrigin    // A security bypass that allows our future HTML website to talk to this server
public class ChessController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    private Game game = new Game();

    // NEW: The matchmaking bucket
    private List<String> unassignedColors;

    public ChessController() {
        // When the server starts, fill the bucket and shuffle it!
        unassignedColors = new ArrayList<>(Arrays.asList("WHITE", "BLACK"));
        Collections.shuffle(unassignedColors);
    }

    // ==========================================
    // THE RECONNECT FIX: Link STOMP Sessions to Players
    // =========================================
    @MessageMapping("/register")
    public void registerSession(@Payload String color, @Header("simpSessionId") String sessionId) {
        // STOMP sometimes sends strings with literal quotes like ""WHITE"". This cleans it up!
        String cleanColor = color.replace("\"", "").trim();

        // Add a print statement so we can visibly PROVE the handshake worked
        System.out.println("🔗 HANDSHAKE SUCCESS: " + cleanColor + " registered to session " + sessionId);

        webSocketEventListener.registerPlayerSession(sessionId, cleanColor);
    }
    // NEW: Endpoint for players to claim an identity when they open the link
    // We completely removed HttpSession!
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

        // NEW: Tell the browser if the match is actively running!
        state.put("matchStarted", game.isMatchStarted());
        return state;
    }

    // NEW: Endpoint for the Ready Button
    @GetMapping("/ready")
    public String playerReady(@RequestParam String color) {
        boolean isStarting = game.setPlayerReady(color);

        // ONLY broadcast if the match is officially starting!
        if (isStarting) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "START");
            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return "OK";
    }

    // NEW: Endpoint for Resign / Abort
    @GetMapping("/action")
    public String playerAction(@RequestParam String action, @RequestParam String color) {
        String status = "RESIGN".equals(action) ? game.resign(color) : game.abort();

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "MOVE"); // We pretend it's a move so JS handles it naturally
        payload.put("status", status);
        payload.put("grid", getBoardState());
        payload.put("pieceCode", "");
        payload.put("startX", 0); payload.put("startY", 0);
        payload.put("endX", 0); payload.put("endY", 0);
        payload.put("whiteTime", game.getWhiteTimeRemaining());
        payload.put("blackTime", game.getBlackTimeRemaining());

        // Save the game-ending action to memory
        game.addMoveToHistory(payload);

        messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        return status;
    }

    // This creates a link: http://localhost:8080/validMoves?startX=...&startY=...
    @GetMapping("/validMoves")
    public List<int[]> getValidMoves(@RequestParam int startX, @RequestParam int startY) {
        // Returns a JSON array of coordinates, like: [[2, 4], [3, 4]]
        return game.getValidMoves(startX, startY);
    }

    // This creates a web link: http://localhost:8080/move
    @GetMapping("/move")
    public String movePiece(@RequestParam int startX, @RequestParam int startY,
                            @RequestParam int endX, @RequestParam int endY,
                            @RequestParam(required = false) String promotion,
                            @RequestParam String pieceCode) { // NEW: Ask JS what piece moved

        String status = game.playerMove(startX, startY, endX, endY, promotion);

        if (!status.contains("ERROR")) {
            // MOVE WAS SUCCESSFUL! Build a massive data packet to shout to all players.
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MOVE");
            payload.put("status", status);
            payload.put("grid", getBoardState()); // Broadcast the new grid directly!
            payload.put("pieceCode", pieceCode);
            payload.put("startX", startX);
            payload.put("startY", startY);
            payload.put("endX", endX);
            payload.put("endY", endY);
            payload.put("promotion", promotion);
            // NEW: Add the official server time to the broadcast
            payload.put("whiteTime", game.getWhiteTimeRemaining());
            payload.put("blackTime", game.getBlackTimeRemaining());

            // Save the payload to the server's memory bank!
            game.addMoveToHistory(payload);
            // Blast it to the "/topic/game" radio channel

            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return status;
    }

    // A handy link to restart the game: http://localhost:8080/reset
    @GetMapping("/reset")
    public String reset() {
        // 1. Trigger the master reset switch in Game.java
        game.resetGame();

        // 2. Broadcast the reset command to all connected browsers
        Map<String, String> payload = new HashMap<>();
        payload.put("type", "RESET");
        messagingTemplate.convertAndSend("/topic/game", payload);

        return "Reset successful";
    }

    // --- NEW: Arcade Cabinet Reset ---
    @GetMapping("/leave")
    public String leaveTable() {
        // 1. Wipe the secret tokens
        game.clearSeats();

        // 2. Wipe the board and clocks
        game.resetGame();

        // 3. Tell EVERY connected browser to forcefully refresh!
        Map<String, String> payload = new HashMap<>();
        payload.put("type", "KICK");
        messagingTemplate.convertAndSend("/topic/game", payload);

        return "Table Cleared";
    }

    // ==========================================
    // THE ABANDONMENT FIX: Award the win to the remaining player
    // ==========================================
    public void handleAbandonment(String droppedColor) {
        // 1. Force the backend game engine to officially end the match
        game.resign(droppedColor);

        // 2. Determine the winner
        String winner = droppedColor.equals("WHITE") ? "Black" : "White";
        String status = droppedColor + " ABANDONED. " + winner + " wins!";

        // 3. Build a "Game Over" payload that the frontend already knows how to read!
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "MOVE"); // Pretend it's a move so the JS overlay triggers naturally
        payload.put("status", status);
        payload.put("grid", getBoardState());
        payload.put("pieceCode", "");
        payload.put("startX", 0); payload.put("startY", 0);
        payload.put("endX", 0); payload.put("endY", 0);
        payload.put("whiteTime", game.getWhiteTimeRemaining());
        payload.put("blackTime", game.getBlackTimeRemaining());

        // Save it to history and broadcast it
        game.addMoveToHistory(payload);
        messagingTemplate.convertAndSend("/topic/game", (Object) payload);
    }

    // This creates a web link: http://localhost:8080/board
    @GetMapping("/board")
    public String[][] getBoardState() {
        // We will create a simple 8x8 grid of text to send to the website
        String[][] grid = new String[8][8];

        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = game.getBoard().getBox(i, j).getPiece();

                if (p == null) {
                    grid[i][j] = ""; // Empty square
                } else {
                    String color = p.getColor() == Color.WHITE ? "w" : "b";
                    String type = "";

                    switch (p) {
                        case King king -> type = "K";
                        case Queen queen -> type = "Q";
                        case Rook rook -> type = "R";
                        case Bishop bishop -> type = "B";
                        case Knight knight -> type = "N";
                        case Pawn pawn -> type = "P";
                        default -> {
                        }
                    }

                    // Example: White Knight becomes "wN", Black King becomes "bK"
                    grid[i][j] = color + type;
                }
            }
        }
        return grid;
    }

    // NEW: Endpoint for the frontend to trigger a timeout check
    @GetMapping("/timeout")
    public String triggerTimeout() {
        String status = game.checkTimeout();

        if (status.contains("TIME_OUT")) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "MOVE");
            payload.put("status", status);

            // THE FIX: Use getBoard() instead of game.getBoard()
            payload.put("grid", getBoardState());

            payload.put("pieceCode", "");
            payload.put("startX", 0); payload.put("startY", 0);
            payload.put("endX", 0); payload.put("endY", 0);
            payload.put("whiteTime", game.getWhiteTimeRemaining()); // Make sure to send the 0 time!
            payload.put("blackTime", game.getBlackTimeRemaining());

            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return status;
    }
}
