package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

class SculptConfigTest {

    @Test
    void validGridSizesAreAccepted() {
        for (int grid : new int[]{1, 2, 4, 8, 16}) {
            SculptConfig cfg = config(grid, 16, 300);
            assertEquals(grid, cfg.chunkGridSize());
            assertTrue(cfg.isValid());
        }
    }

    @Test
    void invalidGridSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(3, 16, 300));
        assertThrows(IllegalArgumentException.class, () -> config(0, 16, 300));
        assertThrows(IllegalArgumentException.class, () -> config(7, 16, 300));
    }

    @Test
    void nullApiKeyFallsBackToEmptyString() {
        SculptConfig cfg = new SculptConfig(
            4, 16, null, 300, true, false,
            6000L, 10L, "en_us", true);
        assertEquals("", cfg.mineskinApiKey());
    }

    @Test
    void mineskinApiUrlDefaultsToOfficialEndpoint() {
        assertEquals(SculptConfig.DEFAULT_MINESKIN_API_URL,
            config(4, 16, 300).mineskinApiUrl());
    }

    @Test
    void configuredMineskinApiUrlIsNormalized() {
        SculptConfig cfg = new SculptConfig(
            4, 16, "api-key", "https://skins.example.test/mineskin/",
            300, true, true, false, 6000L, 10L, "en_us", true);

        assertEquals("https://skins.example.test/mineskin", cfg.mineskinApiUrl());
    }

    @Test
    void invalidMineskinApiUrlIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new SculptConfig(
                4, 16, "api-key", "file:///tmp/mineskin",
                300, true, true, false, 6000L, 10L, "en_us", true));
    }

    @Test
    void invalidMaxBlocksIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(4, 0, 300));
        assertThrows(IllegalArgumentException.class, () -> config(4, -2, 300));
    }

    @Test
    void unlimitedMaxBlocksIsAccepted() {
        assertEquals(-1, config(4, -1, 300).maxActiveSculptBlocks());
    }

    @Test
    void nonPositiveAutoSaveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(4, 16, 0));
    }

    @Test
    void configRecordsAllFields() {
        SculptConfig cfg = new SculptConfig(
            8, 32, "apikey-123", 600, false, true,
            6000L, 10L, "en_us", true);
        assertEquals(8, cfg.chunkGridSize());
        assertEquals(32, cfg.maxActiveSculptBlocks());
        assertEquals("apikey-123", cfg.mineskinApiKey());
        assertEquals(SculptConfig.DEFAULT_MINESKIN_API_URL, cfg.mineskinApiUrl());
        assertEquals(600, cfg.autoSaveIntervalSeconds());
        assertFalse(cfg.blockBreakListenerEnabled());
        assertEquals(FillMode.SHULKER, cfg.defaultFillMode());
        assertEquals(SculptDisplayMode.AUTO, cfg.defaultDisplayMode());
        assertTrue(cfg.debug());
        assertEquals("en_us", cfg.languageDefault());
        assertTrue(cfg.languageAutoDetect());
    }

    @Test
    void configIsImmutableRecord() {
        SculptConfig a = config(4, 16, 300);
        SculptConfig b = config(4, 16, 300);
        assertEquals(a, b);
        assertNotNull(a.toString());
    }

    @Test
    void blockConversionIsEnabledByDefaultConstructor() {
        assertTrue(config(4, 16, 300).blockBreakListenerEnabled());
    }

    @Test
    void shulkerAndAutomaticRenderingAreDefaultStrategies() {
        SculptConfig config = SculptConfig.from(new YamlConfiguration());

        assertTrue(SculptConfig.defaultShulkerMode(new YamlConfiguration()));
        assertEquals(FillMode.SHULKER, config.defaultFillMode());
        assertEquals(SculptDisplayMode.AUTO, config.defaultDisplayMode());
        assertEquals(SculptConfig.DEFAULT_DOUBLE_TAP_WINDOW_MS,
            config.doubleTapWindowMs());
    }

    @Test
    void invalidDoubleTapWindowFallsBackToDefault() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("controls.doubleTapWindowMs", 10);

        assertEquals(SculptConfig.DEFAULT_DOUBLE_TAP_WINDOW_MS,
            SculptConfig.from(yaml).doubleTapWindowMs());
    }

    @Test
    void barrierCanBeConfiguredAsDefaultFill() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("sculpt.defaultFillMode", "barrier");

        assertFalse(SculptConfig.defaultShulkerMode(yaml));
    }

    @Test
    void invalidLegacyCollisionModeFallsBackToShulker() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("sculpt.defaultCollisionMode", "invalid");

        assertTrue(SculptConfig.defaultShulkerMode(yaml));
    }

    @Test
    void currentSchemaIsParsedFromStructuredPaths() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("sculpt.defaultGridSize", 8);
        yaml.set("sculpt.maxActiveBlocks", 24);
        yaml.set("sculpt.convertNormalBlocks", false);
        yaml.set("sculpt.defaultFillMode", "barrier");
        yaml.set("sculpt.defaultDisplayMode", "textdisplay");
        yaml.set("rendering.textDisplay.maxEntitiesPerBlock", 2048);
        yaml.set("runtimeBaking.mineskin.apiKey", "key");
        yaml.set("runtimeBaking.mineskin.apiUrl", "https://skins.example.test/");
        yaml.set("runtimeBaking.upload.batchDelayMs", 1200L);
        yaml.set("runtimeBaking.upload.timeoutMinutes", 5L);
        yaml.set("storage.autoSaveIntervalSeconds", 90);
        yaml.set("controls.doubleTapWindowMs", 450);
        yaml.set("language.default", "zh_tw");
        yaml.set("language.autoDetect", false);
        yaml.set("debug.textureMarkers", true);

        SculptConfig config = SculptConfig.from(yaml);

        assertEquals(8, config.chunkGridSize());
        assertEquals(24, config.maxActiveSculptBlocks());
        assertFalse(config.blockBreakListenerEnabled());
        assertFalse(config.defaultShulkerMode());
        assertEquals(SculptDisplayMode.TEXT_DISPLAY, config.defaultDisplayMode());
        assertEquals(2048, config.textDisplayMaxEntitiesPerBlock());
        assertEquals("key", config.mineskinApiKey());
        assertEquals("https://skins.example.test", config.mineskinApiUrl());
        assertEquals(1200L, config.skinUploadBatchDelayMs());
        assertEquals(5L, config.skinUploadTimeoutMinutes());
        assertEquals(90, config.autoSaveIntervalSeconds());
        assertEquals(450, config.doubleTapWindowMs());
        assertEquals("zh_tw", config.languageDefault());
        assertFalse(config.languageAutoDetect());
        assertTrue(config.debug());
    }

    private static SculptConfig config(
            final int grid,
            final int maxBlocks,
            final int autoSaveSeconds) {
        return new SculptConfig(
            grid, maxBlocks, "", autoSaveSeconds, true, false,
            6000L, 10L, "en_us", true);
    }
}
