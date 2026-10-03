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

    @Test
    void extraPassesKeepRoundingTheSurface() {
        // A 3x3x3 wall at x <= 2 with a three-cell ledge sticking out at (3..5, 1, 1).
        final Solid wall = new Solid();
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = 0; z <= 2; z++) wall.set(x, y, z, true);
            }
        }
        wall.set(3, 1, 1, true);
        wall.set(4, 1, 1, true);
        wall.set(5, 1, 1, true);
        final CellVolume area = new CellVolume(GRID, 1000, 100_000);
        ShapeRasterizer.brush(2, 1, 1, 6, ShapeRasterizer.BrushShape.CUBE, area);

        final Map<BlockPos, BlockCellEdit> one = BrushSmoother.smooth(area, wall, 1);
        assertEquals(BlockCellEdit.Operation.CARVE, operationAt(one, 4, 1, 1));
        assertEquals(BlockCellEdit.Operation.CARVE, operationAt(one, 5, 1, 1));
        assertEquals(null, operationAt(one, 3, 1, 1),
            "the ledge cell touching the wall is only sparse after the outer cells are gone");

        final Map<BlockPos, BlockCellEdit> three = BrushSmoother.smooth(area, wall, 3);
        assertEquals(BlockCellEdit.Operation.CARVE, operationAt(three, 3, 1, 1),
            "the next pass rounds the cell the first pass left behind");
        assertTrue(changedCells(three) > changedCells(one),
            "extra passes keep rounding instead of repeating the first pass: "
                + changedCells(one) + " then " + changedCells(three));
    }

    @Test
    void onePassMatchesTheSinglePassOverload() {
        final Floor floor = new Floor();
        floor.set(2, 0, 2, true);
        floor.set(-3, -1, -3, false);
        final CellVolume area = new CellVolume(GRID, 1000, 100_000);
        ShapeRasterizer.brush(0, 0, 0, 4, ShapeRasterizer.BrushShape.CUBE, area);

        final Map<BlockPos, BlockCellEdit> overload = BrushSmoother.smooth(area, floor);
        final Map<BlockPos, BlockCellEdit> explicit = BrushSmoother.smooth(area, floor, 1);

        assertEquals(changedCells(overload), changedCells(explicit));
        assertEquals(changedCells(overload), changedCells(BrushSmoother.smooth(area, floor, 0)),
            "a non-positive pass count behaves like one pass");
    }

    private static int changedCells(final Map<BlockPos, BlockCellEdit> edits) {
        return edits.values().stream().mapToInt(BlockCellEdit::cellCount).sum();
    }

    /** Occupancy given cell by cell, with stone as the material of every cell. */
    private static final class Solid implements BrushSmoother.CellLookup {
        private final Map<String, Boolean> cells = new HashMap<>();

        void set(final long x, final long y, final long z, final boolean occupied) {
            cells.put(x + "," + y + "," + z, occupied);
        }

        @Override
        public boolean occupied(final long x, final long y, final long z) {
            return cells.getOrDefault(x + "," + y + "," + z, false);
        }

        @Override
        public BlockData material(final long x, final long y, final long z) {
            return occupied(x, y, z) ? STONE : null;
        }
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
