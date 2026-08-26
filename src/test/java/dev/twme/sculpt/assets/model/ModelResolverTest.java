package dev.twme.sculpt.assets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.FaceDir;

class ModelResolverTest {

    private static final String VERSION = "26.2";

    @Test
    void textureSlotSupportsLegacyStringsAndSpriteObjects() {
        assertEquals("#all", ModelResolver.textureSlot(
            JsonParser.parseString("\"#all\"")));
        assertEquals("minecraft:block/orange_stained_glass",
            ModelResolver.textureSlot(JsonParser.parseString("""
                {
                  "force_translucent": true,
                  "sprite": "minecraft:block/orange_stained_glass"
                }
                """)));
        assertNull(ModelResolver.textureSlot(
            JsonParser.parseString("{\"force_translucent\":true}")));
    }

    @Test
    void minecraft26SpriteObjectResolvesTransparentStainedGlass(
            @TempDir final Path cacheRoot) throws IOException {
        writeAsset(cacheRoot, "blockstates/orange_stained_glass.json", """
            {
              "variants": {
                "": {"model": "minecraft:block/orange_stained_glass"}
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/orange_stained_glass.json", """
            {
              "parent": "minecraft:block/cube_all",
              "textures": {
                "all": {
                  "force_translucent": true,
                  "sprite": "minecraft:block/orange_stained_glass"
                }
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/cube_all.json", """
            {
              "parent": "minecraft:block/cube",
              "textures": {
                "down": "#all",
                "up": "#all",
                "north": "#all",
                "south": "#all",
                "west": "#all",
                "east": "#all"
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/cube.json", """
            {
              "elements": [{
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {
                  "down": {"texture": "#down"},
                  "up": {"texture": "#up"},
                  "north": {"texture": "#north"},
                  "south": {"texture": "#south"},
                  "west": {"texture": "#west"},
                  "east": {"texture": "#east"}
                }
              }]
            }
            """);
        writeTexture(cacheRoot, "textures/block/orange_stained_glass.png",
            0x80F9801D);

        final McAssetClient assets = new McAssetClient(cacheRoot);
        final BlockKey key = BlockKey.of("minecraft:orange_stained_glass");
        final ModelResolver textDisplayResolver = new ModelResolver(
            assets, Logger.getAnonymousLogger(), VERSION, true);

        final Optional<BlockModel> resolved = textDisplayResolver.resolve(key);

        assertTrue(resolved.isPresent());
        assertTrue(resolved.orElseThrow().transparent());
        final BufferedImage north = resolved.orElseThrow().face(FaceDir.NORTH);
        assertEquals(16, north.getWidth());
        assertEquals(0x80, north.getRGB(0, 0) >>> 24,
            "TextDisplay resolution must preserve the stained-glass alpha");

        final ModelResolver headResolver = new ModelResolver(
            assets, Logger.getAnonymousLogger(), VERSION, false);
        assertFalse(headResolver.resolve(key).isPresent(),
            "transparent texture objects remain unsupported by head baking");
    }

    @Test
    void slabUsesItsVanillaDoubleModelAsTheCellMaterial(
            @TempDir final Path cacheRoot) throws IOException {
        writeAsset(cacheRoot, "blockstates/smooth_stone_slab.json", """
            {
              "variants": {
                "type=bottom": {"model": "minecraft:block/missing_bottom_slab"},
                "type=double": {"model": "minecraft:block/smooth_stone_slab_double"},
                "type=top": {"model": "minecraft:block/missing_top_slab"}
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/smooth_stone_slab_double.json", """
            {
              "parent": "minecraft:block/cube_column",
              "textures": {
                "end": "minecraft:block/smooth_stone",
                "side": "minecraft:block/smooth_stone_slab_side"
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/cube_column.json", """
            {
              "parent": "minecraft:block/cube",
              "textures": {
                "down": "#end",
                "up": "#end",
                "north": "#side",
                "south": "#side",
                "west": "#side",
                "east": "#side"
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/cube.json", """
            {
              "elements": [{
                "from": [0, 0, 0],
                "to": [16, 16, 16],
                "faces": {
                  "down": {"texture": "#down"},
                  "up": {"texture": "#up"},
                  "north": {"texture": "#north"},
                  "south": {"texture": "#south"},
                  "west": {"texture": "#west"},
                  "east": {"texture": "#east"}
                }
              }]
            }
            """);
        writeTexture(cacheRoot, "textures/block/smooth_stone.png", 0xFFCCDDEE);
        writeTexture(cacheRoot, "textures/block/smooth_stone_slab_side.png",
            0xFF334455);

        final ModelResolver resolver = new ModelResolver(
            new McAssetClient(cacheRoot), Logger.getAnonymousLogger(), VERSION);

        final BlockModel model = resolver.resolve(
            BlockKey.of("minecraft:smooth_stone_slab")).orElseThrow();

        assertEquals(0xFFCCDDEE, model.face(FaceDir.UP).getRGB(0, 0));
        assertEquals(0xFF334455, model.face(FaceDir.NORTH).getRGB(0, 0));
    }

    private static void writeAsset(
            final Path cacheRoot,
            final String assetPath,
            final String content) throws IOException {
        final Path target = cacheRoot.resolve(VERSION).resolve(assetPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static void writeTexture(
            final Path cacheRoot,
            final String assetPath,
            final int argb) throws IOException {
        final Path target = cacheRoot.resolve(VERSION).resolve(assetPath);
        Files.createDirectories(target.getParent());
        final BufferedImage image = new BufferedImage(
            16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb);
            }
        }
        assertTrue(ImageIO.write(image, "png", target.toFile()));
    }
}
