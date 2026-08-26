package dev.twme.sculpt.core;

import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceDirTest {

    @Test
    void normalReturnsUnitVectorForEachDirection() {
        assertEquals(new Vector3i( 0, -1,  0), FaceDir.DOWN.normal());
        assertEquals(new Vector3i( 0,  1,  0), FaceDir.UP.normal());
        assertEquals(new Vector3i( 0,  0, -1), FaceDir.NORTH.normal());
        assertEquals(new Vector3i( 0,  0,  1), FaceDir.SOUTH.normal());
        assertEquals(new Vector3i(-1,  0,  0), FaceDir.WEST.normal());
        assertEquals(new Vector3i( 1,  0,  0), FaceDir.EAST.normal());
    }

    @Test
    void cornerChunkHasAllThreeOutwardAxes() {
        int gridN = 4;
        ChunkCoord corner000 = new ChunkCoord(0, 0, 0);
        assertTrue(FaceDir.DOWN.isOutwardAt(corner000, gridN));
        assertTrue(FaceDir.NORTH.isOutwardAt(corner000, gridN));
        assertTrue(FaceDir.WEST.isOutwardAt(corner000, gridN));
        assertFalse(FaceDir.UP.isOutwardAt(corner000, gridN));
        assertFalse(FaceDir.SOUTH.isOutwardAt(corner000, gridN));
        assertFalse(FaceDir.EAST.isOutwardAt(corner000, gridN));
    }

    @Test
    void faceCenterChunkHasExactlyOneOutward() {
        int gridN = 4;
        // Center of +X face: x=3, y=2, z=2 — only EAST is outward.
        ChunkCoord eastFaceCenter = new ChunkCoord(3, 2, 2);
        assertTrue(FaceDir.EAST.isOutwardAt(eastFaceCenter, gridN));
        for (FaceDir f : FaceDir.values()) {
            if (f == FaceDir.EAST) continue;
            assertFalse(f.isOutwardAt(eastFaceCenter, gridN), f + " should not be outward at " + eastFaceCenter);
        }
    }

    @Test
    void edgeChunkHasExactlyTwoOutward() {
        int gridN = 4;
        // Edge between +X and +Y: (3, 3, 2) — EAST and UP are outward.
        ChunkCoord edge = new ChunkCoord(3, 3, 2);
        assertTrue(FaceDir.EAST.isOutwardAt(edge, gridN));
        assertTrue(FaceDir.UP.isOutwardAt(edge, gridN));
        for (FaceDir f : FaceDir.values()) {
            if (f == FaceDir.EAST || f == FaceDir.UP) continue;
            assertFalse(f.isOutwardAt(edge, gridN), f + " should not be outward at " + edge);
        }
    }

    @Test
    void interiorChunkHasNoOutward() {
        int gridN = 4;
        ChunkCoord interior = new ChunkCoord(1, 1, 1);
        for (FaceDir f : FaceDir.values()) {
            assertFalse(f.isOutwardAt(interior, gridN), f + " should not be outward at interior " + interior);
        }
    }

    @Test
    void isOutwardAtHandlesGridN1AsAlwaysOutward() {
        // At gridN=1, the single chunk is on every boundary.
        ChunkCoord only = new ChunkCoord(0, 0, 0);
        for (FaceDir f : FaceDir.values()) {
            assertTrue(f.isOutwardAt(only, 1));
        }
    }

    @Test
    void shadeValuesMatchVanillaClientLevel() {
        // Sourced from ClientLevel.getShade() in 1.21.4 decompiled.
        assertEquals(0.5f, FaceDir.DOWN.shade());
        assertEquals(1.0f, FaceDir.UP.shade());
        assertEquals(0.8f, FaceDir.NORTH.shade());
        assertEquals(0.8f, FaceDir.SOUTH.shade());
        assertEquals(0.6f, FaceDir.WEST.shade());
        assertEquals(0.6f, FaceDir.EAST.shade());
    }

    @Test
    void jsonNameIsLowercase() {
        assertEquals("down", FaceDir.DOWN.jsonName());
        assertEquals("north", FaceDir.NORTH.jsonName());
    }

    @Test
    void fromJsonRoundtripsJsonName() {
        for (FaceDir f : FaceDir.values()) {
            assertEquals(f, FaceDir.fromJson(f.jsonName()));
        }
    }
}
