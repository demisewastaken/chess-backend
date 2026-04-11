package com.chess.engine;

public class Pawn extends Piece {

    public Pawn(Color color) {
        super(color);
    }

    @Override
    public boolean canMove(Board board, Spot start, Spot end) {
        // Cannot capture your own pieces
        if (end.getPiece() != null && end.getPiece().getColor() == this.getColor()) {
            return false;
        }

        int startX = start.getX();
        int startY = start.getY();
        int endX = end.getX();
        int endY = end.getY();

        // THE FIX: Define strict forward direction (+1 for White moving up to 7, -1 for Black moving down to 0)
        int direction = (this.getColor() == Color.WHITE) ? 1 : -1;

        int dx = endX - startX; // We do NOT use Math.abs() here anymore!
        int dy = Math.abs(endY - startY); // Left/Right movement still uses absolute value

        // 1. Moving straight forward (1 square)
        if (dx == direction && dy == 0 && end.getPiece() == null) {
            return true;
        }

        // 2. Initial 2-square jump
        if (dx == 2 * direction && dy == 0 && end.getPiece() == null && !this.isHasMoved()) {
            // Must check if the square in between is also empty so they can't jump over pieces!
            if (board.getBox(startX + direction, startY).getPiece() == null) {
                return true;
            }
        }

        // 3. Diagonal Captures (Normal and En Passant)
        if (dx == direction && dy == 1) {
            // Normal Capture
            if (end.getPiece() != null && end.getPiece().getColor() != this.getColor()) {
                return true;
            }
            // En Passant Capture
            if (end == board.getEnPassantTarget()) {
                return true;
            }
        }

        return false;
    }
}