package dev.twme.sculpt.plugin;

import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import dev.twme.sculpt.util.YamlMigrationSupport;

/** Migrates older flat and mixed config layouts into the current schema. */
public final class SculptConfigMigrator {

    public static final int CURRENT_VERSION = 5;

    private static final List<PathMigration> LEGACY_PATHS = List.of(
        new PathMigration("chunkGridSize", "sculpt.defaultGridSize"),
        new PathMigration("defaultCollisionMode", "sculpt.defaultFillMode"),
        new PathMigration("sculpt.defaultCollisionMode", "sculpt.defaultFillMode"),
        new PathMigration("maxActiveSculptBlocks", "sculpt.maxActiveBlocks"),
        new PathMigration("blockBreakListener.enabled", "sculpt.convertNormalBlocks"),
        new PathMigration("mineskinApiKey", "runtimeBaking.mineskin.apiKey"),
        new PathMigration("mineskinApiUrl", "runtimeBaking.mineskin.apiUrl"),
        new PathMigration("skinUploadBatchDelayMs", "runtimeBaking.upload.batchDelayMs"),
        new PathMigration("skinUploadTimeoutMinutes", "runtimeBaking.upload.timeoutMinutes"),
        new PathMigration("autoSaveIntervalSeconds", "storage.autoSaveIntervalSeconds"),
        new PathMigration("language.auto_detect", "language.autoDetect"),
        new PathMigration("debug.debugTex", "debug.textureMarkers"),
        new PathMigration("blueprint.enable", "blueprint.enabled"),
        new PathMigration("blueprint.consumeItem", "blueprint.consumeItemAfterPaste"),
        new PathMigration("blueprint.storage.maxBlueprintsPerPlayer",
            "blueprint.storage.maxPerPlayer"),
        new PathMigration("blueprint.defaultPasteSettings.rotationVersion",
            "blueprint.pasteDefaults.rotationVersion"),
        new PathMigration("blueprint.defaultPasteSettings.pasteAir",
            "blueprint.pasteDefaults.pasteAir"),
        new PathMigration("blueprint.defaultPasteSettings.overwriteCells",
            "blueprint.pasteDefaults.overwriteCells"),
        new PathMigration("blueprint.defaultPasteSettings.overwriteBlocks",
            "blueprint.pasteDefaults.overwriteBlocks"),
        new PathMigration("blueprint.defaultPasteSettings.adhesive",
            "blueprint.pasteDefaults.adhesive"),
        new PathMigration("blueprint.defaultPasteSettings.rotateMode",
            "blueprint.pasteDefaults.rotateMode"),
        new PathMigration("blueprint.defaultPasteSettings.ry",
            "blueprint.pasteDefaults.rotationY"),
        new PathMigration("blueprint.defaultPasteSettings.flip",
            "blueprint.pasteDefaults.flip"),
        new PathMigration("blueprint.webApi.endpoint", "blueprint.web.apiEndpoint"),
        new PathMigration("blueprint.download.maxDownloadSize",
            "blueprint.download.maxBytes")
    );
    private static final List<String> LEGACY_SECTIONS = List.of(
        "blockBreakListener",
        "blueprint.defaultPasteSettings",
        "blueprint.webApi"
    );
    private static final List<String> REMOVED_PATHS = List.of(
        "sculptTool",
        "sculpt.tool"
    );

    private SculptConfigMigrator() {}

    /**
     * Move legacy values without overwriting values already placed at current paths.
     * Legacy paths are removed so the saved file documents one authoritative schema.
     *
     * @return whether the configuration was changed
     */
    public static boolean migrate(FileConfiguration config) {
        int sourceVersion = config.contains("configVersion", true)
            ? config.getInt("configVersion") : 0;
        if (sourceVersion > CURRENT_VERSION) return false;

        boolean changed = false;
        boolean legacyAutomaticRotation =
            !config.contains("blueprint.defaultPasteSettings.rotationVersion", true);

        for (PathMigration migration : LEGACY_PATHS) {
            if (!config.contains(migration.oldPath(), true)) continue;
            if (legacyAutomaticRotation
                    && migration.oldPath().equals(
                        "blueprint.defaultPasteSettings.rotateMode")
                    && "none".equalsIgnoreCase(
                        config.getString(migration.oldPath()))) {
                config.set(migration.oldPath(), "auto");
            }
            changed |= YamlMigrationSupport.move(
                config, migration.oldPath(), migration.newPath());
        }

        for (String path : REMOVED_PATHS) {
            if (!config.contains(path, true)) continue;
            config.set(path, null);
            changed = true;
        }

        for (String path : LEGACY_SECTIONS) {
            changed |= YamlMigrationSupport.removeEmptySection(config, path);
        }

        if (sourceVersion < CURRENT_VERSION) {
            config.set("configVersion", CURRENT_VERSION);
            changed = true;
        }
        return changed;
    }

    private record PathMigration(String oldPath, String newPath) {}
}
