package dev.twme.sculpt.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.core.FaceDir;

class TextureSplitterTest {

    @Test
    void splitsOnlyRequestedCells() {
        TextureSplitter splitter = new TextureSplitter();
        List<ChunkSpec> chunks = splitter.split(model(), 4, List.of(
                new ChunkCoord(2, 0, 2),
                new ChunkCoord(3, 1, 3),
                new ChunkCoord(2, 0, 2)));

        assertEquals(List.of(
                new ChunkCoord(2, 0, 2),
                new ChunkCoord(3, 1, 3)),
                chunks.stream().map(ChunkSpec::coord).toList());
    }

    @Test
    void rejectsRequestedCellOutsideGrid() {
        TextureSplitter splitter = new TextureSplitter();
        assertThrows(IllegalArgumentException.class, () ->
                splitter.split(model(), 4, List.of(new ChunkCoord(4, 0, 0))));
    }

    @Test
    void preparedGridMatchesDirectSplit() {
        TextureSplitter splitter = new TextureSplitter();
        ChunkCoord coordinate = new ChunkCoord(2, 1, 3);
        ChunkSpec direct = splitter.split(model(), 4, List.of(coordinate)).getFirst();
        ChunkSpec prepared = splitter.prepare(model(), 4).cell(coordinate);

        assertEquals(direct.coord(), prepared.coord());
        for (FaceDir face : FaceDir.values()) {
            BufferedImage expected = direct.tile(face);
            BufferedImage actual = prepared.tile(face);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    assertEquals(expected.getRGB(x, y), actual.getRGB(x, y));
                }
            }
        }
    }

    private static BlockModel model() {
        EnumMap<FaceDir, BufferedImage> faces = new EnumMap<>(FaceDir.class);
        for (FaceDir dir : FaceDir.values()) {
            faces.put(dir, new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        }
        return new BlockModel(
                new BlockKey("minecraft", "stone"),
                faces,
                EnumSet.noneOf(FaceDir.class),
                Map.of(),
                "minecraft:block/cube_all",
                Map.of());
    }
}
