package com.chess.engine;

public class Spot {
    private int x;
    private int y;
    private Piece piece;

    public Spot(int x, int y, Piece piece) {
        this.setX(x);
        this.setY(y);
        this.setPiece(piece);
    }

    // Getters and Setters
    public Piece getPiece() { return this.piece; }
    public void setPiece(Piece p) { this.piece = p; }
    public int getX() { return this.x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return this.y; }
    public void setY(int y) { this.y = y; }
}
