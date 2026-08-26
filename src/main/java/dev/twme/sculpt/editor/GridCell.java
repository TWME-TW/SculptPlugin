package dev.twme.sculpt.editor;

/** A cell coordinate in the player's active editing grid. */
record GridCell(int x, int y, int z) {

    static final int OCTREE_GRID_SIZE = 16;

    static GridCell adjacentTo(final VirtualGridHit hit) {
        return new GridCell(
            hit.pgx() + hit.face().dx,
            hit.pgy() + hit.face().dy,
            hit.pgz() + hit.face().dz);
    }

    boolean isInside(final int gridSize) {
        return x >= 0 && x < gridSize
            && y >= 0 && y < gridSize
            && z >= 0 && z < gridSize;
    }

    GridCell wrapped(final int gridSize) {
        return new GridCell(
            Math.floorMod(x, gridSize),
            Math.floorMod(y, gridSize),
            Math.floorMod(z, gridSize));
    }

    int centerX(final int gridSize) {
        return center(x, gridSize);
    }

    int centerY(final int gridSize) {
        return center(y, gridSize);
    }

    int centerZ(final int gridSize) {
        return center(z, gridSize);
    }

    private static int center(final int coordinate, final int gridSize) {
        final int side = OCTREE_GRID_SIZE / gridSize;
        return coordinate * side + side / 2;
    }
}
