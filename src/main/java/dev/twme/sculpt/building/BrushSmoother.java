package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.block.data.BlockData;

/**
 * Majority-filter smoothing for the sculpting brush. Occupied cells with few
 * occupied neighbors (spikes and sharp corners) are carved, and empty cells
 * surrounded by material (pits and notches) are filled with the most common
 * neighboring material.
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

    /**
     * Compute smoothing edits for the cells of {@code area}. All decisions use
     * the original occupancy, so the result does not depend on visit order.
     */
    public static Map<BlockPos, BlockCellEdit> smooth(
            final CellVolume area,
            final CellLookup lookup) {
        final int grid = area.grid();
        final Map<BlockPos, BitSet> carve = new HashMap<>();
        final Map<BlockPos, Map<BlockData, BitSet>> fill = new HashMap<>();

        for (final Map.Entry<BlockPos, BitSet> entry : area.blocks().entrySet()) {
            final BlockPos block = entry.getKey();
            final BitSet cells = entry.getValue();
            for (int index = cells.nextSetBit(0); index >= 0;
                    index = cells.nextSetBit(index + 1)) {
                final long x = (long) block.x() * grid + CellVolume.localX(grid, index);
                final long y = (long) block.y() * grid + CellVolume.localY(grid, index);
                final long z = (long) block.z() * grid + CellVolume.localZ(grid, index);
                final boolean occupied = lookup.occupied(x, y, z);
                final Map<BlockData, Integer> materials = new HashMap<>();
                int neighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            if (!lookup.occupied(x + dx, y + dy, z + dz)) continue;
                            neighbors++;
                            final BlockData material = lookup.material(x + dx, y + dy, z + dz);
                            if (material != null) materials.merge(material, 1, Integer::sum);
                        }
                    }
                }
                if (occupied && neighbors <= CARVE_AT_MOST
                        && lookup.material(x, y, z) != null) {
                    carve.computeIfAbsent(block, ignored -> new BitSet()).set(index);
                } else if (!occupied && neighbors >= FILL_AT_LEAST && !materials.isEmpty()) {
                    final BlockData material = mostCommon(materials);
                    fill.computeIfAbsent(block, ignored -> new HashMap<>())
                        .computeIfAbsent(material, ignored -> new BitSet()).set(index);
                }
            }
        }

        final Map<BlockPos, BlockCellEdit> edits = new HashMap<>();
        final Set<BlockPos> positions = new HashSet<>(carve.keySet());
        positions.addAll(fill.keySet());
        for (final BlockPos position : positions) {
            final List<BlockCellEdit.Layer> layers = new ArrayList<>();
            final BitSet carved = carve.get(position);
            if (carved != null) {
                layers.add(new BlockCellEdit.Layer(
                    BlockCellEdit.Operation.CARVE, null, carved));
            }
            final Map<BlockData, BitSet> filled = fill.get(position);
            if (filled != null) {
                filled.forEach((material, bits) -> layers.add(new BlockCellEdit.Layer(
                    BlockCellEdit.Operation.ADD, material, bits)));
            }
            edits.put(position, new BlockCellEdit(grid, layers));
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
}
