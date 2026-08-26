package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FaceDir;

class GridCellTest {

    @Test
    void derivesAdjacentCellFromHitFace() {
        final VirtualGridHit hit = new VirtualGridHit(
            1, 2, 3, FaceDir.EAST, null);

        assertEquals(new GridCell(2, 2, 3), GridCell.adjacentTo(hit));
    }

    @Test
    void detectsAndWrapsCellsOutsideTheGrid() {
        final GridCell outside = new GridCell(-1, 2, 4);

        assertFalse(outside.isInside(4));
        assertEquals(new GridCell(3, 2, 0), outside.wrapped(4));
        assertTrue(outside.wrapped(4).isInside(4));
    }

    @Test
    void convertsPlayerGridCoordinatesToOctreeCenters() {
        final GridCell cell = new GridCell(1, 2, 3);

        assertEquals(6, cell.centerX(4));
        assertEquals(10, cell.centerY(4));
        assertEquals(14, cell.centerZ(4));
    }
}
