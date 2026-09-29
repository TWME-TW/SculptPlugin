package dev.twme.sculpt.editor;

import java.util.Locale;

import org.bukkit.Material;

import dev.twme.sculpt.plugin.SculptPermissions;

/** The nine editor tools, in hotbar order. */
public enum ToolId {
    SCULPT(Material.IRON_PICKAXE),
    BRUSH(Material.BRUSH),
    SMOOTH(Material.HONEYCOMB),
    PAINT(Material.BRUSH),
    SELECT(Material.BONE),
    TRANSFORM(Material.COMPASS),
    SHAPE(Material.BLAZE_ROD),
    BLUEPRINT(Material.PAPER),
    SETTINGS(Material.COMPARATOR);

    private final Material icon;

    ToolId(final Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public int slot() {
        return ordinal();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Permission required to use the tool; settings are always available. */
    public String permission() {
        return this == SETTINGS ? null : SculptPermissions.editorTool(id());
    }

    public static ToolId ofSlot(final int slot) {
        final ToolId[] values = values();
        return slot >= 0 && slot < values.length ? values[slot] : SCULPT;
    }
}
