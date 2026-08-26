package dev.twme.sculpt.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.lang.LanguageManager;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Utility class for the wand selection tool item.
 *
 * <p>The wand is identified by its material ({@link Material#BONE})
 * <strong>and</strong> a PDC marker key {@code sculpt:wand_tool = "true"}.
 * This prevents other bone items from acting as a selection wand.
 */
public final class WandTool {

    /** PDC key used to mark an ItemStack as a wand tool. */
    public static final NamespacedKey WAND_TOOL_KEY =
        new NamespacedKey("sculpt", "wand_tool");

    private WandTool() {
    }

    /**
     * Check whether the given player is holding a wand tool in their main hand.
     *
     * @param player the player to check
     * @return true if the player holds a wand tool
     */
    public static boolean checkPlayer(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.BONE) return false;
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(WAND_TOOL_KEY, PersistentDataType.STRING);
    }

    /**
     * Check whether the given ItemStack is a wand tool.
     *
     * @param item the item to check
     * @return true if the item is a wand tool
     */
    public static boolean isWandTool(final ItemStack item) {
        if (item == null || item.getType() != Material.BONE) return false;
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(WAND_TOOL_KEY, PersistentDataType.STRING);
    }

    /**
     * Create a wand tool ItemStack with translated display name and PDC marker.
     *
     * @param lang the language manager for translation
     * @param player the player whose language to use
     * @return the wand tool ItemStack
     */
    public static ItemStack createTool(final LanguageManager lang, final Player player) {
        final ItemStack item = new ItemStack(Material.BONE);
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        final MiniMessage mm = MiniMessage.miniMessage();
        final String nameStr = lang.getMessage(player, "wand_tool.name");
        meta.displayName(mm.deserialize(nameStr));

        meta.getPersistentDataContainer().set(
            WAND_TOOL_KEY, PersistentDataType.STRING, "true");
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Give a wand tool to a player.
     *
     * @param player the target player
     * @param plugin the plugin instance (for language manager access)
     */
    public static void giveToPlayer(final Player player, final Sculpt plugin) {
        final ItemStack tool = createTool(plugin.getLanguageManager(), player);
        player.getInventory().addItem(tool);
        player.updateInventory();
    }
}
