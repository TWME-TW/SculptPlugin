package dev.twme.sculpt.split;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FaceDir;

class SourceRotationsTest {

    @AfterEach
    void cleanup() {
        SourceRotations.resetAll();
    }

    @Test
    void defaultIsZeroForAllFaces() {
        for (FaceDir f : FaceDir.values()) {
            assertEquals(0, SourceRotations.of(f), "default for " + f);
            assertEquals(0, SourceRotations.defaultOf(f), "defaultOf for " + f);
        }
    }

    @Test
    void setNormalizesTo360Range() {
        SourceRotations.set(FaceDir.UP, 720);
        assertEquals(0, SourceRotations.of(FaceDir.UP));
        SourceRotations.set(FaceDir.UP, -90);
        assertEquals(270, SourceRotations.of(FaceDir.UP));
    }

    @Test
    void setRejectsNonMultipleOf90() {
        assertThrows(IllegalArgumentException.class, () -> SourceRotations.set(FaceDir.UP, 45));
        assertThrows(IllegalArgumentException.class, () -> SourceRotations.set(FaceDir.UP, 1));
    }

    @Test
    void resetReturnsToDefault() {
        SourceRotations.set(FaceDir.UP, 90);
        SourceRotations.reset(FaceDir.UP);
        assertEquals(0, SourceRotations.of(FaceDir.UP));
    }

    @Test
    void resetAllClearsAllOverrides() {
        SourceRotations.set(FaceDir.UP,   90);
        SourceRotations.set(FaceDir.DOWN, 180);
        SourceRotations.resetAll();
        for (FaceDir f : FaceDir.values()) {
            assertEquals(SourceRotations.defaultOf(f), SourceRotations.of(f));
        }
    }
}
