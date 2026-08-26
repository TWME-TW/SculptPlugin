package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.HeadFace;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class HeadSkinPackerTest {

    private static BufferedImage tile(int r, int g, int b) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = img.createGraphics();
        gg.setColor(new Color(r, g, b));
        gg.fillRect(0, 0, 4, 4);
        gg.dispose();
        return img;
    }

    private static Map<FaceDir, BufferedImage> distinctSix(int base) {
        EnumMap<FaceDir, BufferedImage> m = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) m.put(d, tile(base, d.ordinal(), 0));
        return m;
    }

    @Test
    void canonicalFaceMappingIncludesXAxisMirror() {
        assertEquals(FaceDir.UP,    HeadSkinPacker.headFaceToFaceDir(HeadFace.TOP));
        assertEquals(FaceDir.DOWN,  HeadSkinPacker.headFaceToFaceDir(HeadFace.BOTTOM));
        assertEquals(FaceDir.SOUTH, HeadSkinPacker.headFaceToFaceDir(HeadFace.FRONT));
        assertEquals(FaceDir.NORTH, HeadSkinPacker.headFaceToFaceDir(HeadFace.BACK));
        assertEquals(FaceDir.WEST,  HeadSkinPacker.headFaceToFaceDir(HeadFace.RIGHT));
        assertEquals(FaceDir.EAST,  HeadSkinPacker.headFaceToFaceDir(HeadFace.LEFT));
    }

    @Test
    void idFromHashIsDeterministic() {
        String h = "abc123";
        assertEquals(HeadSkin.idFromHash(h), HeadSkin.idFromHash(h));
    }

    @Test
    void identicalChunksDedupToOneHead() {
        ChunkSpec c1 = new ChunkSpec(new ChunkCoord(0, 0, 0), distinctSix(1));
        ChunkSpec c2 = new ChunkSpec(new ChunkCoord(0, 0, 0), distinctSix(1));
        HeadSkinPacker.Result r = new HeadSkinPacker().pack(List.of(c1, c2));
        assertEquals(1, r.uniqueHeads().size());
        assertSame(r.chunkToHead().get(c1), r.chunkToHead().get(c2));
    }

    @Test
    void differentChunksProduceDifferentHeads() {
        ChunkSpec c1 = new ChunkSpec(new ChunkCoord(0, 0, 0), distinctSix(1));
        ChunkSpec c2 = new ChunkSpec(new ChunkCoord(0, 0, 0), distinctSix(2));
        HeadSkinPacker.Result r = new HeadSkinPacker().pack(List.of(c1, c2));
        assertEquals(2, r.uniqueHeads().size());
    }

    @Test
    void allSixTilesAreDistinctPerFaceNoFiller() {
        Map<FaceDir, BufferedImage> m = distinctSix(1);
        ChunkSpec chunk = new ChunkSpec(new ChunkCoord(3, 2, 2), m);
        HeadSkinPacker.Result r = new HeadSkinPacker().pack(List.of(chunk));
        HeadSkin head = r.uniqueHeads().get(0);
        for (HeadFace hf : HeadFace.values()) {
            assertNotNull(head.tile(hf));
        }
        assertNotSame(head.tile(HeadFace.TOP), head.tile(HeadFace.BOTTOM));
    }

    @Test
    void packProducesStableResultForStoneLikeInput() {
        ChunkSpec fc1 = new ChunkSpec(new ChunkCoord(3, 2, 2), distinctSix(1));
        ChunkSpec fc2 = new ChunkSpec(new ChunkCoord(3, 2, 2), distinctSix(1));
        ChunkSpec co  = new ChunkSpec(new ChunkCoord(0, 0, 0), distinctSix(1));
        HeadSkinPacker.Result r = new HeadSkinPacker().pack(List.of(fc1, fc2, co));
        // All three use the same 6-tile bundle → 1 unique head.
        assertEquals(1, r.uniqueHeads().size());
    }
}
