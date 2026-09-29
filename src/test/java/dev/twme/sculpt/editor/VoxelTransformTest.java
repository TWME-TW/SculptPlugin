package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class VoxelTransformTest {

    private static final VoxelBox BOX = new VoxelBox(0, 0, 0, 4, 2, 2);

    @Test
    void translationMovesEveryVoxel() {
        final VoxelTransform transform = new VoxelTransform(BOX, 0, false, false, 16, -2, 3);
        assertArrayEquals(new long[]{17, -1, 4}, transform.apply(1, 1, 1));
        assertEquals(new VoxelBox(16, -2, 3, 20, 0, 5), transform.destination());
        assertFalse(transform.isIdentity());
        assertTrue(VoxelTransform.identity(BOX).isIdentity());
    }

    @Test
    void quarterTurnSwapsHorizontalSizesAndStaysInsideTheDestination() {
        final VoxelTransform turn = new VoxelTransform(BOX, 1, false, false, 0, 0, 0);
        final VoxelBox destination = turn.destination();
        assertEquals(2, destination.sizeX());
        assertEquals(4, destination.sizeZ());
        final Set<String> seen = new HashSet<>();
        for (long x = BOX.minX(); x < BOX.maxX(); x++) {
            for (long y = BOX.minY(); y < BOX.maxY(); y++) {
                for (long z = BOX.minZ(); z < BOX.maxZ(); z++) {
                    final long[] target = turn.apply(x, y, z);
                    assertTrue(destination.contains(target[0], target[1], target[2]));
                    assertTrue(seen.add(target[0] + "," + target[1] + "," + target[2]), "bijective");
                }
            }
        }
        assertEquals(BOX.volume(), seen.size());
    }

    @Test
    void clockwiseTurnViewedFromAbove() {
        // The +X end of the box faces +Z after a clockwise quarter turn.
        final VoxelTransform turn = new VoxelTransform(BOX, 1, false, false, 0, 0, 0);
        assertArrayEquals(new long[]{1, 0, 0}, turn.apply(0, 0, 0));
        assertArrayEquals(new long[]{1, 0, 3}, turn.apply(3, 0, 0));
    }

    @Test
    void fourTurnsAndDoubleMirrorsAreIdentities() {
        final VoxelTransform four = new VoxelTransform(BOX, 4, false, false, 0, 0, 0);
        assertTrue(four.isIdentity());
        final VoxelTransform mirror = new VoxelTransform(BOX, 0, true, false, 0, 0, 0);
        assertArrayEquals(new long[]{3, 1, 1}, mirror.apply(0, 1, 1));
        final VoxelTransform half = new VoxelTransform(BOX, 2, false, false, 0, 0, 0);
        final VoxelTransform mirrored = new VoxelTransform(BOX, 0, true, true, 0, 0, 0);
        assertArrayEquals(half.apply(1, 0, 0), mirrored.apply(1, 0, 0),
            "a half turn equals mirroring both axes");
    }

    @Test
    void voxelBoxHelpers() {
        final VoxelBox box = new VoxelBox(-8, 0, 0, 24, 16, 16);
        assertEquals(-1, box.minBlockX());
        assertEquals(1, box.maxBlockX());
        assertEquals(box, box.expand(-100), "shrinking past zero keeps the box");
        assertEquals(new VoxelBox(-10, -2, -2, 26, 18, 18), box.expand(2));
        assertEquals("2", VoxelBox.blocks(32));
        assertEquals("1.5", VoxelBox.blocks(24));
        assertEquals("0.0625", VoxelBox.blocks(1));
        assertThrows(IllegalArgumentException.class, () -> new VoxelBox(0, 0, 0, 0, 1, 1));
    }

    @Test
    void scrollDirectionHandlesWrapping() {
        assertEquals(1, EditorListener.scrollDirection(3, 4));
        assertEquals(-1, EditorListener.scrollDirection(4, 3));
        assertEquals(1, EditorListener.scrollDirection(8, 0));
        assertEquals(-1, EditorListener.scrollDirection(0, 8));
        assertEquals(0, EditorListener.scrollDirection(2, 2));
    }
}
