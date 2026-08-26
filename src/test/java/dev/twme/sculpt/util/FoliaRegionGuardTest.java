package dev.twme.sculpt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FoliaRegionGuardTest {

    @Test
    void mapsTouchedChunksIncludingNegativeCoordinates() {
        assertEquals(
            new FoliaRegionGuard.ChunkRange(-2, -1, 1, 1),
            FoliaRegionGuard.chunksTouched(-17, -1, 16, 16));
    }

    @Test
    void mapsCrossChunkRange() {
        assertEquals(
            new FoliaRegionGuard.ChunkRange(0, 0, 1, 0),
            FoliaRegionGuard.chunksTouched(15, 0, 16, 0));
    }

    @Test
    void keepsSingleChunkRangeStable() {
        assertEquals(
            new FoliaRegionGuard.ChunkRange(2, -3, 2, -3),
            FoliaRegionGuard.chunksTouched(32, -48, 47, -33));
    }

    @Test
    void normalizesReversedCorners() {
        assertEquals(
            new FoliaRegionGuard.ChunkRange(0, 0, 1, 1),
            FoliaRegionGuard.chunksTouched(31, 31, 0, 0));
    }
}
