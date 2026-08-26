package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockKeyTest {

    @Test
    void ofAddsMinecraftNamespaceWhenMissing() {
        BlockKey k = BlockKey.of("stone");
        assertEquals("minecraft", k.namespace());
        assertEquals("stone", k.path());
    }

    @Test
    void ofPreservesExplicitNamespace() {
        BlockKey k = BlockKey.of("custom:ruby_ore");
        assertEquals("custom", k.namespace());
        assertEquals("ruby_ore", k.path());
    }

    @Test
    void ofLowercasesInput() {
        BlockKey a = BlockKey.of("Stone");
        BlockKey b = BlockKey.of("STONE");
        assertEquals(a, b);
    }

    @Test
    void asStringRoundtripsThroughOf() {
        assertEquals("minecraft:oak_log", BlockKey.of("oak_log").asString());
        assertEquals("custom:ruby_ore", BlockKey.of("custom:ruby_ore").asString());
    }

    @Test
    void rejectsEmptyNamespaceOrPath() {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("", "stone"));
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft", ""));
        assertThrows(NullPointerException.class, () -> new BlockKey(null, "stone"));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(new BlockKey("minecraft", "stone"), new BlockKey("minecraft", "stone"));
        assertNotEquals(new BlockKey("minecraft", "stone"), new BlockKey("minecraft", "dirt"));
    }
}
