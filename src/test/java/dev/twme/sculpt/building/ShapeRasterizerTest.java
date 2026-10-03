package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ShapeRasterizerTest {

    private record Cell(long x, long y, long z) {}

    @Test
    void horizontalTriangleFillsExactlyOneLayer() {
        final CellVolume volume = volume(4);
        ShapeRasterizer.polygon(List.of(
            cellCenter(4, 0, 2, 0), cellCenter(4, 7, 2, 0), cellCenter(4, 0, 2, 7)),
            1.0, volume);

        final Set<Cell> cells = cells(volume);
        assertTrue(cells.stream().allMatch(cell -> cell.y() == 2), "single layer");
        // Right triangle with 8-cell legs, conservatively rasterized.
        assertTrue(cells.size() >= 36 && cells.size() <= 64, "size " + cells.size());
        assertTrue(cells.contains(new Cell(0, 2, 0)));
        assertFalse(cells.contains(new Cell(7, 2, 7)));
    }

    @Test
    void quadPolygonCoversTheWholeRectangle() {
        final CellVolume volume = volume(2);
        ShapeRasterizer.polygon(List.of(
            cellCenter(2, 0, 0, 0), cellCenter(2, 5, 0, 0),
            cellCenter(2, 5, 0, 3), cellCenter(2, 0, 0, 3)), 1.0, volume);

        assertEquals(24, volume.cellCount());
        assertTrue(cells(volume).stream().allMatch(cell -> cell.y() == 0));
    }

    @Test
    void diagonalPlaneIsWatertightAndThin() {
        final CellVolume volume = volume(8);
        // A 45 degree ramp rising along X, 12 cells wide along Z.
        ShapeRasterizer.polygon(List.of(
            cellCenter(8, 0, 0, 0), cellCenter(8, 20, 20, 0),
            cellCenter(8, 20, 20, 11), cellCenter(8, 0, 0, 11)), 1.0, volume);

        final Set<Cell> cells = cells(volume);
        assertTrue(isSixConnected(cells), "ramp must not contain see-through gaps");
        final Map<Long, Integer> perColumn = new HashMap<>();
        for (final Cell cell : cells) {
            if (cell.z() == 5) perColumn.merge(cell.x(), 1, Integer::sum);
        }
        for (long x = 1; x < 20; x++) {
            final int count = perColumn.getOrDefault(x, 0);
            assertTrue(count >= 1 && count <= 3, "column " + x + " has " + count);
        }
    }

    @Test
    void thicknessGrowsTheSurface() {
        final List<Vector3d> points = List.of(
            cellCenter(4, 0, 4, 0), cellCenter(4, 7, 4, 0), cellCenter(4, 7, 4, 7));
        final CellVolume thin = volume(4);
        final CellVolume thick = volume(4);
        ShapeRasterizer.polygon(points, 1.0, thin);
        ShapeRasterizer.polygon(points, 3.0, thick);

        final Set<Long> layers = new HashSet<>();
        for (final Cell cell : cells(thick)) layers.add(cell.y());
        assertEquals(Set.of(3L, 4L, 5L), layers);
        assertTrue(thick.cellCount() > thin.cellCount() * 2);
    }

    @Test
    void loftingTwoStraightLinesMatchesAFlatRectangle() {
        final CellVolume volume = volume(2);
        ShapeRasterizer.loft(List.of(
            List.of(cellCenter(2, 0, 1, 0), cellCenter(2, 5, 1, 0)),
            List.of(cellCenter(2, 0, 1, 5), cellCenter(2, 5, 1, 5))), 1.0, volume);

        assertEquals(36, volume.cellCount());
        assertTrue(cells(volume).stream().allMatch(cell -> cell.y() == 1));
    }

    @Test
    void loftingThreeLinesPassesThroughTheMiddleLine() {
        final CellVolume volume = volume(4);
        // Three parallel lines at z = 0, 8 and 16. The middle line is raised,
        // and the surface must pass through it while staying smooth between.
        ShapeRasterizer.loft(List.of(
            List.of(cellCenter(4, 0, 0, 0), cellCenter(4, 16, 0, 0)),
            List.of(cellCenter(4, 0, 8, 8), cellCenter(4, 16, 8, 8)),
            List.of(cellCenter(4, 0, 0, 16), cellCenter(4, 16, 0, 16))), 1.0, volume);

        final Set<Cell> cells = cells(volume);
        long middleHeight = Long.MIN_VALUE;
        long endHeight = Long.MIN_VALUE;
        for (final Cell cell : cells) {
            if (cell.z() == 8) middleHeight = Math.max(middleHeight, cell.y());
            if (cell.z() == 0 || cell.z() == 16) endHeight = Math.max(endHeight, cell.y());
        }
        assertTrue(middleHeight >= 7, "the surface passes through the middle line");
        assertTrue(endHeight <= 1, "the surface passes through the end lines");
        assertTrue(isSixConnected(cells));
    }

    @Test
    void loftingLinesWithDifferentPointCountsStillFormsOneSurface() {
        final CellVolume volume = volume(4);
        ShapeRasterizer.loft(List.of(
            List.of(cellCenter(4, 0, 0, 0), cellCenter(4, 16, 0, 0)),
            List.of(cellCenter(4, 0, 0, 16), cellCenter(4, 8, 4, 16),
                cellCenter(4, 16, 0, 16))), 1.0, volume);

        assertTrue(volume.cellCount() > 0);
        assertTrue(isSixConnected(cells(volume)),
            "lines of different lengths still rasterize as one surface");
    }

    @Test
    void loftRejectsASingleLine() {
        assertThrows(IllegalArgumentException.class, () -> ShapeRasterizer.loft(
            List.of(List.of(new Vector3d(), new Vector3d(1, 0, 0))), 1.0, volume(2)));
    }

    @Test
    void straightCurveIsAConnectedLine() {
        final CellVolume volume = volume(4);
        ShapeRasterizer.curve(List.of(cellCenter(4, 0, 0, 0), cellCenter(4, 9, 5, 3)),
            1.0, volume);

        final Set<Cell> cells = cells(volume);
        assertTrue(cells.contains(new Cell(0, 0, 0)));
        assertTrue(cells.contains(new Cell(9, 5, 3)));
        assertTrue(isSixConnected(cells));
        assertTrue(cells.size() <= 9 + 5 + 3 + 1 + 6, "size " + cells.size());
    }

    @Test
    void curvePassesThroughEveryControlPoint() {
        final CellVolume volume = volume(2);
        final List<Vector3d> points = List.of(
            cellCenter(2, 0, 0, 0), cellCenter(2, 6, 6, 0), cellCenter(2, 12, 0, 0));
        ShapeRasterizer.curve(points, 1.0, volume);

        final Set<Cell> cells = cells(volume);
        assertTrue(cells.contains(new Cell(0, 0, 0)));
        assertTrue(cells.contains(new Cell(6, 6, 0)));
        assertTrue(cells.contains(new Cell(12, 0, 0)));
        assertTrue(isSixConnected(cells));
    }

    @Test
    void solidSphereApproximatesItsVolume() {
        final CellVolume volume = volume(1);
        ShapeRasterizer.sphere(new Vector3d(0.5, 0.5, 0.5), 5.0, 0.0, volume);

        final double expected = 4.0 / 3.0 * Math.PI * 125.0;
        assertEquals(expected, volume.cellCount(), expected * 0.1);
        assertTrue(volume.contains(0, 0, 0));
        assertTrue(volume.contains(5, 0, 0));
        assertFalse(volume.contains(6, 0, 0));
    }

    @Test
    void sphereShellIsHollowAndClosed() {
        final CellVolume volume = volume(2);
        ShapeRasterizer.sphere(new Vector3d(0.25, 0.25, 0.25), 3.0, 1.0, volume);

        assertFalse(volume.contains(0, 0, 0), "center must be empty");
        assertTrue(volume.contains(6, 0, 0));
        final Set<Cell> cells = cells(volume);
        assertTrue(isSixConnected(cells));
        // Flood fill from the center must not escape through the shell.
        assertTrue(enclosed(cells, new Cell(0, 0, 0), 10));
    }

    @Test
    void cylinderCoversItsAxisAndRadius() {
        final CellVolume solid = volume(1);
        ShapeRasterizer.cylinder(new Vector3d(0.5, 0.5, 0.5), new Vector3d(0.5, 6.5, 0.5),
            2.0, 0.0, solid);
        assertTrue(solid.contains(0, 0, 0));
        assertTrue(solid.contains(0, 6, 0));
        assertTrue(solid.contains(2, 3, 0));
        assertFalse(solid.contains(3, 3, 0));
        assertFalse(solid.contains(0, 7, 0));

        final CellVolume tube = volume(1);
        ShapeRasterizer.cylinder(new Vector3d(0.5, 0.5, 0.5), new Vector3d(0.5, 6.5, 0.5),
            3.0, 1.0, tube);
        assertFalse(tube.contains(0, 3, 0), "tube axis must be empty");
        assertTrue(tube.contains(3, 3, 0));
    }

    @Test
    void brushFootprints() {
        final CellVolume single = volume(16);
        ShapeRasterizer.brush(5, 5, 5, 0, ShapeRasterizer.BrushShape.SPHERE, single);
        assertEquals(1, single.cellCount());

        final CellVolume sphere = volume(16);
        ShapeRasterizer.brush(5, 5, 5, 1, ShapeRasterizer.BrushShape.SPHERE, sphere);
        assertEquals(19, sphere.cellCount());

        final CellVolume cube = volume(16);
        ShapeRasterizer.brush(-1, -1, -1, 1, ShapeRasterizer.BrushShape.CUBE, cube);
        assertEquals(27, cube.cellCount());
        assertEquals(8, cube.blockCount(), "a cube around a block corner spans 8 blocks");
    }

    @Test
    void limitsStopRunawayShapes() {
        final CellVolume volume = new CellVolume(16, 4, 1_000_000);
        final CellVolume.LimitExceededException blocks = assertThrows(
            CellVolume.LimitExceededException.class,
            () -> ShapeRasterizer.sphere(new Vector3d(), 3.0, 0.0, volume));
        assertTrue(blocks.blockLimit());

        final CellVolume cells = new CellVolume(16, 1000, 10);
        final CellVolume.LimitExceededException cellLimit = assertThrows(
            CellVolume.LimitExceededException.class,
            () -> ShapeRasterizer.brush(0, 0, 0, 2, ShapeRasterizer.BrushShape.CUBE, cells));
        assertFalse(cellLimit.blockLimit());
    }

    @Test
    void invalidShapesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShapeRasterizer.polygon(
            List.of(new Vector3d(), new Vector3d(1, 0, 0)), 1.0, volume(2)));
        assertThrows(IllegalArgumentException.class, () -> ShapeRasterizer.loft(
            List.of(List.of(new Vector3d(), new Vector3d()),
                List.of(new Vector3d(1, 0, 0))), 1.0, volume(2)));
        assertThrows(IllegalArgumentException.class, () -> ShapeRasterizer.cylinder(
            new Vector3d(1, 1, 1), new Vector3d(1, 1, 1), 2.0, 0.0, volume(2)));
    }

    @Test
    void collinearPolygonDegradesToALine() {
        final CellVolume volume = volume(2);
        ShapeRasterizer.polygon(List.of(
            cellCenter(2, 0, 0, 0), cellCenter(2, 3, 0, 0), cellCenter(2, 6, 0, 0)),
            1.0, volume);
        assertEquals(7, volume.cellCount());
    }

    // ---------------------------------------------------------------------

    private static CellVolume volume(final int grid) {
        return new CellVolume(grid, 100_000, 10_000_000);
    }

    /** World position of a cell center at the given resolution. */
    private static Vector3d cellCenter(final int grid, final long x, final long y, final long z) {
        return new Vector3d((x + 0.5) / grid, (y + 0.5) / grid, (z + 0.5) / grid);
    }

    private static Set<Cell> cells(final CellVolume volume) {
        final Set<Cell> cells = new HashSet<>();
        final int grid = volume.grid();
        for (final Map.Entry<BlockPos, BitSet> entry : volume.blocks().entrySet()) {
            final BlockPos block = entry.getKey();
            final BitSet bits = entry.getValue();
            for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
                cells.add(new Cell(
                    (long) block.x() * grid + CellVolume.localX(grid, index),
                    (long) block.y() * grid + CellVolume.localY(grid, index),
                    (long) block.z() * grid + CellVolume.localZ(grid, index)));
            }
        }
        return cells;
    }

    private static final long[][] FACES = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static boolean isSixConnected(final Set<Cell> cells) {
        if (cells.isEmpty()) return true;
        final Set<Cell> seen = new HashSet<>();
        final ArrayDeque<Cell> queue = new ArrayDeque<>();
        final Cell first = cells.iterator().next();
        queue.add(first);
        seen.add(first);
        while (!queue.isEmpty()) {
            final Cell cell = queue.poll();
            for (final long[] face : FACES) {
                final Cell next = new Cell(cell.x() + face[0], cell.y() + face[1],
                    cell.z() + face[2]);
                if (cells.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return seen.size() == cells.size();
    }

    /** Whether a 6-connected flood fill from {@code start} stays within {@code bound}. */
    private static boolean enclosed(final Set<Cell> walls, final Cell start, final long bound) {
        final Set<Cell> seen = new HashSet<>();
        final ArrayDeque<Cell> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            final Cell cell = queue.poll();
            if (Math.abs(cell.x()) > bound || Math.abs(cell.y()) > bound
                    || Math.abs(cell.z()) > bound) {
                return false;
            }
            for (final long[] face : FACES) {
                final Cell next = new Cell(cell.x() + face[0], cell.y() + face[1],
                    cell.z() + face[2]);
                if (!walls.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return true;
    }
}
