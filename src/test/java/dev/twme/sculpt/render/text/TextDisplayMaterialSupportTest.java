package dev.twme.sculpt.render.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.core.BlockKey;

class TextDisplayMaterialSupportTest {

    private static final String VERSION = "26.2";

    @Test
    void acceptsCubeAllAndEquivalentExplicitFullCubeModels(
            @TempDir final Path cacheRoot) throws IOException {
        writeCoreModels(cacheRoot);
        writeBlockstate(cacheRoot, "weathered_copper_grate",
            "minecraft:block/weathered_copper_grate");
        writeAsset(cacheRoot, "models/block/weathered_copper_grate.json", """
            {
              "parent": "minecraft:block/cube_all",
              "textures": {"all": "minecraft:block/weathered_copper_grate"}
            }
            """);
        writeTexture(cacheRoot,
            "textures/block/weathered_copper_grate.png", 0x80A5745B);

        writeBlockstate(cacheRoot, "oak_leaves", "minecraft:block/oak_leaves");
        writeAsset(cacheRoot, "models/block/oak_leaves.json", """
            {
              "parent": "minecraft:block/leaves",
              "textures": {"all": "minecraft:block/oak_leaves"}
            }
            """);
        writeAsset(cacheRoot, "models/block/leaves.json", """
            {
              "parent": "minecraft:block/block",
              "elements": [{
                "from": [0, 0, 0], "to": [16, 16, 16],
                "faces": {
                  "down":  {"texture": "#all", "tintindex": 0},
                  "up":    {"texture": "#all", "tintindex": 0},
                  "north": {"texture": "#all", "tintindex": 0},
                  "south": {"texture": "#all", "tintindex": 0},
                  "west":  {"texture": "#all", "tintindex": 0},
                  "east":  {"texture": "#all", "tintindex": 0}
                }
              }]
            }
            """);
        writeTexture(cacheRoot, "textures/block/oak_leaves.png", 0x8080A755);

        writeBlockstate(cacheRoot, "stone", "minecraft:block/stone");
        writeAsset(cacheRoot, "models/block/stone.json", """
            {
              "parent": "minecraft:block/cube_all",
              "textures": {"all": "minecraft:block/stone"}
            }
            """);
        writeTexture(cacheRoot, "textures/block/stone.png", 0xFF7F7F7F);

        final TextDisplayMaterialSupport support = support(cacheRoot);

        assertEquals(TextDisplayMaterialSupport.Status.SUPPORTED,
            support.status(BlockKey.of("minecraft:weathered_copper_grate"), true));
        assertEquals(TextDisplayMaterialSupport.Status.SUPPORTED,
            support.status(BlockKey.of("minecraft:oak_leaves"), true));
        assertEquals(TextDisplayMaterialSupport.ModelStatus.TRANSPARENT,
            support.modelStatus(
                BlockKey.of("minecraft:weathered_copper_grate"), false));
        assertEquals(TextDisplayMaterialSupport.ModelStatus.TRANSPARENT,
            support.modelStatus(BlockKey.of("minecraft:oak_leaves"), false));
        assertEquals(TextDisplayMaterialSupport.ModelStatus.OPAQUE,
            support.modelStatus(BlockKey.of("minecraft:stone"), true));
    }

    @Test
    void rejectsModelsWithoutACompleteSixFaceCube(
            @TempDir final Path cacheRoot) throws IOException {
        writeAsset(cacheRoot, "models/block/block.json", "{}");
        writeBlockstate(cacheRoot, "oak_stairs", "minecraft:block/oak_stairs");
        writeAsset(cacheRoot, "models/block/oak_stairs.json", """
            {
              "parent": "minecraft:block/block",
              "elements": [{
                "from": [0, 0, 0], "to": [16, 8, 16],
                "faces": {"up": {"texture": "minecraft:block/oak_planks"}}
              }]
            }
            """);

        final TextDisplayMaterialSupport support = support(cacheRoot);
        final BlockKey stairs = BlockKey.of("minecraft:oak_stairs");

        assertEquals(TextDisplayMaterialSupport.Status.UNKNOWN,
            support.status(stairs, false));
        assertEquals(TextDisplayMaterialSupport.Status.UNSUPPORTED,
            support.status(stairs, true));
    }

