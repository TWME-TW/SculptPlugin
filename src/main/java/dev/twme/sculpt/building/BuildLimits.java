package dev.twme.sculpt.building;

import org.bukkit.configuration.ConfigurationSection;

/** Reloadable limits for the building toolkit, read from {@code building.*}. */
public record BuildLimits(
    int maxBlocks,
    long maxCells,
    int maxThickness,
    int maxBrushRadius,
    int historyMaxEntries,
    int historyMaxBlocks,
    long maxTransformVoxels
) {

    public static final int MAX_POINTS = 16;

    static final int DEFAULT_MAX_BLOCKS = 4096;
    static final long DEFAULT_MAX_CELLS = 262_144L;
    static final int DEFAULT_MAX_THICKNESS = 16;
    static final int DEFAULT_MAX_BRUSH_RADIUS = 8;
    static final int DEFAULT_HISTORY_ENTRIES = 30;
    static final int DEFAULT_HISTORY_BLOCKS = 32_768;
    static final long DEFAULT_MAX_TRANSFORM_VOXELS = 2_097_152L;

    public static BuildLimits defaults() {
        return new BuildLimits(DEFAULT_MAX_BLOCKS, DEFAULT_MAX_CELLS,
            DEFAULT_MAX_THICKNESS, DEFAULT_MAX_BRUSH_RADIUS,
            DEFAULT_HISTORY_ENTRIES, DEFAULT_HISTORY_BLOCKS, DEFAULT_MAX_TRANSFORM_VOXELS);
    }

    public static BuildLimits from(final ConfigurationSection root) {
        if (root == null) return defaults();
        return new BuildLimits(
            clamp(root.getInt("building.maxBlocks", DEFAULT_MAX_BLOCKS), 1, 1_000_000),
            Math.max(1L, root.getLong("building.maxCells", DEFAULT_MAX_CELLS)),
            clamp(root.getInt("building.maxThickness", DEFAULT_MAX_THICKNESS), 1, 256),
            clamp(root.getInt("building.brush.maxRadius", DEFAULT_MAX_BRUSH_RADIUS), 0, 64),
            clamp(root.getInt("building.history.maxEntries", DEFAULT_HISTORY_ENTRIES), 1, 1000),
            clamp(root.getInt("building.history.maxBlocks", DEFAULT_HISTORY_BLOCKS), 1, 10_000_000),
            Math.max(4096L, root.getLong("building.maxTransformVoxels", DEFAULT_MAX_TRANSFORM_VOXELS)));
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
