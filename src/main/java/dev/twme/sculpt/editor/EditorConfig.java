package dev.twme.sculpt.editor;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Reloadable editor settings from {@code editor.*}.
 *
 * @param reach                 cursor reach in blocks
 * @param previewBudget         preview entities allowed per player
 * @param animations            whether previews and results animate
 * @param hudIntervalTicks      ticks between action-bar HUD refreshes
 * @param progressBarThreshold  blocks an operation must touch to show a progress bar
 */
public record EditorConfig(
        double reach,
        int previewBudget,
        boolean animations,
        int hudIntervalTicks,
        int progressBarThreshold
) {

    public static EditorConfig defaults() {
        return new EditorConfig(6.0, 1024, true, 5, 64);
    }

    public static EditorConfig from(final ConfigurationSection root) {
        if (root == null) return defaults();
        return new EditorConfig(
            clamp(root.getDouble("editor.reach", 6.0), 1.0, 32.0),
            (int) clamp(root.getInt("editor.previewBudget", 1024), 64, 16384),
            root.getBoolean("editor.animations", true),
            (int) clamp(root.getInt("editor.hudIntervalTicks", 5), 1, 40),
            (int) clamp(root.getInt("editor.progressBarThreshold", 64), 1, 1_000_000));
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
