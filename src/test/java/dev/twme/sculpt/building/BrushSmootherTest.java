package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class BrushSmootherTest {

    private static final BlockData STONE = TestBlockData.of(Material.STONE);
    private static final int GRID = 8;

    /** A flat floor occupying every cell with y below zero. */
    private static final class Floor implements BrushSmoother.CellLookup {
        private final Map<String, Boolean> overrides = new HashMap<>();

        void set(final long x, final long y, final long z, final boolean occupied) {
            overrides.put(x + "," + y + "," + z, occupied);
        }

        @Override
        public boolean occupied(final long x, final long y, final long z) {
            return overrides.getOrDefault(x + "," + y + "," + z, y < 0);
        }

        @Override
        public BlockData material(final long x, final long y, final long z) {
            return occupied(x, y, z) ? STONE : null;
        }
    }

    @Test
    void spikesAreCarvedAndPitsAreFilled() {
        final Floor floor = new Floor();
        floor.set(2, 0, 2, true);    // spike on top of the floor
        floor.set(-3, -1, -3, false); // pit in the floor

        final CellVolume area = new CellVolume(GRID, 1000, 100_000);
        ShapeRasterizer.brush(0, 0, 0, 4, ShapeRasterizer.BrushShape.CUBE, area);
        final Map<BlockPos, BlockCellEdit> edits = BrushSmoother.smooth(area, floor);

        assertEquals(BlockCellEdit.Operation.CARVE, operationAt(edits, 2, 0, 2));
        assertEquals(BlockCellEdit.Operation.ADD, operationAt(edits, -3, -1, -3));
        assertEquals(2, edits.values().stream().mapToInt(BlockCellEdit::cellCount).sum(),
            "a flat floor must otherwise stay unchanged");
    }

    @Test
    void emptyAreasProduceNoEdits() {
        final CellVolume area = new CellVolume(GRID, 1000, 100_000);
        ShapeRasterizer.brush(0, 40, 0, 3, ShapeRasterizer.BrushShape.SPHERE, area);
        assertTrue(BrushSmoother.smooth(area, new Floor()).isEmpty());
    }

    private static BlockCellEdit.Operation operationAt(
            final Map<BlockPos, BlockCellEdit> edits,
            final long x,
            final long y,
            final long z) {
        final BlockPos block = new BlockPos(
            (int) Math.floorDiv(x, GRID), (int) Math.floorDiv(y, GRID),
            (int) Math.floorDiv(z, GRID));
        final int index = CellVolume.localIndex(GRID,
            (int) Math.floorMod(x, GRID), (int) Math.floorMod(y, GRID),
            (int) Math.floorMod(z, GRID));
        final BlockCellEdit edit = edits.get(block);
        if (edit == null) return null;
        for (final BlockCellEdit.Layer layer : edit.layers()) {
            final BitSet cells = layer.cells();
            if (cells.get(index)) return layer.operation();
        }
        return null;
    }
}
