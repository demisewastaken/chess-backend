package com.chess.engine;

public class Queen extends Piece {

    public Queen(Color color) {
        super(color);
    }

    @Override
    public boolean canMove(Board board, Spot start, Spot end) {
        // Cannot land on a spot occupied by your own piece
        if (end.getPiece() != null && end.getPiece().getColor() == this.getColor()) {
            return false;
        }

        int xDiff = Math.abs(start.getX() - end.getX());
        int yDiff = Math.abs(start.getY() - end.getY());

        // Check if moving diagonally (Bishop logic) OR in straight lines (Rook logic)
        if (xDiff == yDiff || start.getX() == end.getX() || start.getY() == end.getY()) {
            // If the direction is valid, make sure the path is not blocked
            return isPathClear(board, start, end);
        }

        return false;
    }
}
