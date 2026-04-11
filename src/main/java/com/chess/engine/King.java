package com.chess.engine;

public class King extends Piece {

    public King(Color color) {
        super(color);
    }

    @Override
    public boolean canMove(Board board, Spot start, Spot end) {
        if (end.getPiece() != null && end.getPiece().getColor() == this.getColor()) {
            return false;
        }

        int x = Math.abs(start.getX() - end.getX());
        int y = Math.abs(start.getY() - end.getY());

        // Standard 1-square move
        if (x + y == 1 || (x == 1 && y == 1)) {
            return true;
        }

        // NEW: Castling Logic (Moving 2 squares horizontally)
        if (x == 0 && y == 2 && !this.isHasMoved()) {
            // Determine if we are castling Kingside (right) or Queenside (left)
            int direction = (end.getY() > start.getY()) ? 1 : -1;

            // Where should the Rook be?
            int rookY = (direction == 1) ? 7 : 0;
            Piece rook = board.getBox(start.getX(), rookY).getPiece();

            // Check if the Rook exists, is actually a Rook, and hasn't moved
            if (rook != null && rook instanceof Rook && !rook.isHasMoved()) {
                // Check if the path between King and Rook is clear
                return isPathClear(board, start, board.getBox(start.getX(), rookY));
            }
        }

        return false;
    }
}
