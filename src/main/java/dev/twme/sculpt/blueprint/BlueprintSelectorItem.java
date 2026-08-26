package dev.twme.sculpt.blueprint;

import java.util.UUID;

import javax.annotation.Nullable;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * BlueprintSelectorItem — 藍圖選取工具與藍圖物品的工具類別。
 *
 * <p>選取工具：BLAZE_ROD + PDC {@code sculpt:blueprint_selector = "true"}。
 * 左鍵點擊 SculptBlock 選取，右鍵將選取的 SculptBlock 作為隱式藍圖貼上。
 *
 * <p>藍圖綁定物品：任何物品 + PDC {@code sculpt:blueprint_id = <uuid>}。
 * 可在 PlayerInteractEvent 中攔截右鍵以執行貼上。
 */
public final class BlueprintSelectorItem {

    /** PDC key 用於標記藍圖選取工具。 */
    public static final NamespacedKey SELECTOR_KEY =
        new NamespacedKey("sculpt", "blueprint_selector");

    /** PDC key 用於標記藍圖 ID 綁定。 */
    public static final NamespacedKey BOUND_KEY =
        new NamespacedKey("sculpt", "blueprint_id");

    private BlueprintSelectorItem() {}

    /**
     * 檢查物品是否為藍圖選取工具。
     * 條件：BLAZE_ROD + PDC sculpt:blueprint_selector = "true"
     *
     * @param item 要檢查的物品
     * @return 若為選取工具則 true
     */
    public static boolean isSelectorTool(@Nullable ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "true".equals(
            meta.getPersistentDataContainer().get(SELECTOR_KEY, PersistentDataType.STRING));
    }

    /**
     * 檢查物品是否已綁定藍圖（任何物品含 PDC sculpt:blueprint_id）。
     * @param item 要檢查的物品
     * @return 若已綁定藍圖則 true
     */
    public static boolean isBoundItem(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(BOUND_KEY, PersistentDataType.STRING);
    }

    /**
     * 從物品的 PDC 讀取綁定的藍圖 UUID。
     *
     * @param item 藍圖綁定物品
     * @return 藍圖 UUID，若不存在則 null
     */
    @Nullable
    public static UUID getBlueprintId(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer()
            .get(BOUND_KEY, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 從物品 PDC 讀取貼上設定。
     * 若物品無對應 PDC 則回傳 PasteSettings.DEFAULTS。
     *
     * @param item 藍圖綁定物品
     * @return 貼上設定
     */
    public static PasteSettings getPasteSettings(@Nullable ItemStack item) {
        return getPasteSettings(item, PasteSettings.DEFAULTS);
    }

    /** Resolve item-level overrides on top of the player's configured defaults. */
    public static PasteSettings getPasteSettings(@Nullable ItemStack item, PasteSettings defaults) {
        if (item == null || !item.hasItemMeta()) return defaults;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return defaults;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        Boolean pasteAir = getPdcBoolean(pdc, "paste_air");
        Boolean overwrite = getPdcBoolean(pdc, "overwrite");
        Boolean adhesive = getPdcBoolean(pdc, "adhesive");
        String rotateMode = getPdcString(pdc, "rotate_mode");
        Integer ry = getPdcInt(pdc, "ry");
        String flip = getPdcString(pdc, "flip");

        PasteSettings.RotateMode mode = defaults.rotateMode();
        if (rotateMode != null) {
            try {
                mode = PasteSettings.RotateMode.valueOf(
                    rotateMode.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }
        return new PasteSettings(
            pasteAir != null ? pasteAir : defaults.pasteAir(),
            overwrite != null ? overwrite : defaults.overwriteCells(),
            overwrite != null ? overwrite : defaults.overwriteBlocks(),
            adhesive != null ? adhesive : defaults.adhesive(),
            mode,
            ry != null ? ry : defaults.ry(),
            flip != null ? flip : defaults.flipAxis());
    }

    // ====================== PDC 輔助 ======================

    @Nullable
    private static Boolean getPdcBoolean(PersistentDataContainer pdc, String key) {
        NamespacedKey nsk = new NamespacedKey("sculpt", key);
        if (pdc.has(nsk, PersistentDataType.BOOLEAN))
            return pdc.get(nsk, PersistentDataType.BOOLEAN);
        if (pdc.has(nsk, PersistentDataType.STRING))
            return Boolean.parseBoolean(pdc.get(nsk, PersistentDataType.STRING));
        return null;
    }

    @Nullable
    private static String getPdcString(PersistentDataContainer pdc, String key) {
        NamespacedKey nsk = new NamespacedKey("sculpt", key);
        if (pdc.has(nsk, PersistentDataType.STRING))
            return pdc.get(nsk, PersistentDataType.STRING);
        return null;
    }

    @Nullable
    private static Integer getPdcInt(PersistentDataContainer pdc, String key) {
        NamespacedKey nsk = new NamespacedKey("sculpt", key);
        if (pdc.has(nsk, PersistentDataType.INTEGER))
            return pdc.get(nsk, PersistentDataType.INTEGER);
        return null;
    }
}
