package dev.twme.sculpt.plugin;

import org.bukkit.configuration.file.FileConfiguration;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.skin.SkinUploader;

/**
 * Immutable snapshot of config.yml. Replaced wholesale on /sculpt admin reload —
 * never mutated in place, so callers holding a reference always see a
 * coherent set of values.
 *
 * <p>Per DEVELOPMENT_PLAN.md §6.1.
 */
public record SculptConfig(
        int chunkGridSize,
        int maxActiveSculptBlocks,
        String mineskinApiKey,
        String mineskinApiUrl,
        int autoSaveIntervalSeconds,
        boolean blockBreakListenerEnabled,
        FillMode defaultFillMode,
        SculptDisplayMode defaultDisplayMode,
        int textDisplayMaxEntitiesPerBlock,
        boolean debug,
        long skinUploadBatchDelayMs,
        long skinUploadTimeoutMinutes,
        String languageDefault,
        boolean languageAutoDetect
) {

    public static final String DEFAULT_MINESKIN_API_URL =
            SkinUploader.DEFAULT_API_URL;

    private static final int[] VALID_GRIDS = {1, 2, 4, 8, 16};

    /** Compatibility constructor for callers that predate configurable fill defaults. */
    public SculptConfig(
            int chunkGridSize, int maxActiveSculptBlocks,
            String mineskinApiKey, int autoSaveIntervalSeconds,
            boolean blockBreakListenerEnabled, boolean debug,
            long skinUploadBatchDelayMs, long skinUploadTimeoutMinutes,
            String languageDefault, boolean languageAutoDetect) {
        this(chunkGridSize, maxActiveSculptBlocks, mineskinApiKey,
            DEFAULT_MINESKIN_API_URL,
            autoSaveIntervalSeconds, blockBreakListenerEnabled,
            FillMode.SHULKER, SculptDisplayMode.AUTO, 4096, debug,
            skinUploadBatchDelayMs, skinUploadTimeoutMinutes,
            languageDefault, languageAutoDetect);
    }

    /** Compatibility constructor for the previous canonical record fields. */
    public SculptConfig(
            int chunkGridSize, int maxActiveSculptBlocks,
            String mineskinApiKey, int autoSaveIntervalSeconds,
            boolean blockBreakListenerEnabled, boolean defaultShulkerMode,
            boolean debug, long skinUploadBatchDelayMs,
            long skinUploadTimeoutMinutes, String languageDefault,
            boolean languageAutoDetect) {
        this(chunkGridSize, maxActiveSculptBlocks, mineskinApiKey,
            DEFAULT_MINESKIN_API_URL, autoSaveIntervalSeconds,
            blockBreakListenerEnabled,
            defaultShulkerMode ? FillMode.SHULKER : FillMode.BARRIER,
            SculptDisplayMode.AUTO, 4096, debug,
            skinUploadBatchDelayMs, skinUploadTimeoutMinutes,
            languageDefault, languageAutoDetect);
    }

    /** Compatibility constructor for the previous canonical record fields. */
    public SculptConfig(
            int chunkGridSize, int maxActiveSculptBlocks,
            String mineskinApiKey, String mineskinApiUrl,
            int autoSaveIntervalSeconds, boolean blockBreakListenerEnabled,
            boolean defaultShulkerMode, boolean debug,
            long skinUploadBatchDelayMs, long skinUploadTimeoutMinutes,
            String languageDefault, boolean languageAutoDetect) {
        this(chunkGridSize, maxActiveSculptBlocks, mineskinApiKey,
            mineskinApiUrl, autoSaveIntervalSeconds, blockBreakListenerEnabled,
            defaultShulkerMode ? FillMode.SHULKER : FillMode.BARRIER,
            SculptDisplayMode.AUTO, 4096, debug,
            skinUploadBatchDelayMs, skinUploadTimeoutMinutes,
            languageDefault, languageAutoDetect);
    }

    public SculptConfig {
        if (!isValidGrid(chunkGridSize)) {
            throw new IllegalArgumentException(
                    "sculpt.defaultGridSize must be one of 1, 2, 4, 8, 16; got "
                        + chunkGridSize);
        }
        if (maxActiveSculptBlocks < -1 || maxActiveSculptBlocks == 0) {
            throw new IllegalArgumentException(
                    "sculpt.maxActiveBlocks must be a positive integer or -1"
                        + " (unlimited); got " + maxActiveSculptBlocks);
        }
        if (mineskinApiKey == null) {
            mineskinApiKey = "";
        }
        mineskinApiUrl = SkinUploader.normalizeApiUrl(mineskinApiUrl);
        if (autoSaveIntervalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "storage.autoSaveIntervalSeconds must be positive; got "
                        + autoSaveIntervalSeconds);
        }
        if (defaultFillMode == null) defaultFillMode = FillMode.SHULKER;
        if (defaultDisplayMode == null) defaultDisplayMode = SculptDisplayMode.AUTO;
        if (textDisplayMaxEntitiesPerBlock < 1) {
            textDisplayMaxEntitiesPerBlock = 4096;
        }
        if (skinUploadBatchDelayMs < 0) {
            skinUploadBatchDelayMs = 6000;
        }
        if (skinUploadTimeoutMinutes < 1) {
            skinUploadTimeoutMinutes = 10;
        }
        if (languageDefault == null || languageDefault.isBlank()) {
            languageDefault = "en_us";
        }
    }

    public boolean isValid() {
        return isValidGrid(chunkGridSize);
    }

    private static boolean isValidGrid(int grid) {
        for (int v : VALID_GRIDS) {
            if (grid == v) return true;
        }
        return false;
    }

    /**
     * Parse a {@link SculptConfig} from the on-disk FileConfiguration. All
     * fields have sensible defaults so missing keys don't crash the plugin.
     */
    public static SculptConfig from(FileConfiguration cfg) {
        int grid = cfg.getInt("sculpt.defaultGridSize", 2);
        int maxBlocks = cfg.getInt("sculpt.maxActiveBlocks", -1);
        if (maxBlocks == 0) maxBlocks = -1; // treat 0 as unlimited
        String apiKey = cfg.getString("runtimeBaking.mineskin.apiKey", "");
        String apiUrl = cfg.getString(
                "runtimeBaking.mineskin.apiUrl", DEFAULT_MINESKIN_API_URL);
        int autoSave = cfg.getInt("storage.autoSaveIntervalSeconds", 300);
        if (autoSave <= 0) autoSave = 300;
        boolean bblEnabled = cfg.getBoolean("sculpt.convertNormalBlocks", true);
        FillMode defaultFillMode = FillMode.parse(
            cfg.getString("sculpt.defaultFillMode", "shulker"), FillMode.SHULKER);
        SculptDisplayMode defaultDisplayMode = SculptDisplayMode.parse(
            cfg.getString("sculpt.defaultDisplayMode", "auto"), SculptDisplayMode.AUTO);
        int maxTextDisplays = cfg.getInt(
            "rendering.textDisplay.maxEntitiesPerBlock", 4096);
        boolean debug = cfg.getBoolean("debug.textureMarkers", false);
        long batchDelay = cfg.getLong("runtimeBaking.upload.batchDelayMs", 6000L);
        long timeoutMin = cfg.getLong("runtimeBaking.upload.timeoutMinutes", 10L);

        String langDefault = cfg.getString("language.default", "en_us");
        boolean langAutoDetect = cfg.getBoolean("language.autoDetect", true);

        return new SculptConfig(grid, maxBlocks, apiKey, apiUrl,
                autoSave, bblEnabled, defaultFillMode, defaultDisplayMode,
                maxTextDisplays, debug, batchDelay, timeoutMin,
                langDefault, langAutoDetect);
    }

    /** Compatibility view for integrations that still ask a boolean question. */
    public boolean defaultShulkerMode() {
        return defaultFillMode == FillMode.SHULKER;
    }

    static boolean defaultShulkerMode(FileConfiguration cfg) {
        return FillMode.parse(cfg.getString("sculpt.defaultFillMode",
            cfg.getString("sculpt.defaultCollisionMode", "shulker")),
            FillMode.SHULKER) == FillMode.SHULKER;
    }
}
