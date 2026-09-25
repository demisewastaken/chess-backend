package com.chess.engine;

public class King extends Piece {

    public King(Color color) {
        super(color);
    }

    @Override
    public boolean canMove(Board board, Spot start, Spot end) {
        // Cannot land on a spot occupied by your own piece
        if (end.getPiece() != null && end.getPiece().getColor() == this.getColor()) {
            return false;
        }

        int x = Math.abs(start.getX() - end.getX());
        int y = Math.abs(start.getY() - end.getY());

        // Standard 1-square move in any direction
        if (x + y == 1 || (x == 1 && y == 1)) {
            return true;
        }

        // Castling Logic (Moving exactly 2 squares horizontally, King hasn't moved)
        if (x == 0 && y == 2 && !this.isHasMoved()) {
            int direction = (end.getY() > start.getY()) ? 1 : -1;
            int rookY = (direction == 1) ? 7 : 0;
            Piece rook = board.getBox(start.getX(), rookY).getPiece();

            // Rook must exist, be a Rook, be the SAME color, and never have moved
            if (rook != null && rook instanceof Rook
                    && rook.getColor() == this.getColor()
                    && !rook.isHasMoved()) {
                // The path between King and Rook must be clear of all pieces
                return isPathClear(board, start, board.getBox(start.getX(), rookY));
            }
        }

        return false;
    }
}
