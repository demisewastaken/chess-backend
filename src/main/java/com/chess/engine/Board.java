package com.chess.engine;

public class Board {
    private Spot enPassantTarget = null;
    Spot[][] boxes;

    public Board() {
        this.resetBoard();
    }

    public Spot getBox(int x, int y) {
        if (x < 0 || x > 7 || y < 0 || y > 7) {
            throw new IndexOutOfBoundsException("Index out of bound");
        }
        return boxes[x][y];
    }

    public Spot getEnPassantTarget(){
        return enPassantTarget;
    }

    public void setEnPassantTarget(Spot spot) {
        this.enPassantTarget = spot;
    }

    public void resetBoard() {
        boxes = new Spot[8][8];

        // 1. Initialize White Major Pieces (Row 0)
        boxes[0][0] = new Spot(0, 0, new Rook(Color.WHITE));
        boxes[0][1] = new Spot(0, 1, new Knight(Color.WHITE));
        boxes[0][2] = new Spot(0, 2, new Bishop(Color.WHITE));
        boxes[0][3] = new Spot(0, 3, new Queen(Color.WHITE));
        boxes[0][4] = new Spot(0, 4, new King(Color.WHITE));
        boxes[0][5] = new Spot(0, 5, new Bishop(Color.WHITE));
        boxes[0][6] = new Spot(0, 6, new Knight(Color.WHITE));
        boxes[0][7] = new Spot(0, 7, new Rook(Color.WHITE));

        // 2. Initialize White Pawns (Row 1)
        for (int j = 0; j < 8; j++) {
            boxes[1][j] = new Spot(1, j, new Pawn(Color.WHITE));
        }

        // 3. Initialize Black Major Pieces (Row 7)
        boxes[7][0] = new Spot(7, 0, new Rook(Color.BLACK));
        boxes[7][1] = new Spot(7, 1, new Knight(Color.BLACK));
        boxes[7][2] = new Spot(7, 2, new Bishop(Color.BLACK));
        boxes[7][3] = new Spot(7, 3, new Queen(Color.BLACK));
        boxes[7][4] = new Spot(7, 4, new King(Color.BLACK));
        boxes[7][5] = new Spot(7, 5, new Bishop(Color.BLACK));
        boxes[7][6] = new Spot(7, 6, new Knight(Color.BLACK));
        boxes[7][7] = new Spot(7, 7, new Rook(Color.BLACK));

        // 4. Initialize Black Pawns (Row 6)
        for (int j = 0; j < 8; j++) {
            boxes[6][j] = new Spot(6, j, new Pawn(Color.BLACK));
        }

        // 5. Initialize remaining empty spots (Rows 2 to 5)
        for (int i = 2; i < 6; i++) {
            for (int j = 0; j < 8; j++) {
                boxes[i][j] = new Spot(i, j, null); // Empty spot
            }
        }
    }

    public void printBoard() {
        System.out.println();
        // Loop backwards from 7 down to 0 so Black is at the top
        for (int i = 7; i >= 0; i--) {
            System.out.print((i) + " "); // Print row numbers on the left

            for (int j = 0; j < 8; j++) {
                Spot spot = boxes[i][j];
                Piece p = spot.getPiece();

                if (p == null) {
                    System.out.print("[  ] ");
                } else {
                    String color = p.getColor() == Color.WHITE ? "w" : "b";
                    String type = "";

                    // Determine the piece type
                    if (p instanceof King) type = "K";
                    else if (p instanceof Queen) type = "Q";
                    else if (p instanceof Rook) type = "R";
                    else if (p instanceof Bishop) type = "B";
                    else if (p instanceof Knight) type = "N"; // N for Knight to avoid confusing with King
                    else if (p instanceof Pawn) type = "P";

                    System.out.print("[" + color + type + "] ");
                }
            }
            System.out.println(); // Move to the next row
        }
        // Print column numbers at the bottom
        System.out.println("    0    1    2    3    4    5    6    7\n");
    }
}

