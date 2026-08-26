package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.HeadFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileFlipsTest {

    @AfterEach
    void cleanup() {
        TileFlips.resetAll();
        TileRotations.resetAll();
    }

    @Test
    void defaultIsNoneForAllFaces() {
        for (HeadFace f : HeadFace.values()) {
            assertEquals(TileFlips.Flip.NONE, TileFlips.of(f));
            assertEquals(TileFlips.Flip.NONE, TileFlips.defaultOf(f));
        }
    }

    @Test
    void parseAcceptsCommonAliases() {
        assertEquals(TileFlips.Flip.NONE, TileFlips.Flip.parse("none"));
        assertEquals(TileFlips.Flip.H,    TileFlips.Flip.parse("h"));
        assertEquals(TileFlips.Flip.V,    TileFlips.Flip.parse("v"));
        assertEquals(TileFlips.Flip.HV,   TileFlips.Flip.parse("hv"));
        assertEquals(TileFlips.Flip.HV,   TileFlips.Flip.parse("both"));
        assertEquals(TileFlips.Flip.H,    TileFlips.Flip.parse("Horizontal"));
    }

    @Test
    void parseRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> TileFlips.Flip.parse("diagonal"));
    }

    @Test
    void setMarksStaleOnTileRotations() {
        TileFlips.set(HeadFace.FRONT, TileFlips.Flip.H);
        assertTrue(TileRotations.consumeStale(),
                "TileFlips.set must propagate to TileRotations' STALE flag");
    }

    @Test
    void setSameValueDoesNotMarkStale() {
        TileFlips.set(HeadFace.FRONT, TileFlips.Flip.H);
        TileRotations.consumeStale();   // drain
        TileFlips.set(HeadFace.FRONT, TileFlips.Flip.H);
        assertFalse(TileRotations.consumeStale(),
                "set() to the same value must not re-mark stale");
    }

    @Test
    void resetReturnsToDefault() {
        TileFlips.set(HeadFace.FRONT, TileFlips.Flip.HV);
        TileFlips.reset(HeadFace.FRONT);
        assertEquals(TileFlips.defaultOf(HeadFace.FRONT), TileFlips.of(HeadFace.FRONT));
    }
}
