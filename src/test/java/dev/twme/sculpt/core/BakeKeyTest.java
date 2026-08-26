package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakeKeyTest {

    private final BlockKey stone = new BlockKey("minecraft", "stone");

    @Test
    void untintedHasTintZero() {
        BakeKey k = BakeKey.untinted(stone);
        assertEquals(0, k.tintArgb());
        assertFalse(k.isTinted());
    }

    @Test
    void tintedCarriesTintValue() {
        BakeKey k = new BakeKey(stone, 0xFF7FBF2E);
        assertTrue(k.isTinted());
        assertEquals(0xFF7FBF2E, k.tintArgb());
    }

    @Test
    void toStringDropsHashWhenUntinted() {
        assertEquals("minecraft:stone", BakeKey.untinted(stone).toString());
    }

    @Test
    void toStringUsesSixDigitHexForTint() {
        BakeKey k = new BakeKey(stone, 0xFF7FBF2E);
        assertEquals("minecraft:stone#7fbf2e", k.toString());
    }

    @Test
    void parseRoundtripsUntinted() {
        BakeKey k = BakeKey.parse("minecraft:oak_log");
        assertEquals(BakeKey.untinted(new BlockKey("minecraft", "oak_log")), k);
        assertFalse(k.isTinted());
    }

    @Test
    void parseRoundtripsTintedWithForcedAlpha() {
        BakeKey k = BakeKey.parse("minecraft:oak_leaves#7fbf2e");
        assertEquals("minecraft:oak_leaves", k.block().asString());
        assertEquals(0xFF7FBF2E, k.tintArgb());
        assertTrue(k.isTinted());
    }

    @Test
    void parseToStringRoundtrip() {
        BakeKey a = new BakeKey(new BlockKey("minecraft", "grass_block"), 0xFF00FF00);
        assertEquals(a, BakeKey.parse(a.toString()));
    }

    @Test
    void rejectsNullBlock() {
        assertThrows(NullPointerException.class, () -> new BakeKey(null, 0));
    }

    @Test
    void equalityIsByValue() {
        BakeKey a = new BakeKey(stone, 0xFF112233);
        BakeKey b = new BakeKey(stone, 0xFF112233);
        BakeKey c = new BakeKey(stone, 0xFF445566);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
