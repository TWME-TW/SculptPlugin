package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderStrategyTest {

    @Test
    void fillModeUsesNullAsItsPublicIdAndAcceptsNoneAlias() {
        assertEquals("null", FillMode.NONE.id());
        assertEquals(FillMode.NONE, FillMode.parse("null", FillMode.BARRIER));
        assertEquals(FillMode.NONE, FillMode.parse("none", FillMode.BARRIER));
        assertEquals(FillMode.SHULKER,
            FillMode.parse("unknown", FillMode.SHULKER));
    }

    @Test
    void displayModeAcceptsStableAndReadableSpellings() {
        assertEquals("textdisplay", SculptDisplayMode.TEXT_DISPLAY.id());
        assertEquals(SculptDisplayMode.TEXT_DISPLAY,
            SculptDisplayMode.parse("text_display", SculptDisplayMode.HEAD));
        assertEquals(SculptDisplayMode.HEAD,
            SculptDisplayMode.parse("head", SculptDisplayMode.TEXT_DISPLAY));
        assertEquals("auto", SculptDisplayMode.AUTO.id());
        assertEquals(SculptDisplayMode.AUTO,
            SculptDisplayMode.parse("automatic", SculptDisplayMode.HEAD));
        assertTrue(SculptDisplayMode.AUTO.usesTextRenderer());
        assertTrue(SculptDisplayMode.TEXT_DISPLAY.usesTextRenderer());
        assertFalse(SculptDisplayMode.HEAD.usesTextRenderer());
    }
}
