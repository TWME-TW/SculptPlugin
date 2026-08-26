package dev.twme.sculpt.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BlockPosKeyTest {

    @Test
    void sameWorldAndCoordsAreEqual() {
        BlockPosKey a = new BlockPosKey("world", 10, 64, 20);
        BlockPosKey b = new BlockPosKey("world", 10, 64, 20);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentWorldsAreNotEqual() {
        assertNotEquals(
                new BlockPosKey("world1", 10, 64, 20),
                new BlockPosKey("world2", 10, 64, 20));
    }

    @Test
    void differentCoordsAreNotEqual() {
        assertNotEquals(
                new BlockPosKey("world", 10, 64, 20),
                new BlockPosKey("world", 11, 64, 20));
        assertNotEquals(
                new BlockPosKey("world", 10, 64, 20),
                new BlockPosKey("world", 10, 65, 20));
        assertNotEquals(
                new BlockPosKey("world", 10, 64, 20),
                new BlockPosKey("world", 10, 64, 21));
    }

    @Test
    void toStringIncludesAllFields() {
        BlockPosKey k = new BlockPosKey("world", 10, 64, 20);
        String s = k.toString();
        assertEquals(true, s.contains("world"));
        assertEquals(true, s.contains("10"));
        assertEquals(true, s.contains("64"));
        assertEquals(true, s.contains("20"));
    }
}
