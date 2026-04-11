package com.chess.engine;

public class Rook extends Piece {
    public Rook(Color color) { super(color); }

    @Override
    public boolean canMove(Board board, Spot start, Spot end) {
        if (end.getPiece() != null && end.getPiece().getColor() == this.getColor()) {
            return false;
        }
        // Check if it's moving in a straight line
        if ((start.getX() == end.getX()) || (start.getY() == end.getY())) {
            // NEW: Check if the path is clear
            return isPathClear(board, start, end);
        }
        return false;
    }
}

