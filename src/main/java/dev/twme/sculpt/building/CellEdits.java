package dev.twme.sculpt.building;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import dev.twme.sculpt.core.CellMaterial;

/** Builds per-block edits from rasterized cells. */
public final class CellEdits {

    private CellEdits() {
    }

    /** One operation applied to every cell of {@code volume}. */
    public static Map<BlockPos, BlockCellEdit> of(
            final CellVolume volume,
            final BlockCellEdit.Operation operation,
            final CellMaterial material) {
        final Map<BlockPos, BlockCellEdit> edits = new HashMap<>();
        volume.blocks().forEach((position, cells) -> edits.put(position,
            BlockCellEdit.single(volume.grid(), operation, material, (BitSet) cells.clone())));
        return edits;
    }
}