    @Test
    void reportsLoadingWithoutBlockingTheCallingThread(
            @TempDir final Path cacheRoot) throws IOException {
        writeCoreModels(cacheRoot);
        writeBlockstate(cacheRoot, "glass", "minecraft:block/glass");
        writeAsset(cacheRoot, "models/block/glass.json", """
            {
              "parent": "minecraft:block/cube_all",
              "textures": {"all": "minecraft:block/glass"}
            }
            """);
        writeTexture(cacheRoot, "textures/block/glass.png", 0x80FFFFFF);
        final List<Runnable> queued = new ArrayList<>();
        final TextDisplayMaterialSupport support = support(
            cacheRoot, queued::add);
        final BlockKey glass = BlockKey.of("minecraft:glass");

        assertEquals(TextDisplayMaterialSupport.Status.LOADING,
            support.status(glass, true));
        assertEquals(TextDisplayMaterialSupport.ModelStatus.LOADING,
            support.modelStatus(glass, false));
        assertTrue(
            support.status(glass, false).allowsOperation(),
            "an in-flight model must not make the player repeat the operation");
        final var renderModel = support.resolve(glass);
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertTrue(renderModel.join().isPresent(),
            "material admission and rendering must share one resolution future");
        assertEquals(TextDisplayMaterialSupport.Status.SUPPORTED,
            support.status(glass, false));
        assertEquals(TextDisplayMaterialSupport.ModelStatus.TRANSPARENT,
            support.resolveStatus(glass).join());
        assertTrue(
            support.status(glass, false).allowsOperation());
    }

    @Test
    void onlyLoadingAndSupportedStatusesAllowOperations() {
        assertFalse(
            TextDisplayMaterialSupport.Status.UNKNOWN.allowsOperation());
        assertTrue(
            TextDisplayMaterialSupport.Status.LOADING.allowsOperation());
        assertTrue(
            TextDisplayMaterialSupport.Status.SUPPORTED.allowsOperation());
        assertFalse(
            TextDisplayMaterialSupport.Status.UNSUPPORTED.allowsOperation());
    }

    private static TextDisplayMaterialSupport support(final Path cacheRoot) {
        return support(cacheRoot, Runnable::run);
    }

    private static TextDisplayMaterialSupport support(
            final Path cacheRoot,
            final Executor executor) {
        return new TextDisplayMaterialSupport(
            new McAssetClient(cacheRoot), VERSION,
            java.util.logging.Logger.getAnonymousLogger(), executor);
    }

    private static void writeCoreModels(final Path cacheRoot) throws IOException {
        writeAsset(cacheRoot, "models/block/block.json", "{}");
        writeAsset(cacheRoot, "models/block/cube_all.json", """
            {
              "parent": "minecraft:block/cube",
              "textures": {
                "down": "#all", "up": "#all", "north": "#all",
                "south": "#all", "west": "#all", "east": "#all"
              }
            }
            """);
        writeAsset(cacheRoot, "models/block/cube.json", """
            {
              "parent": "minecraft:block/block",
              "elements": [{
                "from": [0, 0, 0], "to": [16, 16, 16],
                "faces": {
                  "down": {"texture": "#down"}, "up": {"texture": "#up"},
                  "north": {"texture": "#north"}, "south": {"texture": "#south"},
                  "west": {"texture": "#west"}, "east": {"texture": "#east"}
                }
              }]
            }
            """);
    }

    private static void writeBlockstate(
            final Path cacheRoot,
            final String block,
            final String model) throws IOException {
        writeAsset(cacheRoot, "blockstates/" + block + ".json", """
            {"variants": {"": {"model": "%s"}}}
            """.formatted(model));
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
        ImageIO.write(image, "png", target.toFile());
    }
}
