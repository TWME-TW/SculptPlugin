package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.lang.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Items for the building toolkit. Like the selection wand, each tool is
 * identified by its material <strong>and</strong> a PDC marker, so ordinary
 * blaze rods and brushes keep their vanilla behavior.
 */
public final class BuildTools {

    public enum Tool {
        /** Places control points for planes, surfaces, curves, and solids. */
        BUILDER(Material.BLAZE_ROD, "builder"),
        /** Sculpts, smooths, and paints cells around the cursor. */
        BRUSH(Material.BRUSH, "brush");

        private final Material material;
        private final String id;

        Tool(final Material material, final String id) {
            this.material = material;
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public static final NamespacedKey TOOL_KEY = new NamespacedKey("sculpt", "build_tool");

    private BuildTools() {}

    /** The toolkit tool represented by {@code item}, or {@code null}. */
    public static Tool toolOf(final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        final String id = meta.getPersistentDataContainer()
            .get(TOOL_KEY, PersistentDataType.STRING);
        if (id == null) return null;
        for (final Tool tool : Tool.values()) {
            if (tool.id.equals(id) && tool.material == item.getType()) return tool;
        }
        return null;
    }

    public static boolean isTool(final ItemStack item) {
        return toolOf(item) != null;
    }

    public static Tool heldTool(final Player player) {
        return toolOf(player.getInventory().getItemInMainHand());
    }

    public static ItemStack create(
            final Tool tool,
            final LanguageManager lang,
            final Player player) {
        final ItemStack item = new ItemStack(tool.material);
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        final MiniMessage mini = MiniMessage.miniMessage();
        final String prefix = "building.tool." + tool.id;
        meta.displayName(mini.deserialize(lang.getMessage(player, prefix + ".name")));
        final List<Component> lore = new ArrayList<>();
        for (int line = 1; line <= 4; line++) {
            final String key = prefix + ".lore" + line;
            final String text = lang.getMessage(player, key);
            if (text == null || text.isBlank() || text.equals(key)) continue;
            lore.add(mini.deserialize(text));
        }
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(TOOL_KEY, PersistentDataType.STRING, tool.id);
        item.setItemMeta(meta);
        return item;
    }
}
