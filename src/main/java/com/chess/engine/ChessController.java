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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;


@RestController // Tells Spring Boot: "This class is a Waiter that listens to the internet"
@CrossOrigin    // A security bypass that allows our future HTML website to talk to this server
public class ChessController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private Game game = new Game();

    // NEW: The matchmaking bucket
    private List<String> unassignedColors;

    public ChessController() {
        // When the server starts, fill the bucket and shuffle it!
        unassignedColors = new ArrayList<>(Arrays.asList("WHITE", "BLACK"));
        Collections.shuffle(unassignedColors);
    }

    // NEW: Endpoint for players to claim an identity when they open the link
    @GetMapping("/join")
    public String joinGame() {
        if (unassignedColors.isEmpty()) {
            return "SPECTATOR"; // If 2 people are already playing, anyone else just watches
        }
        return unassignedColors.remove(0); // Hand them a random color
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
            // Blast it to the "/topic/game" radio channel
            messagingTemplate.convertAndSend("/topic/game", (Object) payload);
        }

        return status;
    }

    // A handy link to restart the game: http://localhost:8080/reset
    @GetMapping("/reset")
    public String resetGame() {
        this.game = new Game();

        // Refill and shuffle the bucket for the next game!
        unassignedColors = new ArrayList<>(Arrays.asList("WHITE", "BLACK"));
        Collections.shuffle(unassignedColors);

        // Blast the reset signal to all players
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "RESET");
        messagingTemplate.convertAndSend("/topic/game", (Object) payload);

        return "SUCCESS: New game started!";
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
