package com.chess.engine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Game {
    // NEW: The Server-Side Memory Bank
    private List<Map<String, Object>> moveHistory = new ArrayList<>();
    private Board board;
    private Color currentTurn;

    // --- NEW: SECRET TOKEN TRACKERS ---
    private String whiteToken = null;
    private String blackToken = null;

    // We now accept a token to check if the player is returning!
    public String assignPlayer(String returningToken) {

        // 1. Welcome back returning players!
        if (returningToken != null && !returningToken.isEmpty()) {
            if (returningToken.equals(whiteToken)) return whiteToken;
            if (returningToken.equals(blackToken)) return blackToken;
        }

        // 2. Assign empty seats to new players and generate a secret token
        if (whiteToken == null) {
            whiteToken = "WHITE-" + java.util.UUID.randomUUID().toString();
            return whiteToken;
        }
        if (blackToken == null) {
            blackToken = "BLACK-" + java.util.UUID.randomUUID().toString();
            return blackToken;
        }

        return "SPECTATOR";
    }

    // Allows the Controller to ask if the match is running
    public boolean isMatchStarted() {
        return matchStarted;
    }

    // --- NEW: LOBBY TRACKERS ---
    private boolean whiteReady = false;
    private boolean blackReady = false;
    private boolean matchStarted = false;

    // Call this when a player clicks "Ready"
    public boolean setPlayerReady(String color) {
        if ("WHITE".equals(color)) whiteReady = true;
        if ("BLACK".equals(color)) blackReady = true;

        if (whiteReady && blackReady) {
            matchStarted = true;
            // START THE CLOCKS NOW!
            lastMoveTimestamp = System.currentTimeMillis();
            return true; // Returns true if the match just officially started
        }
        return false;
    }

    // NEW: Action Handlers for Resign and Abort
    public String resign(String color) {
        return "RESIGNATION! " + ("WHITE".equals(color) ? "BLACK wins!" : "WHITE wins!");
    }

    public String abort() {
        return "MATCH ABORTED! Game cancelled.";
    }

    public List<Map<String, Object>> getMoveHistory() {
        return moveHistory;
    }

    public void addMoveToHistory(Map<String, Object> movePayload) {
        moveHistory.add(movePayload);
    }

    // --- DRAW TRACKERS ---
    // Counts moves. Resets to 0 if a pawn moves or a piece is captured. Hits 100 = Draw.
    private int halfMoveClock = 0;

    // Remembers board positions to check for Threefold Repetition
    private java.util.Map<String, Integer> positionHistory = new java.util.HashMap<>();

    // --- CLOCK TRACKERS ---
    private long whiteTimeRemaining = 10 * 60 * 1000; // 10 minutes in milliseconds
    private long blackTimeRemaining = 10 * 60 * 1000;
    private long lastMoveTimestamp = System.currentTimeMillis();


    // LIVE TIME CALCULATION
    public long getWhiteTimeRemaining() {
        // If the match is actively running, and it is White's turn, calculate the exact live time!
        if (matchStarted && currentTurn == Color.WHITE) {
            long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
            return whiteTimeRemaining - elapsed;
        }
        // Otherwise, return their frozen time
        return whiteTimeRemaining;
    }

    public long getBlackTimeRemaining() {
        // If the match is actively running, and it is Black's turn, calculate the exact live time!
        if (matchStarted && currentTurn == Color.BLACK) {
            long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
            return blackTimeRemaining - elapsed;
        }
        // Otherwise, return their frozen time
        return blackTimeRemaining;
    }

    public Game() {
        board = new Board();
        currentTurn = Color.WHITE; // White always goes first in chess
    }

    // ==========================================
    // --- MASTER RESET METHOD ---
    // ==========================================
    public void resetGame() {
        this.board = new Board();       // Get a fresh board
        this.currentTurn = Color.WHITE; // White goes first

        // 1. Reset Lobby & Match State
        this.whiteReady = false;
        this.blackReady = false;
        this.matchStarted = false;

        // 2. Reset Draw Trackers
        this.halfMoveClock = 0;
        this.positionHistory.clear();

        // 3. Reset Clocks
        this.whiteTimeRemaining = 10 * 60 * 1000;
        this.blackTimeRemaining = 10 * 60 * 1000;
        this.lastMoveTimestamp = System.currentTimeMillis();

        // 4. Wipe the Server Memory Bank!
        this.moveHistory.clear();
    }

    // ==========================================
    // --- NEW: LEAVE TABLE METHOD ---
    // ==========================================
    public void clearSeats() {
        this.whiteToken = null;
        this.blackToken = null;
    }

    public Board getBoard() {
        return board;
    }

    // The main engine method that processes a move

    public String playerMove(int startX, int startY, int endX, int endY, String promotion) {
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
        }

        // --- PHASE 2: THE "CHECK" SIMULATION ---
        Piece destPiece = endBox.getPiece();
        endBox.setPiece(pieceToMove);
        startBox.setPiece(null);

        if (isKingInCheck(currentTurn)) {
            //Undo the move
            startBox.setPiece(pieceToMove);
            endBox.setPiece(destPiece);
            return "ERROR: You cannot make a move that leaves your King in Check!";
        }

        // ==========================================
        // --- NEW: TIME MANAGEMENT ---
        // ==========================================
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastMoveTimestamp;

        if (currentTurn == Color.WHITE) {
            whiteTimeRemaining -= elapsed;
            if (whiteTimeRemaining <= 0) return "TIME_OUT! Black wins on time!";
        } else {
            blackTimeRemaining -= elapsed;
            if (blackTimeRemaining <= 0) return "TIME_OUT! White wins on time!";
        }

        // Reset the stopwatch for the next player's turn!
        lastMoveTimestamp = currentTime;

        // --- PHASE 3: PERMANENT EXECUTION ---

        // 50-MOVE RULE TRACKER
        if (pieceToMove instanceof Pawn || destPiece != null) {
            halfMoveClock = 0; // Reset the clock
            positionHistory.clear(); // Pawn moves/captures break repetition chains permanently
        } else {
            halfMoveClock++; // Tick the clock
        }

        if (destPiece != null) {
            destPiece.setKilled(true);
        }

        // Execute En Passant Capture
        if (pieceToMove instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
            Spot epVictimSpot = board.getBox(startX, endY);
            if (epVictimSpot.getPiece() != null) {
                epVictimSpot.getPiece().setKilled(true);
                epVictimSpot.setPiece(null);
            }
        }

        // Handle Pawn Promotion
        if (pieceToMove instanceof Pawn) {
            if ((currentTurn == Color.WHITE && endX == 7) || (currentTurn == Color.BLACK && endX == 0)) {
                if (promotion != null && !promotion.isEmpty()) {
                    Piece newPiece;
                    switch (promotion.toUpperCase()) {
                        case "R": newPiece = new Rook(currentTurn); break;
                        case "B": newPiece = new Bishop(currentTurn); break;
                        case "N": newPiece = new Knight(currentTurn); break;
                        case "Q":
                        default: newPiece = new Queen(currentTurn); break;
                    }
                    newPiece.setHasMoved(true);
                    endBox.setPiece(newPiece);
                }
            }
        }

        Color enemyColor = (currentTurn == Color.WHITE) ? Color.BLACK : Color.WHITE;
        String resultMessage = "";

        // THREEFOLD REPETITION TRACKER (Take a snapshot AFTER the move is made)
        String currentState = getBoardStateString();
        positionHistory.put(currentState, positionHistory.getOrDefault(currentState, 0) + 1);
        boolean isThreefold = positionHistory.get(currentState) >= 3;

        // --- WIN / DRAW CHECKS ---
        if (isKingInCheck(enemyColor)) {
            if (isCheckmate(enemyColor)) {
                resultMessage = "CHECKMATE! " + currentTurn + " wins!";
            } else {
                resultMessage = "CHECK! " + enemyColor + "'s King is under attack!";
            }
        } else {
            // Check all 4 Draw Conditions!
            if (isStalemate(enemyColor)) {
                resultMessage = "DRAW! Game ended in Stalemate.";
            } else if (isInsufficientMaterial()) {
                resultMessage = "DRAW! Insufficient Material.";
            } else if (halfMoveClock >= 100) {
                resultMessage = "DRAW! 50-Move Rule.";
            } else if (isThreefold) {
                resultMessage = "DRAW! Threefold Repetition.";
            } else {
                resultMessage = enemyColor + "'s Turn";
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
            rook.setHasMoved(true);
        }

        // Set the En Passant Target
        board.setEnPassantTarget(null);
        if (pieceToMove instanceof Pawn && Math.abs(startX - endX) == 2) {
            int targetX = (startX + endX) / 2;
            board.setEnPassantTarget(board.getBox(targetX, startY));
        }

        pieceToMove.setHasMoved(true);
        currentTurn = enemyColor;

        return resultMessage;
    }

    public boolean isKingInCheck(Color kingColor) {
        Spot kingSpot = null;

        // Step 1: Find the King on the board
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p != null && p instanceof King && p.getColor() == kingColor) {
                    kingSpot = board.getBox(i, j);
                    break;
                }
            }
        }

        // If for some reason the King is missing (should never happen), return false
        if (kingSpot == null) return false;

        // Step 2: Check if any enemy piece can attack that spot
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                // If there is an enemy piece on this square...
                if (p != null && p.getColor() != kingColor) {
                    // ...can it legally move to the King's spot?
                    if (p.canMove(board, board.getBox(i, j), kingSpot)) {
                        return true; // The King is in check!
                    }
                }
            }
        }

        return false; // The King is safe
    }

    public boolean isCheckmate(Color color) {
        // You cannot be checkmated if you aren't in check!
        if (!isKingInCheck(color)) {
            return false;
        }

        // Loop through every square to find all of YOUR pieces
        for (int startX = 0; startX < 8; startX++) {
            for (int startY = 0; startY < 8; startY++) {
                Spot startBox = board.getBox(startX, startY);
                Piece piece = startBox.getPiece();

                if (piece != null && piece.getColor() == color) {
                    // We found one of your pieces! Now, try moving it to EVERY square on the board
                    for (int endX = 0; endX < 8; endX++) {
                        for (int endY = 0; endY < 8; endY++) {
                            Spot endBox = board.getBox(endX, endY);

                            // If the piece can mathematically move there...
                            if (piece.canMove(board, startBox, endBox)) {

                                // SIMULATE THE MOVE
                                Piece destPiece = endBox.getPiece();
                                endBox.setPiece(piece);
                                startBox.setPiece(null);

                                // Check if this move saved the King
                                boolean stillInCheck = isKingInCheck(color);

                                // UNDO THE SIMULATION
                                startBox.setPiece(piece);
                                endBox.setPiece(destPiece);

                                // If the King is no longer in check, there is an escape! Not checkmate.
                                if (!stillInCheck) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        // If we tried every move and none saved the King... game over.
        return true;
    }
    // NEW: Allows the server to explicitly check for a timeout without needing a move
    public String checkTimeout() {
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastMoveTimestamp;

        if (currentTurn == Color.WHITE) {
            if (whiteTimeRemaining - elapsed <= 0) return "TIME_OUT! Black wins on time!";
        } else {
            if (blackTimeRemaining - elapsed <= 0) return "TIME_OUT! White wins on time!";
        }

        return "SAFE"; // Not timed out yet
    }
    // NEW: Returns a list of valid [x, y] coordinates for a given piece
    public List<int[]> getValidMoves(int startX, int startY) {
        List<int[]> validMoves = new ArrayList<>();
        Spot startBox = board.getBox(startX, startY);
        Piece piece = startBox.getPiece();

        // FIX: Removed the "currentTurn" check so the engine can simulate future turns!
        if (piece == null) {
            return validMoves;
        }

        // Test moving this piece to every single square on the board
        for (int endX = 0; endX < 8; endX++) {
            for (int endY = 0; endY < 8; endY++) {
                Spot endBox = board.getBox(endX, endY);

                // 1. Does the math allow it?
                if (piece.canMove(board, startBox, endBox)) {

                    // (Prevent illegal castling predictions)
                    if (piece instanceof King && Math.abs(startY - endY) == 2 && isKingInCheck(piece.getColor())) {
                        continue;
                    }

                    // 2. SIMULATION: Make the move temporarily
                    Piece destPiece = endBox.getPiece();

                    // Temporarily remove En Passant victim during simulation
                    Piece epVictim = null;
                    Spot epVictimSpot = null;
                    if (piece instanceof Pawn && startX != endX && startY != endY && destPiece == null) {
                        epVictimSpot = board.getBox(startX, endY);
                        epVictim = epVictimSpot.getPiece();
                        epVictimSpot.setPiece(null);
                    }

                    endBox.setPiece(piece);
                    startBox.setPiece(null);

                    // Does it leave the King safe? (Check for THIS piece's color)
                    boolean isSafe = !isKingInCheck(piece.getColor());

                    // UNDO SIMULATION
                    startBox.setPiece(piece);
                    endBox.setPiece(destPiece);
                    if (epVictimSpot != null) {
                        epVictimSpot.setPiece(epVictim); // Put the victim back
                    }

                    // If it is completely legal and safe, add it to the list!
                    if (isSafe) {
                        validMoves.add(new int[]{endX, endY});
                    }
                }
            }
        }
        return validMoves;
    }

    public boolean isStalemate(Color color) {
        // You cannot be in stalemate if you are in check!
        if (isKingInCheck(color)) {
            return false;
        }

        // Loop through the whole board to find this player's pieces
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board.getBox(r, c).getPiece();
                if (p != null && p.getColor() == color) {
                    // If even ONE piece has at least ONE valid move, it is NOT a stalemate
                    if (!getValidMoves(r, c).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        // If we checked every piece and no valid moves exist... it's a draw!
        return true;
    }

    // Converts the board into a simple string of letters (e.g., "wK-bR---...") to easily compare history
    private String getBoardStateString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                Piece p = board.getBox(i, j).getPiece();
                if (p == null) {
                    sb.append("-");
                } else {
                    sb.append(p.getColor() == Color.WHITE ? "w" : "b").append(p.getClass().getSimpleName().charAt(0));
                }
            }
        }
        sb.append(currentTurn); // Whose turn it is matters for repetition!
        return sb.toString();
    }

    // Checks if a checkmate is mathematically impossible
    private boolean isInsufficientMaterial() {
        int whiteMinors = 0, blackMinors = 0;
        int majorPieces = 0; // Queens, Rooks, Pawns

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

        // If there are Queens, Rooks, or Pawns, checkmate is still possible
        if (majorPieces > 0) return false;

        // King vs King (0 minor pieces) -> Draw
        if (whiteMinors == 0 && blackMinors == 0) return true;

        // King + 1 Minor Piece vs King -> Draw
        if ((whiteMinors == 1 && blackMinors == 0) || (blackMinors == 1 && whiteMinors == 0)) return true;

        return false;
    }
}
