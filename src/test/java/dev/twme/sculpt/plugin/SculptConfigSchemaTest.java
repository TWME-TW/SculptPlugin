package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

class SculptConfigSchemaTest {

    private static final Set<String> REQUIRED_LEAVES = Set.of(
        "configVersion",
        "sculpt.defaultGridSize",
        "sculpt.defaultFillMode",
        "sculpt.defaultDisplayMode",
        "sculpt.maxActiveBlocks",
        "sculpt.convertNormalBlocks",
        "controls.doubleTapWindowMs",
        "runtimeBaking.mineskin.apiKey",
        "runtimeBaking.mineskin.apiUrl",
        "runtimeBaking.upload.batchDelayMs",
        "runtimeBaking.upload.timeoutMinutes",
        "storage.autoSaveIntervalSeconds",
        "rendering.textDisplay.maxEntitiesPerBlock",
        "regionOperations.replace.maxVolume",
        "regionOperations.replace.maxGeneratedLeaves",
        "language.default",
        "language.autoDetect",
        "blueprint.enabled",
        "blueprint.consumeItemAfterPaste",
        "blueprint.selection.maxVolume",
        "blueprint.storage.maxPerPlayer",
        "blueprint.storage.maxFolderDepth",
        "blueprint.pasteDefaults.rotationVersion",
        "blueprint.pasteDefaults.pasteAir",
        "blueprint.pasteDefaults.overwriteCells",
        "blueprint.pasteDefaults.overwriteBlocks",
        "blueprint.pasteDefaults.adhesive",
        "blueprint.pasteDefaults.rotateMode",
        "blueprint.pasteDefaults.rotationY",
        "blueprint.pasteDefaults.flip",
        "blueprint.web.apiEndpoint",
        "blueprint.download.allowedDomains",
        "blueprint.download.maxBytes",
        "debug.textureMarkers"
    );

    @Test
    void bundledConfigurationDeclaresCurrentSchema() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
            new File("src/main/resources/config.yml"));

        assertEquals(SculptConfigMigrator.CURRENT_VERSION,
            config.getInt("configVersion"));
        for (String path : REQUIRED_LEAVES) {
            assertTrue(config.getKeys(true).contains(path), path);
        }
        assertEquals(FillMode.SHULKER.id(),
            config.getString("sculpt.defaultFillMode"));
        assertEquals(SculptDisplayMode.AUTO.id(),
            config.getString("sculpt.defaultDisplayMode"));
    }
}
