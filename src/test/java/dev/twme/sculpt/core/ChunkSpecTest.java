package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkSpecTest {

    private static BufferedImage tile() {
        return new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    }

    private static Map<FaceDir, BufferedImage> allSix() {
        EnumMap<FaceDir, BufferedImage> m = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) m.put(d, tile());
        return m;
    }

    @Test
    void storesAllSixFaceTiles() {
        ChunkSpec spec = new ChunkSpec(new ChunkCoord(0, 0, 0), allSix());
        assertEquals(new ChunkCoord(0, 0, 0), spec.coord());
        for (FaceDir d : FaceDir.values()) {
            assertNotNull(spec.tile(d), "tile for " + d + " should be non-null");
        }
    }

    @Test
    void rejectsIncompleteTileMap() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkSpec(new ChunkCoord(0, 0, 0), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkSpec(new ChunkCoord(0, 0, 0),
                        Map.of(FaceDir.UP, tile())));
    }

    @Test
    void rejectsNullCoord() {
        assertThrows(NullPointerException.class,
                () -> new ChunkSpec(null, allSix()));
    }

    @Test
    void tilesReturnsDefensiveCopy() {
        ChunkSpec spec = new ChunkSpec(new ChunkCoord(2, 3, 2), allSix());
        Map<FaceDir, BufferedImage> copy1 = spec.tiles();
        Map<FaceDir, BufferedImage> copy2 = spec.tiles();
        assertEquals(copy1, copy2);
        assertEquals(6, copy1.size());
    }

    @Test
    void tileReturnsCorrectImage() {
        BufferedImage up = tile();
        EnumMap<FaceDir, BufferedImage> m = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) m.put(d, d == FaceDir.UP ? up : tile());
        ChunkSpec spec = new ChunkSpec(new ChunkCoord(3, 2, 2), m);
        assertSame(up, spec.tile(FaceDir.UP));
    }
}
