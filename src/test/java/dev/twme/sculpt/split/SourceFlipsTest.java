package dev.twme.sculpt.split;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FaceDir;

class SourceFlipsTest {

    @AfterEach
    void cleanup() {
        SourceFlips.resetAll();
    }

    @Test
    void downDefaultsToV() {
        // See SourceFlips class javadoc: the head-model BOTTOM UV has
        // image-Y = -Z (vanilla cube DOWN convention), so the source
        // needs a V-flip before slicing. Sides default to NONE.
        assertEquals(SourceFlips.Flip.V,    SourceFlips.of(FaceDir.DOWN));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.of(FaceDir.UP));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.of(FaceDir.NORTH));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.of(FaceDir.SOUTH));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.of(FaceDir.EAST));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.of(FaceDir.WEST));
    }

    @Test
    void parseAcceptsCommonAliases() {
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.Flip.parse("none"));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.Flip.parse("off"));
        assertEquals(SourceFlips.Flip.NONE, SourceFlips.Flip.parse(""));
        assertEquals(SourceFlips.Flip.H,    SourceFlips.Flip.parse("h"));
        assertEquals(SourceFlips.Flip.H,    SourceFlips.Flip.parse("horizontal"));
        assertEquals(SourceFlips.Flip.H,    SourceFlips.Flip.parse("flipx"));
        assertEquals(SourceFlips.Flip.V,    SourceFlips.Flip.parse("v"));
        assertEquals(SourceFlips.Flip.V,    SourceFlips.Flip.parse("vertical"));
        assertEquals(SourceFlips.Flip.V,    SourceFlips.Flip.parse("flipy"));
        assertEquals(SourceFlips.Flip.HV,   SourceFlips.Flip.parse("hv"));
        assertEquals(SourceFlips.Flip.HV,   SourceFlips.Flip.parse("both"));
        assertEquals(SourceFlips.Flip.HV,   SourceFlips.Flip.parse("flipxy"));
    }

    @Test
    void parseIsCaseInsensitive() {
        assertEquals(SourceFlips.Flip.H, SourceFlips.Flip.parse("H"));
        assertEquals(SourceFlips.Flip.H, SourceFlips.Flip.parse("Horizontal"));
    }

    @Test
    void parseRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> SourceFlips.Flip.parse("diagonal"));
        assertThrows(IllegalArgumentException.class, () -> SourceFlips.Flip.parse("xyz"));
    }

    @Test
    void setAndReset() {
        SourceFlips.set(FaceDir.UP, SourceFlips.Flip.HV);
        assertEquals(SourceFlips.Flip.HV, SourceFlips.of(FaceDir.UP));
        SourceFlips.reset(FaceDir.UP);
        assertEquals(SourceFlips.defaultOf(FaceDir.UP), SourceFlips.of(FaceDir.UP));
    }

    @Test
    void resetAllClearsAllOverrides() {
        SourceFlips.set(FaceDir.UP,   SourceFlips.Flip.H);
        SourceFlips.set(FaceDir.DOWN, SourceFlips.Flip.NONE);  // overrides the V default
        SourceFlips.resetAll();
        for (FaceDir f : FaceDir.values()) {
            assertEquals(SourceFlips.defaultOf(f), SourceFlips.of(f),
                    "resetAll should restore " + f + " to its default");
        }
    }
}
