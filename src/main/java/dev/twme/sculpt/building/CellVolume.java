package dev.twme.sculpt.building;

import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Set of world cells at one editing resolution, grouped by owning block.
 *
 * <p>Global cell coordinates are {@code block * grid + local}. Each block
 * keeps a {@code grid³} bit set indexed by {@link #localIndex}.
 */
public final class CellVolume {

    /** Raised when a shape would touch more cells or blocks than allowed. */
    public static final class LimitExceededException extends RuntimeException {
        private final boolean blockLimit;
        private final long limit;

        LimitExceededException(final boolean blockLimit, final long limit) {
            super((blockLimit ? "block" : "cell") + " limit exceeded: " + limit);
            this.blockLimit = blockLimit;
            this.limit = limit;
        }

        public boolean blockLimit() {
            return blockLimit;
        }

        public long limit() {
            return limit;
        }
    }

    private final int grid;
    private final int maxBlocks;
    private final long maxCells;
    private final Map<BlockPos, BitSet> cells = new HashMap<>();
    private long cellCount;

    public CellVolume(final int grid, final int maxBlocks, final long maxCells) {
        if (!isValidGrid(grid)) {
            throw new IllegalArgumentException("grid must be 1, 2, 4, 8, or 16");
        }
        this.grid = grid;
        this.maxBlocks = Math.max(1, maxBlocks);
        this.maxCells = Math.max(1L, maxCells);
    }

    public static boolean isValidGrid(final int grid) {
        return grid == 1 || grid == 2 || grid == 4 || grid == 8 || grid == 16;
    }

    public int grid() {
        return grid;
    }

    /** Add the global cell; returns whether it was newly added. */
    public boolean add(final long cellX, final long cellY, final long cellZ) {
        final BlockPos block = new BlockPos(
            (int) Math.floorDiv(cellX, grid),
            (int) Math.floorDiv(cellY, grid),
            (int) Math.floorDiv(cellZ, grid));
        BitSet bits = cells.get(block);
        if (bits == null) {
            if (cells.size() >= maxBlocks) {
                throw new LimitExceededException(true, maxBlocks);
            }
            bits = new BitSet(grid * grid * grid);
            cells.put(block, bits);
        }
        final int index = localIndex(grid,
            (int) Math.floorMod(cellX, grid),
            (int) Math.floorMod(cellY, grid),
            (int) Math.floorMod(cellZ, grid));
        if (bits.get(index)) return false;
        if (cellCount >= maxCells) {
            throw new LimitExceededException(false, maxCells);
        }
        bits.set(index);
        cellCount++;
        return true;
    }

    public boolean contains(final long cellX, final long cellY, final long cellZ) {
        final BitSet bits = cells.get(new BlockPos(
            (int) Math.floorDiv(cellX, grid),
            (int) Math.floorDiv(cellY, grid),
            (int) Math.floorDiv(cellZ, grid)));
        return bits != null && bits.get(localIndex(grid,
            (int) Math.floorMod(cellX, grid),
            (int) Math.floorMod(cellY, grid),
            (int) Math.floorMod(cellZ, grid)));
    }

    public long cellCount() {
        return cellCount;
    }

    public int blockCount() {
        return cells.size();
    }

    public boolean isEmpty() {
        return cellCount == 0;
    }

    /** Read-only view; bit sets must not be modified by callers. */
    public Map<BlockPos, BitSet> blocks() {
        return Collections.unmodifiableMap(cells);
    }

    public static int localIndex(final int grid, final int x, final int y, final int z) {
        return (y * grid + z) * grid + x;
    }

    public static int localX(final int grid, final int index) {
        return index % grid;
    }

    public static int localY(final int grid, final int index) {
        return index / (grid * grid);
    }

    public static int localZ(final int grid, final int index) {
        return (index / grid) % grid;
    }
}
