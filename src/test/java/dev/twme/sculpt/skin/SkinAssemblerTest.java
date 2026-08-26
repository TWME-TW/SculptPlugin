package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.HeadFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinAssemblerTest {

    @BeforeEach @AfterEach
    void resetKnobs() {
        TileRotations.resetAll();
        TileFlips.resetAll();
        FaceDebugTint.setEnabled(false);
    }

    private static BufferedImage solidTile(int size, int rgb) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb, true));
        g.fillRect(0, 0, size, size);
        g.dispose();
        return img;
    }

    @Test
    void assemblesEmptyTilesToTransparentCanvas() throws IOException {
        HeadSkin head = new HeadSkin(HeadSkin.idFromHash("test-empty"), "test-empty", Map.of());
        Path out = Files.createTempFile("sculpt-test-", ".png");
        try {
            new SkinAssembler().assemble(head, out.getParent());
            // The file is named <id>.png; we want the produced PNG, not
            // the temp file we made. Find it next to the temp file.
            Path skin = out.getParent().resolve(head.id() + ".png");
            assertTrue(Files.exists(skin), "assembled PNG should exist: " + skin);
            BufferedImage read = javax.imageio.ImageIO.read(skin.toFile());
            assertEquals(64, read.getWidth());
            assertEquals(64, read.getHeight());
            // All transparent: alpha == 0 at every pixel.
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int a = (read.getRGB(x, y) >>> 24) & 0xFF;
                    assertEquals(0, a, "pixel (" + x + "," + y + ") should be transparent");
                }
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void singleFrontTileLandsInFrontSlot() throws IOException {
        // 4x4 red tile, 64x64 canvas. Red pixels should appear only in
        // HeadFace.FRONT's UV region (8..16, 8..16), after nearest-
        // neighbor upscale from 4x4 to 8x8.
        int red = 0xFFFF0000;
        Map<HeadFace, BufferedImage> tiles = new EnumMap<>(HeadFace.class);
        tiles.put(HeadFace.FRONT, solidTile(4, red));
        HeadSkin head = new HeadSkin(HeadSkin.idFromHash("test-front"), "test-front", tiles);

        Path dir = Files.createTempDirectory("sculpt-test-");
        try {
            new SkinAssembler().assemble(head, dir);
            Path skin = dir.resolve(head.id() + ".png");
            BufferedImage read = javax.imageio.ImageIO.read(skin.toFile());

            // Front slot centre: (12, 12) inside (8..16, 8..16) is red.
            int frontRgb = read.getRGB(12, 12);
            assertEquals(red, frontRgb, "FRONT slot centre should be red");

            // Outside the FRONT slot (e.g. TOP slot centre (12, 4)) should
            // be transparent.
            int topRgb = read.getRGB(12, 4);
            int topA = (topRgb >>> 24) & 0xFF;
            assertEquals(0, topA, "TOP slot centre should be transparent");
        } finally {
            // Best-effort cleanup; the dir may have more than one file in
            // future tests so we walk it.
            Files.walk(dir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    void nearestNeighborScaleUpFillsSlotEvenly() {
        // Build a 4x4 tile where the top-left 2x2 is black and the rest
        // is white. After nearest-neighbor upscale to 8x8, the top-left
        // 4x4 should be black, the bottom-right 4x4 should be white.
        BufferedImage tile = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(Color.BLACK); g.fillRect(0, 0, 2, 2);
        g.setColor(Color.WHITE); g.fillRect(2, 0, 2, 4);
        g.setColor(Color.WHITE); g.fillRect(0, 2, 4, 2);
        g.dispose();

        // Paint the tile into a slot via a HeadSkin so we go through
        // SkinAssembler's nearestNeighborScale path.
        Map<HeadFace, BufferedImage> tiles = new EnumMap<>(HeadFace.class);
        tiles.put(HeadFace.FRONT, tile);
        HeadSkin head = new HeadSkin(HeadSkin.idFromHash("test-nn"), "test-nn", tiles);

        Path dir;
        try {
            dir = Files.createTempDirectory("sculpt-test-");
        } catch (IOException e) { throw new RuntimeException(e); }
        try {
            new SkinAssembler().assemble(head, dir);
            BufferedImage read = javax.imageio.ImageIO.read(dir.resolve(head.id() + ".png").toFile());

            // FRONT slot is at (8..16, 8..16). Top-left 2 source pixels
            // scaled to 4x4 in the slot; bottom-right 2 source pixels
            // scaled to 4x4. Sample a few representative points.
            // Source (0,0) -> scaled (0,0) within slot -> (8,8) on canvas.
            assertEquals(0xFF000000, read.getRGB(8, 8),   "scaled top-left (black)");
            // (11, 11) = slot (3, 3). Nearest-neighbor: sx = 3*4/8 = 1,
            // sy = 1. Source (1, 1) is BLACK (in the top-left 2x2 black
            // region). The remaining 12 source pixels are white.
            assertEquals(0xFF000000, read.getRGB(11, 11), "scaled to source (1,1) black");
            // Source (3,3) is white. Nearest-neighbor with target=8 from src=4
            // maps scaled (7,7) -> source (3,3). Slot-relative (7,7) ->
            // canvas (15,15).
            assertEquals(0xFFFFFFFF, read.getRGB(15, 15), "scaled bottom-right (white)");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try { Files.walk(dir).sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }

    @Test
    void tileRotationsChangesPaintedSlot() throws IOException {
        // Use a 4x4 tile where the top-left pixel is distinct (black) and
        // the rest is red. After 0° rotation the FRONT slot has black at
        // top-left. After 180° rotation the FRONT slot has black at
        // bottom-right (top-left scaled from source bottom-right).
        int red = 0xFFFF0000;
        int black = 0xFF000000;
        BufferedImage tile = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new Color(red, true));   g.fillRect(0, 0, 4, 4);
        g.setColor(new Color(black, true)); g.fillRect(0, 0, 1, 1);  // top-left pixel
        g.dispose();

        Map<HeadFace, BufferedImage> tiles0 = new EnumMap<>(HeadFace.class);
        tiles0.put(HeadFace.FRONT, tile);
        HeadSkin h0 = new HeadSkin(HeadSkin.idFromHash("test-rot0"), "test-rot0", tiles0);

        Map<HeadFace, BufferedImage> tiles180 = new EnumMap<>(HeadFace.class);
        tiles180.put(HeadFace.FRONT, tile);
        HeadSkin h180 = new HeadSkin(HeadSkin.idFromHash("test-rot180"), "test-rot180", tiles180);

        Path dir;
        try {
            dir = Files.createTempDirectory("sculpt-test-");
        } catch (IOException e) { throw new RuntimeException(e); }
        try {
            // Default rotation: 0°. Painted top-left of FRONT slot
            // (canvas 8,8) should be black (from source pixel 0,0
            // upscaled to slot 0,0).
            new SkinAssembler().assemble(h0, dir);
            BufferedImage r0 = javax.imageio.ImageIO.read(dir.resolve(h0.id() + ".png").toFile());
            int p00 = r0.getRGB(8, 8);

            // Rotate 180°: top-left of FRONT slot now comes from source
            // pixel (3,3), which is red. So (8,8) should be red.
            TileRotations.set(HeadFace.FRONT, 180);
            assertNotNull(TileRotations.consumeStale(), "set should mark stale");
            new SkinAssembler().assemble(h180, dir);
            BufferedImage r180 = javax.imageio.ImageIO.read(dir.resolve(h180.id() + ".png").toFile());
            int p11 = r180.getRGB(8, 8);

            assertEquals(black, p00, "no rotation: (8,8) should be black");
            assertEquals(red,  p11, "180° rotation: (8,8) should be red");
        } finally {
            try { Files.walk(dir).sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
    }
}
