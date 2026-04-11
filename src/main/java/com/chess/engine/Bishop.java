package com.chess.engine;

public class Bishop extends Piece {

        public Bishop(Color color) {
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

            // Check if the move is a perfect diagonal
            if (xDiff == yDiff) {
                // If it is diagonal, make sure no pieces are in the way
                return isPathClear(board, start, end);
            }

            // If it's not a diagonal move, it's illegal
            return false;
        }
}

