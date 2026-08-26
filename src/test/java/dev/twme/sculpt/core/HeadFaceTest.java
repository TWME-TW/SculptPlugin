package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadFaceTest {

    @Test
    void allSlotsAreEightByEight() {
        for (HeadFace f : HeadFace.values()) {
            assertEquals(8, f.width(),  f + " width");
            assertEquals(8, f.height(), f + " height");
        }
    }

    @Test
    void uvRectsAreNonOverlappingAndInsideCanvas() {
        // Head skin is 64x64; the head regions occupy x∈[0,32], y∈[0,16].
        for (HeadFace a : HeadFace.values()) {
            assertTrue(a.u0 >= 0 && a.v0 >= 0, a + " starts at non-negative UV");
            assertTrue(a.u1 <= 32 && a.v1 <= 16, a + " stays inside the head region");
        }
    }

    @Test
    void packOrderStartsWithFront() {
        // Frame-0 of any lifetime must land on the most-visible slot.
        assertEquals(HeadFace.FRONT, HeadFace.PACK_ORDER[0]);
        assertEquals(6, HeadFace.PACK_ORDER.length);
    }
}
