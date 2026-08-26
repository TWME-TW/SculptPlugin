package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.HeadFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileRotationsTest {

    @AfterEach
    void cleanup() {
        TileRotations.resetAll();
        FaceDebugTint.setEnabled(false);
    }

    @Test
    void defaultIsZeroForAllFaces() {
        for (HeadFace f : HeadFace.values()) {
            assertEquals(0, TileRotations.of(f));
            assertEquals(0, TileRotations.defaultOf(f));
        }
    }

    @Test
    void setNormalizesTo360Range() {
        TileRotations.set(HeadFace.FRONT, 720);
        assertEquals(0, TileRotations.of(HeadFace.FRONT));
        TileRotations.set(HeadFace.FRONT, -90);
        assertEquals(270, TileRotations.of(HeadFace.FRONT));
    }

    @Test
    void setRejectsNonMultipleOf90() {
        assertThrows(IllegalArgumentException.class, () -> TileRotations.set(HeadFace.FRONT, 45));
        assertThrows(IllegalArgumentException.class, () -> TileRotations.set(HeadFace.FRONT, 1));
    }

    @Test
    void setSameValueDoesNotMarkStale() {
        TileRotations.set(HeadFace.FRONT, 90);
        assertTrue(TileRotations.consumeStale(), "first set() must mark stale");
        TileRotations.set(HeadFace.FRONT, 90);
        assertFalse(TileRotations.consumeStale(),
                "set() to the same value must not re-mark stale");
    }

    @Test
    void resetReturnsToDefault() {
        TileRotations.set(HeadFace.FRONT, 90);
        TileRotations.reset(HeadFace.FRONT);
        assertEquals(0, TileRotations.of(HeadFace.FRONT));
    }

    @Test
    void resetAllMarksStale() {
        TileRotations.set(HeadFace.FRONT, 90);
        TileRotations.consumeStale();   // drain
        TileRotations.resetAll();
        assertTrue(TileRotations.consumeStale(),
                "resetAll() must mark stale even when reverting to defaults");
    }
}
