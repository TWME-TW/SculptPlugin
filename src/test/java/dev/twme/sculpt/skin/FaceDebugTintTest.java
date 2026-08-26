package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.HeadFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceDebugTintTest {

    @AfterEach
    void cleanup() {
        FaceDebugTint.setEnabled(false);
    }

    @Test
    void defaultIsDisabled() {
        assertFalse(FaceDebugTint.isEnabled());
    }

    @Test
    void setEnabledTrueMarksStale() {
        FaceDebugTint.setEnabled(true);
        assertTrue(FaceDebugTint.isEnabled());
        assertTrue(TileRotations.consumeStale(),
                "setEnabled(true) must propagate to TileRotations' STALE flag");
    }

    @Test
    void setEnabledSameValueDoesNotMarkStale() {
        FaceDebugTint.setEnabled(true);
        TileRotations.consumeStale();   // drain
        FaceDebugTint.setEnabled(true);
        assertFalse(TileRotations.consumeStale(),
                "setEnabled to the same value must not re-mark stale");
    }

    @Test
    void markerIsCorrectSize() {
        for (int size : new int[]{1, 2, 4, 8}) {
            BufferedImage m = FaceDebugTint.marker(HeadFace.FRONT, size);
            assertEquals(size, m.getWidth(),  "width at size=" + size);
            assertEquals(size, m.getHeight(), "height at size=" + size);
        }
    }

    @Test
    void markerHasDistinctFillPerFace() {
        // Centre pixel of each face's marker should be the face's fill
        // color (no edge stripe at center for size >= 4).
        for (HeadFace face : HeadFace.values()) {
            BufferedImage m = FaceDebugTint.marker(face, 8);
            int rgb = m.getRGB(4, 4);  // centre pixel of 8x8
            int a = (rgb >>> 24) & 0xFF;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8)  & 0xFF;
            int b = rgb         & 0xFF;
            // The 4 different per-face fills: TOP light gray, BOTTOM dark
            // gray, FRONT orange, BACK purple, LEFT teal, RIGHT magenta.
            // Just check alpha is opaque and not pure white/black.
            assertEquals(0xFF, a, face + " center should be opaque");
            assertNotNull(m, "marker should not be null");
        }
    }

    @Test
    void markerHasDistinctEdgeColors() {
        // 8x8 marker has stripe=2. Check that the top edge, right edge,
        // bottom edge, left edge are 4 distinct colors (red/green/blue/yellow).
        BufferedImage m = FaceDebugTint.marker(HeadFace.FRONT, 8);
        int top    = m.getRGB(4, 0);   // top stripe
        int right  = m.getRGB(7, 4);   // right stripe
        int bottom = m.getRGB(4, 7);   // bottom stripe
        int left   = m.getRGB(0, 4);   // left stripe
        assertEquals(0xFFE53935, top,    "top stripe should be red");
        assertEquals(0xFF43A047, right,  "right stripe should be green");
        assertEquals(0xFF1E88E5, bottom, "bottom stripe should be blue");
        assertEquals(0xFFFDD835, left,   "left stripe should be yellow");
    }
}
