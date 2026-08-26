package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerHeadTextureTest {

    @Test
    void normalizesUnsignedTextures() {
        final PlayerHeadTexture signed = new PlayerHeadTexture(
            "eyJ0ZXh0dXJlcyI6e319", "signature");
        final PlayerHeadTexture unsigned = new PlayerHeadTexture(
            "eyJ0ZXh0dXJlcyI6e319", null);

        assertTrue(signed.isSigned());
        assertFalse(unsigned.isSigned());
    }

    @Test
    void constructorRejectsEmptyAndOversizedValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new PlayerHeadTexture(" ", ""));
        assertThrows(IllegalArgumentException.class,
            () -> new PlayerHeadTexture(
                "x".repeat(PlayerHeadTexture.MAX_VALUE_BYTES + 1), ""));
    }
}
