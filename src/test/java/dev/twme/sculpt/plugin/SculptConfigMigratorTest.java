package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SculptConfigMigratorTest {

    @Test
    void migratesEveryLegacySettingAndRemovesOldPaths() {
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("chunkGridSize", 8);
        paths.put("defaultCollisionMode", "barrier");
        paths.put("maxActiveSculptBlocks", 50);
        paths.put("blockBreakListener.enabled", false);
        paths.put("mineskinApiKey", "secret");
        paths.put("mineskinApiUrl", "https://skins.example.test");
        paths.put("skinUploadBatchDelayMs", 1234L);
        paths.put("skinUploadTimeoutMinutes", 7L);
        paths.put("autoSaveIntervalSeconds", 120);
        paths.put("language.auto_detect", false);
        paths.put("debug.debugTex", true);
        paths.put("blueprint.enable", false);
        paths.put("blueprint.consumeItem", true);
        paths.put("blueprint.storage.maxBlueprintsPerPlayer", 20);
        paths.put("blueprint.defaultPasteSettings.rotationVersion", 2);
        paths.put("blueprint.defaultPasteSettings.pasteAir", false);
        paths.put("blueprint.defaultPasteSettings.overwriteCells", false);
        paths.put("blueprint.defaultPasteSettings.overwriteBlocks", false);
        paths.put("blueprint.defaultPasteSettings.adhesive", true);
        paths.put("blueprint.defaultPasteSettings.rotateMode", "player");
        paths.put("blueprint.defaultPasteSettings.ry", 180);
        paths.put("blueprint.defaultPasteSettings.flip", "x");
        paths.put("blueprint.webApi.endpoint", "https://web.example.test/api");
        paths.put("blueprint.download.maxDownloadSize", 4096);

        String[] currentPaths = {
            "sculpt.defaultGridSize", "sculpt.defaultFillMode",
            "sculpt.maxActiveBlocks", "sculpt.convertNormalBlocks",
            "runtimeBaking.mineskin.apiKey", "runtimeBaking.mineskin.apiUrl",
            "runtimeBaking.upload.batchDelayMs", "runtimeBaking.upload.timeoutMinutes",
            "storage.autoSaveIntervalSeconds", "language.autoDetect",
            "debug.textureMarkers", "blueprint.enabled",
            "blueprint.consumeItemAfterPaste", "blueprint.storage.maxPerPlayer",
            "blueprint.pasteDefaults.rotationVersion", "blueprint.pasteDefaults.pasteAir",
            "blueprint.pasteDefaults.overwriteCells",
            "blueprint.pasteDefaults.overwriteBlocks", "blueprint.pasteDefaults.adhesive",
            "blueprint.pasteDefaults.rotateMode", "blueprint.pasteDefaults.rotationY",
            "blueprint.pasteDefaults.flip", "blueprint.web.apiEndpoint",
            "blueprint.download.maxBytes"
        };
        YamlConfiguration yaml = new YamlConfiguration();
        paths.forEach(yaml::set);

        assertTrue(SculptConfigMigrator.migrate(yaml));

        int index = 0;
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            assertEquals(entry.getValue(), yaml.get(currentPaths[index++]));
            assertFalse(yaml.isSet(entry.getKey()), entry.getKey());
        }
        assertFalse(yaml.getKeys(true).contains("blockBreakListener"));
        assertFalse(yaml.getKeys(true).contains("blueprint.defaultPasteSettings"));
        assertFalse(yaml.getKeys(true).contains("blueprint.webApi"));
        assertEquals(5, yaml.getInt("configVersion"));
        assertFalse(SculptConfigMigrator.migrate(yaml));
    }

    @Test
    void removesBothFormerChiselConfigurationPaths() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("configVersion", 4);
        yaml.set("sculptTool", "STICK");
        yaml.set("sculpt.tool", "BLAZE_ROD");

        assertTrue(SculptConfigMigrator.migrate(yaml));
        assertFalse(yaml.contains("sculptTool", true));
        assertFalse(yaml.contains("sculpt.tool", true));
        assertEquals(5, yaml.getInt("configVersion"));
    }

    @Test
    void currentValueWinsOverLegacyValue() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("configVersion", 2);
        yaml.set("chunkGridSize", 16);
        yaml.set("sculpt.defaultGridSize", 4);

        assertTrue(SculptConfigMigrator.migrate(yaml));
        assertEquals(4, yaml.getInt("sculpt.defaultGridSize"));
        assertFalse(yaml.isSet("chunkGridSize"));
    }

    @Test
    void legacyValueOverridesBundledDefaultDuringMigration() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("chunkGridSize", 16);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("configVersion", 2);
        defaults.set("sculpt.defaultGridSize", 2);
        yaml.setDefaults(defaults);

        assertTrue(SculptConfigMigrator.migrate(yaml));
        assertEquals(16, yaml.getInt("sculpt.defaultGridSize"));
        assertEquals(5, yaml.getInt("configVersion"));
        assertFalse(yaml.contains("chunkGridSize", true));
    }

    @Test
    void preRotationVersionNoneMigratesToAutomaticRotation() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blueprint.defaultPasteSettings.rotateMode", "none");

        SculptConfigMigrator.migrate(yaml);

        assertEquals("auto", yaml.getString("blueprint.pasteDefaults.rotateMode"));
    }

    @Test
    void newerSchemaVersionIsNotDowngraded() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("configVersion", 99);
        yaml.set("chunkGridSize", 16);

        assertFalse(SculptConfigMigrator.migrate(yaml));
        assertEquals(99, yaml.getInt("configVersion"));
        assertEquals(16, yaml.getInt("chunkGridSize"));
        assertFalse(yaml.isSet("sculpt.defaultGridSize"));
    }
}
