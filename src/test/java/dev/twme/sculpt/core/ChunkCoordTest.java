package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCoordTest {

    @Test
    void rejectsNegativeComponents() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(0, 0, -1));
    }

    @Test
    void asKeyRoundtripsThroughParseKey() {
        ChunkCoord a = new ChunkCoord(3, 2, 1);
        assertEquals("3,2,1", a.asKey());
        assertEquals(a, ChunkCoord.parseKey(a.asKey()));
    }

    @Test
    void parseKeyRejectsMalformedString() {
        assertThrows(IllegalArgumentException.class, () -> ChunkCoord.parseKey("1,2"));
        assertThrows(IllegalArgumentException.class, () -> ChunkCoord.parseKey("a,b,c"));
    }

    @Test
    void compareToOrdersByYThenZThenX() {
        // Smaller y comes first.
        assertTrue(new ChunkCoord(9, 0, 9).compareTo(new ChunkCoord(0, 1, 0)) < 0);
        // Same y: smaller z first.
        assertTrue(new ChunkCoord(9, 1, 0).compareTo(new ChunkCoord(0, 1, 9)) < 0);
        // Same y, z: smaller x first.
        assertTrue(new ChunkCoord(0, 1, 1).compareTo(new ChunkCoord(1, 1, 1)) < 0);
    }

    @Test
    void compareToSortsListIntoCanonicalOrder() {
        List<ChunkCoord> in = new ArrayList<>(List.of(
                new ChunkCoord(2, 1, 0),
                new ChunkCoord(0, 0, 2),
                new ChunkCoord(1, 1, 1),
                new ChunkCoord(0, 1, 0)
        ));
        in.sort(ChunkCoord::compareTo);
        // Canonical order: (y, z, x) — (0,0,2) first, then y=1 sorted by z then x.
        // (0,1,0) [z=0,x=0], (2,1,0) [z=0,x=2], (1,1,1) [z=1,x=1].
        assertEquals(List.of(
                new ChunkCoord(0, 0, 2),
                new ChunkCoord(0, 1, 0),
                new ChunkCoord(2, 1, 0),
                new ChunkCoord(1, 1, 1)
        ), in);
    }

    @Test
    void compareToIsZeroForEqual() {
        assertEquals(0, new ChunkCoord(1, 2, 3).compareTo(new ChunkCoord(1, 2, 3)));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(new ChunkCoord(1, 2, 3), new ChunkCoord(1, 2, 3));
        assertNotEquals(new ChunkCoord(1, 2, 3), new ChunkCoord(3, 2, 1));
    }
}
