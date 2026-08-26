package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasteSettingsTest {

    @Test
    void explicitFalseFlagsOverrideTrueDefaults() {
        PasteSettings settings = PasteSettings.fromCommand(
            false, false, false, "player", 270, "x");

        assertFalse(settings.pasteAir());
        assertFalse(settings.overwriteCells());
        assertFalse(settings.overwriteBlocks());
        assertFalse(settings.adhesive());
        assertEquals(PasteSettings.RotateMode.PLAYER, settings.rotateMode());
        assertEquals(270, settings.ry());
        assertEquals("x", settings.flipAxis());
    }

    @Test
    void nullFlagsKeepDefaults() {
        PasteSettings settings = PasteSettings.fromCommand(
            null, null, null, null, null, null);

        assertTrue(settings.pasteAir());
        assertTrue(settings.overwriteCells());
        assertTrue(settings.overwriteBlocks());
        assertFalse(settings.adhesive());
        assertEquals(PasteSettings.RotateMode.AUTO, settings.rotateMode());
    }
}
