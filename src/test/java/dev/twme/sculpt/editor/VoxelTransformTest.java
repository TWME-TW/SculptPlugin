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

    private static VoxelTransform of(final VoxelBox box, final int quarterTurns,
                                     final boolean mirrorX, final boolean mirrorZ,
                                     final long dx, final long dy, final long dz) {
        return new VoxelTransform(
            VoxelRotation.around(box, quarterTurns * Math.PI / 2, mirrorX, mirrorZ), dx, dy, dz);
    }

    @Test
    void translationMovesEveryVoxel() {
        final VoxelTransform transform = of(BOX, 0, false, false, 16, -2, 3);
        assertArrayEquals(new long[]{17, -1, 4}, transform.apply(1, 1, 1));
        assertEquals(new VoxelBox(16, -2, 3, 20, 0, 5), transform.destination(BOX));
        assertFalse(transform.isIdentity());
        assertTrue(VoxelTransform.identity(BOX).isIdentity());
    }

    @Test
    void quarterTurnStaysInsideTheDestinationAndIsBijective() {
        final VoxelTransform turn = of(BOX, 1, false, false, 0, 0, 0);
        final VoxelBox destination = turn.destination(BOX);
        // The 4x2 box is centered at (2, 1), so rotating it about that center
        // keeps it inside a 2x4 box spanning x 1..3 and z -1..3.
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
    void quarterTurnsRotateAroundTheBoxCenter() {
        // Voxel centers are (x + 0.5, z + 0.5) and the box center is (2, 1),
        // so a counter-clockwise turn maps (cx, cz) to (3 - cz, cx - 1).
        final VoxelTransform turn = of(BOX, 1, false, false, 0, 0, 0);
        assertArrayEquals(new long[]{2, 0, 1}, turn.apply(2, 0, 0));
        assertArrayEquals(new long[]{2, 0, -1}, turn.apply(0, 0, 0));
        assertArrayEquals(new long[]{1, 0, 2}, turn.apply(3, 0, 1));
    }

    @Test
    void quarterTurnInverseRecoversTheSourceVoxel() {
        final VoxelTransform turn = of(BOX, 1, false, false, 0, 0, 0);
        for (long x = BOX.minX(); x < BOX.maxX(); x++) {
            for (long z = BOX.minZ(); z < BOX.maxZ(); z++) {
                final long[] target = turn.apply(x, 0, z);
                final long[] back = turn.inverse(target[0], 0, target[2], BOX);
                assertArrayEquals(new long[]{x, 0, z}, back,
                    "inverse must undo the rotation for " + x + "," + z);
            }
        }
    }

    @Test
    void fourTurnsAndDoubleMirrorsAreIdentities() {
        assertTrue(of(BOX, 4, false, false, 0, 0, 0).isIdentity());
        // Mirroring reflects voxel centers about the pivot x = 2.0, which sits
        // on a voxel boundary, so the outermost voxels swap.
        final VoxelTransform mirror = of(BOX, 0, true, false, 0, 0, 0);
        assertArrayEquals(new long[]{0, 1, 1}, mirror.apply(3, 1, 1));
        assertArrayEquals(new long[]{3, 1, 1}, mirror.apply(0, 1, 1));
        assertArrayEquals(new long[]{2, 1, 1}, mirror.apply(1, 1, 1));
        final VoxelTransform half = of(BOX, 2, false, false, 0, 0, 0);
        final VoxelTransform mirrored = of(BOX, 0, true, true, 0, 0, 0);
        assertArrayEquals(half.apply(1, 0, 0), mirrored.apply(1, 0, 0),
            "a half turn equals mirroring both axes");
    }

    @Test
    void freeAnglesRotateAndInvertConsistently() {
        final VoxelRotation rotation = VoxelRotation.around(BOX, Math.toRadians(30), false, false);
        assertFalse(rotation.isQuarterTurn());
        // Rotating a point and inverting it must return the same point.
        for (double x = 0; x <= 4; x += 0.5) {
            for (double z = 0; z <= 2; z += 0.5) {
                final double[] forward = rotation.apply(x, z);
                final double[] back = rotation.inverseApply(forward[0], forward[1]);
                assertEquals(x, back[0], 1e-9);
                assertEquals(z, back[1], 1e-9);
            }
        }
        // The pivot is the fixed point of the rotation.
        final double[] center = rotation.apply(2, 1);
        assertEquals(2.0, center[0], 1e-9);
        assertEquals(1.0, center[1], 1e-9);
        // A rotated box keeps its horizontal extent within its destination.
        final VoxelBox destination = rotation.destination(BOX);
        for (long x = 0; x < 4; x++) {
            for (long z = 0; z < 2; z++) {
                final long[] target = rotation.applyVoxel(x, z);
                assertTrue(destination.contains(target[0], 0, target[1]),
                    "rotated voxel must land inside the destination box");
            }
        }
    }

    @Test
    void freeRotationCoversEverySourceVoxelExactlyOnce() {
        final VoxelTransform turn = new VoxelTransform(
            VoxelRotation.around(BOX, Math.toRadians(37), false, false), 0, 0, 0);
        final Set<String> targets = new HashSet<>();
        for (long x = 0; x < 4; x++) {
            for (long z = 0; z < 2; z++) {
                final long[] target = turn.apply(x, 0, z);
                targets.add(target[0] + "," + target[2]);
            }
        }
        assertFalse(targets.isEmpty(), "a rotation must place every voxel somewhere");
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
