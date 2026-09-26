package com.chess.engine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Game {
    // The Server-Side Memory Bank
    private List<Map<String, Object>> moveHistory = new ArrayList<>();
    private List<Map<String, Object>> chatHistory = new ArrayList<>();
    private Board board;
    private Color currentTurn;

    // SECRET TOKEN TRACKERS
    private String whiteToken = null;
    private String blackToken = null;

    public String assignPlayer(String returningToken) {
        // 1. Welcome back returning players
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

    // LOBBY TRACKERS
    private boolean whiteReady = false;
    private boolean blackReady = false;
    private boolean matchStarted = false;

    // Call this when a player clicks "Ready"
    public boolean setPlayerReady(String color) {
        if ("WHITE".equals(color)) whiteReady = true;
        if ("BLACK".equals(color)) blackReady = true;

        if (whiteReady && blackReady) {
            matchStarted = true;
            lastMoveTimestamp = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    // Marks the game as officially over and freezes the clocks
    private void endMatch() {
        matchStarted = false;
        // Freeze the remaining time so subsequent /sync calls return stable values
        whiteTimeRemaining = getWhiteTimeRemaining();
        blackTimeRemaining = getBlackTimeRemaining();
        lastMoveTimestamp = System.currentTimeMillis();
    }

    // Action Handlers for Resign and Abort
    public String resign(String color) {
        endMatch();
        return "RESIGNATION! " + ("WHITE".equals(color) ? "BLACK wins!" : "WHITE wins!");
    }

    public String abort() {
        endMatch();
        return "MATCH ABORTED! Game cancelled.";
    }

    public List<Map<String, Object>> getMoveHistory() {
        return moveHistory;
    }

    public void addMoveToHistory(Map<String, Object> movePayload) {
        moveHistory.add(movePayload);
    }

    public List<Map<String, Object>> getChatHistory() {
        return chatHistory;
    }

    public void addChatToHistory(Map<String, Object> chatPayload) {
        chatHistory.add(chatPayload);
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
        if (matchStarted && currentTurn == Color.WHITE) {
            long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
            return Math.max(0, whiteTimeRemaining - elapsed);
        }
        return Math.max(0, whiteTimeRemaining);
    }

    public long getBlackTimeRemaining() {
        if (matchStarted && currentTurn == Color.BLACK) {
            long elapsed = System.currentTimeMillis() - lastMoveTimestamp;
            return Math.max(0, blackTimeRemaining - elapsed);
        }
        return Math.max(0, blackTimeRemaining);
    }

    public Game() {
        board = new Board();
        currentTurn = Color.WHITE;
    }

    // Returns whose turn it currently is (used by controller for clock sync)
    public Color getCurrentTurn() {
        return currentTurn;
    }

    // ==========================================
    // MASTER RESET METHOD
    // ==========================================
    public void resetGame() {
        this.board = new Board();
        this.currentTurn = Color.WHITE;
        this.moveHistory.clear(); // Clear move history so fetchBoard() doesn't rebuild from stale data

        // Reset Lobby & Match State
        this.whiteReady = false;
        this.blackReady = false;
        this.matchStarted = false;

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

    // LEAVE TABLE METHOD
    public void clearSeats() {
        this.whiteToken = null;
        this.blackToken = null;
    }

    public Board getBoard() {
        return board;
    }

    // ==========================================
    // MAIN MOVE ENGINE
    // ==========================================
    public String playerMove(int startX, int startY, int endX, int endY, String promotion) {
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
                return "TIME_OUT! Black wins on time!";
            }
        } else {
            blackTimeRemaining -= elapsed;
            if (blackTimeRemaining <= 0) {
                blackTimeRemaining = 0;
                endMatch();
                return "TIME_OUT! White wins on time!";
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

        return resultMessage;
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
        if (!matchStarted) return "SAFE";

        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - lastMoveTimestamp;

        if (currentTurn == Color.WHITE) {
            if (whiteTimeRemaining - elapsed <= 0) {
                whiteTimeRemaining = 0;
                endMatch();
                return "TIME_OUT! Black wins on time!";
            }
        } else {
            if (blackTimeRemaining - elapsed <= 0) {
                blackTimeRemaining = 0;
                endMatch();
                return "TIME_OUT! White wins on time!";
            }
        }
        return "SAFE";
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
