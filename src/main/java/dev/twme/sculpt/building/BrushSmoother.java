package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.block.data.BlockData;

/**
 * Majority-filter smoothing for the sculpting brush. Occupied cells with few
 * occupied neighbors (spikes and sharp corners) are carved, and empty cells
 * surrounded by material (pits and notches) are filled with the most common
 * neighboring material.
 *
 * <p>Smoothing can run more than one pass. Every decision of one pass uses the
 * occupancy that pass started with, so a pass does not depend on visit order;
 * the next pass sees the cells its predecessor carved and filled, which keeps
 * rounding the surface instead of stopping after a single cell of change.</p>
 */
public final class BrushSmoother {

    /** Occupied cells with at most this many of 26 neighbors are carved. */
    static final int CARVE_AT_MOST = 9;
    /** Empty cells with at least this many of 26 neighbors are filled. */
    static final int FILL_AT_LEAST = 17;

    /** Occupancy of cells read from the world before smoothing. */
    public interface CellLookup {
        /** Whether the global cell is occupied by anything solid. */
        boolean occupied(long x, long y, long z);

        /** Editable material of an occupied cell, or {@code null} when unknown. */
        BlockData material(long x, long y, long z);
    }

    private BrushSmoother() {}

    /** Compute smoothing edits for the cells of {@code area} with one pass. */
    public static Map<BlockPos, BlockCellEdit> smooth(
            final CellVolume area,
            final CellLookup lookup) {
        return smooth(area, lookup, 1);
    }

    /**
     * Compute the net smoothing edits for the cells of {@code area} after
     * {@code passes} passes.
     *
     * <p>The result holds only the cells whose occupancy differs from the
     * world, and the layers of a block apply the net change at once: a cell
     * carved by one pass and filled by a later one is reported as a single
     * fill, and a cell that returns to its starting occupancy is left out
     * entirely.</p>
     *
     * @param passes number of passes, clamped to at least one
     */
    public static Map<BlockPos, BlockCellEdit> smooth(
            final CellVolume area,
            final CellLookup lookup,
            final int passes) {
        final int grid = area.grid();
        final Map<CellKey, CellState> cells = new HashMap<>();
        for (final Map.Entry<BlockPos, BitSet> entry : area.blocks().entrySet()) {
            for (int index = entry.getValue().nextSetBit(0); index >= 0;
                    index = entry.getValue().nextSetBit(index + 1)) {
                final CellKey cell = CellKey.of(grid, entry.getKey(), index);
                final boolean occupied = lookup.occupied(cell.x(), cell.y(), cell.z());
                cells.put(cell, new CellState(occupied, occupied,
                    occupied ? lookup.material(cell.x(), cell.y(), cell.z()) : null));
            }
        }
        if (cells.isEmpty()) return Map.of();

        for (int pass = 0; pass < Math.max(1, passes); pass++) {
            final Map<CellKey, CellState> next = new HashMap<>(cells);
            for (final Map.Entry<CellKey, CellState> entry : cells.entrySet()) {
                final CellKey cell = entry.getKey();
                final boolean occupied = entry.getValue().occupied();
                final Map<BlockData, Integer> materials = new HashMap<>();
                int neighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            final CellKey neighbor = new CellKey(
                                cell.x() + dx, cell.y() + dy, cell.z() + dz);
                            final CellState state = cells.get(neighbor);
                            final boolean solid = state != null
                                ? state.occupied()
                                : lookup.occupied(neighbor.x(), neighbor.y(), neighbor.z());
                            if (!solid) continue;
                            neighbors++;
                            final BlockData material = state != null
                                ? state.material()
                                : lookup.material(neighbor.x(), neighbor.y(), neighbor.z());
                            if (material != null) materials.merge(material, 1, Integer::sum);
                        }
                    }
                }
                if (occupied && neighbors <= CARVE_AT_MOST
                        && entry.getValue().material() != null) {
                    next.put(cell, new CellState(false, entry.getValue().worldOccupied(), null));
                } else if (!occupied && neighbors >= FILL_AT_LEAST && !materials.isEmpty()) {
                    next.put(cell, new CellState(true, entry.getValue().worldOccupied(),
                        mostCommon(materials)));
                }
            }
            cells.clear();
            cells.putAll(next);
        }

        final Map<BlockPos, Map<Integer, CellState>> changed = new HashMap<>();
        for (final Map.Entry<CellKey, CellState> entry : cells.entrySet()) {
            if (entry.getValue().occupied() == entry.getValue().worldOccupied()) continue;
            final CellKey cell = entry.getKey();
            changed.computeIfAbsent(
                    new BlockPos(
                        (int) Math.floorDiv(cell.x(), grid),
                        (int) Math.floorDiv(cell.y(), grid),
                        (int) Math.floorDiv(cell.z(), grid)),
                    ignored -> new HashMap<>())
                .put(CellVolume.localIndex(grid,
                    (int) Math.floorMod(cell.x(), grid),
                    (int) Math.floorMod(cell.y(), grid),
                    (int) Math.floorMod(cell.z(), grid)), entry.getValue());
        }

        final Map<BlockPos, BlockCellEdit> edits = new HashMap<>();
        for (final Map.Entry<BlockPos, Map<Integer, CellState>> entry : changed.entrySet()) {
            final BitSet carved = new BitSet();
            final Map<BlockData, BitSet> filled = new HashMap<>();
            for (final Map.Entry<Integer, CellState> cell : entry.getValue().entrySet()) {
                if (cell.getValue().occupied()) {
                    filled.computeIfAbsent(cell.getValue().material(), ignored -> new BitSet())
                        .set(cell.getKey());
                } else {
                    carved.set(cell.getKey());
                }
            }
            final List<BlockCellEdit.Layer> layers = new ArrayList<>();
            if (!carved.isEmpty()) {
                layers.add(new BlockCellEdit.Layer(BlockCellEdit.Operation.CARVE, null, carved));
            }
            filled.forEach((material, bits) -> layers.add(new BlockCellEdit.Layer(
                BlockCellEdit.Operation.ADD, material, bits)));
            edits.put(entry.getKey(), new BlockCellEdit(grid, layers));
        }
        return edits;
    }

    private static BlockData mostCommon(final Map<BlockData, Integer> counts) {
        BlockData best = null;
        int bestCount = -1;
        for (final Map.Entry<BlockData, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /**
     * One cell of the working volume: its current occupancy, the occupancy the
     * world had before smoothing, and its material when it is occupied.
     */
    private record CellState(boolean occupied, boolean worldOccupied, BlockData material) {}

    /** A cell in global coordinates. */
    private record CellKey(long x, long y, long z) {

        static CellKey of(final int grid, final BlockPos block, final int index) {
            return new CellKey(
                (long) block.x() * grid + CellVolume.localX(grid, index),
                (long) block.y() * grid + CellVolume.localY(grid, index),
                (long) block.z() * grid + CellVolume.localZ(grid, index));
        }
    }
}
