package com.chess.engine;

public abstract class Piece {
    private Color color;
    private boolean killed = false;
    private boolean hasMoved = false; // NEW FLAG

    public Piece(Color color) { this.color = color; }

    public Color getColor() { return this.color; }
    public boolean isKilled() { return this.killed; }
    public void setKilled(boolean killed) { this.killed = killed; }

    // NEW GETTER AND SETTER
    public boolean isHasMoved() { return this.hasMoved; }
    public void setHasMoved(boolean hasMoved) { this.hasMoved = hasMoved; }

    public abstract boolean canMove(Board board, Spot start, Spot end);
    // NEW: Helper method to check for obstacles
    protected boolean isPathClear(Board board, Spot start, Spot end) {
        // Find the direction of movement (-1, 0, or 1)
        int dx = Integer.signum(end.getX() - start.getX());
        int dy = Integer.signum(end.getY() - start.getY());

        int currX = start.getX() + dx;
        int currY = start.getY() + dy;

        // Loop through all squares between start and end
        while (currX != end.getX() || currY != end.getY()) {
            if (board.getBox(currX, currY).getPiece() != null) {
                return false; // There is a piece in the way!
            }
            currX += dx;
            currY += dy;
        }
        return true; // Path is clear
    }
}

