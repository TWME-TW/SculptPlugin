package dev.twme.sculpt.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.skin.HeadsRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 頭顱瀏覽器 GUI — 6 列 Chest Inventory，用於瀏覽所有已載入的 baked heads。
 *
 * <p>配置：
 * <ul>
 *   <li>Row 0：Grid 篩選按鈕（Grid 2 / 4 / 8 / 16 / All）+ 關閉按鈕</li>
 *   <li>Rows 1-4：頭顱物品（每頁 36 個）</li>
 *   <li>Row 5：分頁導航</li>
 * </ul>
 *
 * <p>點擊頭顱物品可獲得該頭顱（附帶 PDC 標記）。
 */
public final class HeadBrowserGUI implements InventoryHolder {

    // ====================== PDC Keys ======================

    /** 標記此物品為頭顱瀏覽器相關（用於 GUI 按鈕識別 or 已領取的頭顱）。 */
    private static final NamespacedKey PDC_BROWSER = new NamespacedKey("sculpt", "head_browser");

    /** 頭顱的 BlockKey 字串 (minecraft:stone)。 */
    private static final NamespacedKey PDC_BLOCK = new NamespacedKey("sculpt", "head_block");

    /** 頭顱的 gridN。 */
    private static final NamespacedKey PDC_GRID = new NamespacedKey("sculpt", "head_grid");

    /** 頭顱的 cell 座標字串 "x,y,z"。 */
    private static final NamespacedKey PDC_COORD = new NamespacedKey("sculpt", "head_coord");

    // ====================== 搜尋等待 ======================

    /** 正在等待聊天輸入搜尋詞的玩家 UUID 集合（由 handleChat 消費）。 */
    private static final Set<UUID> PENDING_SEARCH = ConcurrentHashMap.newKeySet();

    /**
     * 當玩家點擊搜尋按鈕時呼叫 — 關閉 GUI 並提示玩家在聊天輸入搜尋詞。
     * 下一個來自此玩家的聊天訊息由 {@link #handleChat} 攔截。
     */
    public static void startSearchPrompt(final Player player) {
        PENDING_SEARCH.add(player.getUniqueId());
        player.closeInventory();
        // 由語言文件提供提示訊息，這裡用 plugin 的語言管理器
        // 但此處為靜態方法無法取得 plugin，改由呼叫端發送
    }

    /**
     * 是否有玩家正在等待聊天搜尋輸入。
     */
    public static boolean isPendingSearch(final UUID playerId) {
        return PENDING_SEARCH.contains(playerId);
    }

    public static void clearPendingSearch(final UUID playerId) {
        PENDING_SEARCH.remove(playerId);
    }

    /**
     * 處理聊天事件 — 若玩家在等待搜尋輸入，攔截聊天並開啟搜尋結果 GUI。
     * @return true 表示已攔截（應 cancel 事件）
     */
    public static boolean handleChat(final Sculpt plugin, final Player player, final String message) {
        if (!PENDING_SEARCH.remove(player.getUniqueId())) return false;
        final String query = message.trim();
        if (query.isEmpty() || query.equalsIgnoreCase("cancel")) {
            // 取消搜尋，重新開啟原本的瀏覽器
            new HeadBrowserGUI(plugin, player).open();
            return true;
        }
        new HeadBrowserGUI(plugin, player, query, -1).open();
        return true;
    }

    // ====================== 常量 ======================

    private static final int[] GRID_OPTIONS = {2, 4, 8, 16};
    private static final int ROWS = 6;
    private static final int ITEMS_PER_PAGE = 36; // rows 1-4 × 9 cols
    private static final int HEAD_START_SLOT = 9;

    // ====================== 翻譯輔助 ======================

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * 從語言文件取得已翻譯的 MiniMessage 字串，解析為 Component。
     *
     * @param key  YAML 路徑（如 "head_browser.title"）
     * @param args MessageFormat 參數（{0}, {1}, ...）
     * @return 解析後的 Component
     */
    private Component tl(final String key, final Object... args) {
        final String msg = plugin.getLanguageManager().getMessage(player, key, args);
        return MM.deserialize(msg);
    }

    // ====================== 模式 ======================

    /** 瀏覽模式：SUMMARY = 方塊層級聚合，DETAIL = 單一 cell 層級。 */
    enum BrowserMode { SUMMARY, DETAIL }
    private BrowserMode mode;
    /** 進入 detail 模式時的目標方塊。 */
    private BlockKey detailBlockKey;
    /** 進入 detail 模式時的目標 grid。 */
    private int detailGridN;

    // ====================== 狀態 ======================

    private final Sculpt plugin;
    private final Player player;
    private final Inventory inventory;

    private int selectedGrid;  // -1 = All
    private int page;

    /** 搜尋關鍵字（null = 不篩選）。 */
    private final String searchQuery;

    /** 所有 grid 的完整列表（建構時快取一次）。 */
    private List<HeadEntry> allEntries;

    /** 方塊層級聚合列表（summary 模式用）。 */
    private List<SummaryEntry> summaryEntries;

    /** 當前篩選後的列表（依 selectedGrid + searchQuery + mode）。 */
    private List<HeadEntry> filteredEntries;

    /** GUI-scoped dedup for background loads of visible summary/detail keys. */
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
    private boolean detailLoading;

    // ====================== 資料記錄 ======================

    private record HeadEntry(BakeKey bakeKey, int gridN, ChunkCoord coord, HeadsRegistry.Entry skin) {}

    /** 方塊層級摘要（blockKey + gridN → cellCount + 預覽 skin）。 */
    private record SummaryEntry(BlockKey blockKey, int gridN, int cellCount, HeadsRegistry.Entry skin) {}

    // ====================== 建構 ======================

    /** 無搜尋條件的瀏覽器（顯示所有頭顱）。 */
    public HeadBrowserGUI(final Sculpt plugin, final Player player) {
        this(plugin, player, null, -1);
    }

    /**
     * 附帶搜尋條件的瀏覽器。
     *
     * @param plugin      Sculpt plugin 實例
     * @param player      目標玩家
     * @param searchQuery 搜尋關鍵字（null 或空白 = 不篩選），比對 BlockKey 字串（不區分大小寫）
     * @param grid        grid 大小（-1 = 全部）
     */
    public HeadBrowserGUI(final Sculpt plugin, final Player player,
                          final String searchQuery, final int grid) {
        this.plugin = plugin;
        this.player = player;
        this.selectedGrid = grid;
        this.page = 0;
        this.mode = BrowserMode.SUMMARY;
        this.detailBlockKey = null;
        this.detailGridN = -1;
        this.searchQuery = (searchQuery == null || searchQuery.isBlank()) ? null : searchQuery.trim().toLowerCase(java.util.Locale.ROOT);

        final Component title = (this.searchQuery != null)
                ? tl("head_browser.title_search", this.searchQuery)
                : tl("head_browser.title");
        this.inventory = Bukkit.createInventory(this, ROWS * 9, title);
        buildEntryList();
        render();
    }

    // ====================== 資料收集 ======================

    /**
     * 從所有 HeadsRegistry 收集方塊層級摘要（僅 untinted）。
     * 只讀取已在記憶體中的索引與 skin。冷資料會在當頁顯示時背景
     * prefetch，避免 GUI 開啟在 Paper/Folia 執行緒同步讀取 SQLite/SBH。
     */
    private void buildEntryList() {
        allEntries = new ArrayList<>();   // detail 模式用，初始為空
        summaryEntries = new ArrayList<>();

        for (final int gridN : GRID_OPTIONS) {
            final HeadsRegistry reg = plugin.getHeadsRegistry(gridN);
            if (reg == null) continue;

            for (final BlockKey blockKey : reg.knownBlockKeys()) {
                final BakeKey untinted = BakeKey.untinted(blockKey);
                if (!reg.hasKnownBlock(untinted)) continue;
                final HeadsRegistry.Entry previewSkin = reg.firstEntryIfLoaded(untinted);
                final int cellCount = reg.chunkCountIfLoaded(untinted);
                if (cellCount == 0) continue;

                summaryEntries.add(new SummaryEntry(blockKey, gridN, cellCount, previewSkin));
            }
        }

        // 依 BlockKey 名稱排序
        summaryEntries.sort((a, b) -> {
            int c = a.blockKey().toString().compareTo(b.blockKey().toString());
            if (c != 0) return c;
            return Integer.compare(a.gridN(), b.gridN());
        });

        applyFilter();
    }

    /**
     * 載入指定方塊 + grid 的所有 chunks（detail 模式用）。
     * 僅在使用者從 summary 點擊進入 detail 模式時呼叫。
     */
    private void loadDetailEntriesIfLoaded(final BlockKey blockKey, final int gridN) {
        allEntries = new ArrayList<>();
        final HeadsRegistry reg = plugin.getHeadsRegistry(gridN);
        if (reg == null) return;

        final BakeKey untinted = BakeKey.untinted(blockKey);
        final Map<ChunkCoord, HeadsRegistry.Entry> chunks = reg.chunksForIfLoaded(untinted);
        for (final Map.Entry<ChunkCoord, HeadsRegistry.Entry> cell : chunks.entrySet()) {
            allEntries.add(new HeadEntry(untinted, gridN, cell.getKey(), cell.getValue()));
        }

        // 依 ChunkCoord 排序
        allEntries.sort((a, b) -> a.coord().compareTo(b.coord()));
    }

    private void beginDetailLoad(final BlockKey blockKey, final int gridN) {
        mode = BrowserMode.DETAIL;
        detailBlockKey = blockKey;
        detailGridN = gridN;
        selectedGrid = -1;
        page = 0;
        allEntries = new ArrayList<>();
        detailLoading = true;
        applyFilter();
        render();

        final HeadsRegistry registry = plugin.getHeadsRegistry(gridN);
        if (registry == null) {
            detailLoading = false;
            return;
        }
        scheduleLoad(registry, BakeKey.untinted(blockKey), gridN, false, () -> {
            if (mode != BrowserMode.DETAIL
                    || detailGridN != gridN
                    || !blockKey.equals(detailBlockKey)) return;
            detailLoading = false;
            loadDetailEntriesIfLoaded(blockKey, gridN);
            applyFilter();
            render();
        });
    }

    private void scheduleVisibleSummaryLoads(
            final List<SummaryEntry> entries, final int start, final int end) {
        for (int index = start; index < end; index++) {
            final SummaryEntry entry = entries.get(index);
            if (entry.cellCount() >= 0 && entry.skin() != null) continue;
            final HeadsRegistry registry = plugin.getHeadsRegistry(entry.gridN());
            if (registry == null) continue;
            final BakeKey key = BakeKey.untinted(entry.blockKey());
            scheduleLoad(registry, key, entry.gridN(), true, () -> {
                if (mode != BrowserMode.SUMMARY) return;
                buildEntryList();
                render();
            });
        }
    }

    private void scheduleLoad(final HeadsRegistry registry, final BakeKey key,
                              final int gridN, final boolean preview,
                              final Runnable onLoaded) {
        final String loadKey = (preview ? "preview|" : "full|") + gridN + "|" + key;
        if (!pendingLoads.add(loadKey)) return;
        final java.util.concurrent.CompletableFuture<Boolean> future = preview
                ? registry.prefetchPreview(key) : registry.prefetch(key);
        future.whenComplete((loaded, failure) -> {
            pendingLoads.remove(loadKey);
            if (failure != null || !Boolean.TRUE.equals(loaded)) {
                if (!preview) {
                    dev.twme.sculpt.util.FoliaScheduler.runEntityTask(plugin, player, () -> {
                        if (player.getOpenInventory().getTopInventory().getHolder(false) != this) return;
                        detailLoading = false;
                        render();
                    });
                }
                return;
            }
            dev.twme.sculpt.util.FoliaScheduler.runEntityTask(plugin, player, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) != this) return;
                onLoaded.run();
            });
        });
    }

    /** 套用 grid + 搜尋詞 + detail 篩選，重置頁面。detail 模式只顯示目標方塊的 cells。 */
    private void applyFilter() {
        filteredEntries = new ArrayList<>(allEntries.size());
        for (final HeadEntry e : allEntries) {
            // 細節模式：只顯示目標方塊 + grid 的 cells
            if (mode == BrowserMode.DETAIL) {
                if (!e.bakeKey().block().equals(detailBlockKey)) continue;
                if (detailGridN != -1 && e.gridN() != detailGridN) continue;
            } else {
                // Grid 篩選（summary 模式）
                if (selectedGrid != -1 && e.gridN() != selectedGrid) continue;
            }
            // 搜尋詞篩選（比對全字串，不區分大小寫）
            if (searchQuery != null) {
                final String keyStr = e.bakeKey().block().toString();
                if (!keyStr.contains(searchQuery)) continue;
            }
            filteredEntries.add(e);
        }
        final int maxPage = maxPage();
        if (page > maxPage) page = maxPage;
    }

    private int maxPage() {
        if (filteredEntries.isEmpty()) return 0;
        return (filteredEntries.size() - 1) / ITEMS_PER_PAGE;
    }

    /** Summary 模式的最後一頁索引。 */
    private int lastSummaryPage() {
        final int count = summaryFilteredCount();
        if (count <= 0) return 0;
        return (count - 1) / ITEMS_PER_PAGE;
    }

    /** Summary 模式中過濾後的方塊數量。 */
    private int summaryFilteredCount() {
        int count = 0;
        for (final SummaryEntry e : summaryEntries) {
            if (selectedGrid != -1 && e.gridN() != selectedGrid) continue;
            if (searchQuery != null && !e.blockKey().toString().contains(searchQuery)) continue;
            count++;
        }
        return count;
    }

    // ====================== 開啟 GUI ======================

    public void open() {
        player.openInventory(inventory);
    }

    // ====================== 渲染 ======================

    private void render() {
        inventory.clear();
        renderGridButtons();
        renderItems();
    }

    /** Row 0：Grid 按鈕 + 關閉按鈕。 */
    private void renderGridButtons() {
        // Slots 0-3：Grid 2, 4, 8, 16
        for (int i = 0; i < GRID_OPTIONS.length; i++) {
            final int g = GRID_OPTIONS[i];
            final boolean active = (selectedGrid == g);
            final ItemStack btn = new ItemStack(
                    active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
            final ItemMeta meta = btn.getItemMeta();
            meta.displayName(tl("head_browser.grid_button",
                    active ? "green" : "gray", String.valueOf(g))
                    .decoration(TextDecoration.ITALIC, false));
            if (active) {
                meta.lore(List.of(
                        tl("head_browser.grid_button_selected")
                                .decoration(TextDecoration.ITALIC, false)));
            }
            meta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, "grid_" + g);
            btn.setItemMeta(meta);
            inventory.setItem(i, btn);
        }

        // Slot 4：All Grids
        final boolean allActive = (selectedGrid == -1);
        final ItemStack allBtn = new ItemStack(
                allActive ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        final ItemMeta allMeta = allBtn.getItemMeta();
        allMeta.displayName(tl("head_browser.grid_all",
                allActive ? "green" : "gray")
                .decoration(TextDecoration.ITALIC, false));
        allMeta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, "grid_all");
        allBtn.setItemMeta(allMeta);
        inventory.setItem(4, allBtn);

        // Slots 5-7：spacer（留空）

        // Slot 8：Close
        final ItemStack closeBtn = new ItemStack(Material.BARRIER);
        final ItemMeta closeMeta = closeBtn.getItemMeta();
        closeMeta.displayName(tl("head_browser.close_button")
                .decoration(TextDecoration.ITALIC, false));
        closeMeta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, "close");
        closeBtn.setItemMeta(closeMeta);
        inventory.setItem(8, closeBtn);
    }

    /** Rows 1-4：方塊摘要或頭顱物品 + Row 5：分頁導航。 */
    private void renderItems() {
        if (mode == BrowserMode.SUMMARY) {
            renderSummaryItems();
        } else {
            renderDetailItems();
        }
    }

    /** Summary 模式：每頁顯示 36 個方塊摘要，點擊進入該方塊的 detail 模式。 */
    private void renderSummaryItems() {
        // 先根據 grid + 搜尋詞過濾 summaryEntries
        final List<SummaryEntry> filteredSummary = new ArrayList<>();
        for (final SummaryEntry e : summaryEntries) {
            if (selectedGrid != -1 && e.gridN() != selectedGrid) continue;
            if (searchQuery != null) {
                if (!e.blockKey().toString().contains(searchQuery)) continue;
            }
            filteredSummary.add(e);
        }

        final int maxP = filteredSummary.isEmpty() ? 0 : (filteredSummary.size() - 1) / ITEMS_PER_PAGE;
        if (page > maxP) page = maxP;
        final int totalPages = Math.max(1, maxP + 1);

        final int startIdx = page * ITEMS_PER_PAGE;
        final int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, filteredSummary.size());

        int slot = HEAD_START_SLOT;
        for (int i = startIdx; i < endIdx; i++) {
            final SummaryEntry entry = filteredSummary.get(i);
            inventory.setItem(slot, buildSummaryItem(entry));
            slot++;
        }

        fillRest(slot);
        renderPagination(totalPages);
        scheduleVisibleSummaryLoads(filteredSummary, startIdx, endIdx);
    }

    /** Detail 模式：每頁顯示 36 個頭顱 cell，與原始 renderItems 相同。 */
    private void renderDetailItems() {
        if (detailLoading) {
            fillRest(HEAD_START_SLOT);
            renderBottomBar(1, true);
            return;
        }
        final int startIdx = page * ITEMS_PER_PAGE;
        final int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, filteredEntries.size());
        final int totalPages = Math.max(1, maxPage() + 1);

        int slot = HEAD_START_SLOT;
        for (int i = startIdx; i < endIdx; i++) {
            final HeadEntry entry = filteredEntries.get(i);
            inventory.setItem(slot, buildHeadItem(entry));
            slot++;
        }

        fillRest(slot);
        renderBottomBar(totalPages, true);
    }

    /** 用黑色玻璃填滿剩餘 slot。 */
    private void fillRest(final int startSlot) {
        final ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        final ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);
        int slot = startSlot;
        while (slot < HEAD_START_SLOT + ITEMS_PER_PAGE) {
            inventory.setItem(slot, filler);
            slot++;
        }
    }

    /** Summary 模式的分頁導航列（⏮ ◀◀ ◀ [page] ▶ ▶▶ ⏭）。 */
    private void renderPagination(final int totalPages) {
        renderBottomBar(totalPages, false);
    }

    /**
     * 統一的底部分頁導航列。
     *
     * @param totalPages 總頁數
     * @param detailMode true 時 slot 45 顯示返回按鈕，false 顯示首頁按鈕
     */
    private void renderBottomBar(final int totalPages, final boolean detailMode) {
        // ──── Slot 45：首頁 ⏮ / 返回 ← ────
        if (detailMode) {
            final ItemStack backBtn = new ItemStack(Material.STRUCTURE_VOID);
            final ItemMeta backMeta = backBtn.getItemMeta();
            backMeta.displayName(tl("head_browser.back_button")
                    .decoration(TextDecoration.ITALIC, false));
            backMeta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, "back");
            backBtn.setItemMeta(backMeta);
            inventory.setItem(45, backBtn);
        } else {
            inventory.setItem(45, buildNavButton(Material.EMERALD, "first",
                    "head_browser.first_button", "green", page > 0));
        }

        // ──── Slot 46：跳前 5 頁 ◀◀ ────
        inventory.setItem(46, buildNavButton(Material.ARROW, "jump_prev5",
                "head_browser.jump_prev5", "white", page > 0));

        // ──── Slot 47：上一頁 ◀ ────
        inventory.setItem(47, buildNavButton(Material.ARROW, "prev",
                "head_browser.prev_button", "white", page > 0));

        // ──── Slot 49：頁碼資訊 ────
        final ItemStack pageInfo = new ItemStack(Material.PAPER);
        final ItemMeta pageMeta = pageInfo.getItemMeta();
        pageMeta.displayName(tl("head_browser.page_info",
                String.valueOf(page + 1), String.valueOf(totalPages))
                .decoration(TextDecoration.ITALIC, false));
        final List<Component> loreList = new ArrayList<>();
        if (detailMode) {
            loreList.add(tl("head_browser.total_heads",
                    String.valueOf(filteredEntries.size())));
        } else {
            loreList.add(tl("head_browser.total_blocks",
                    String.valueOf(summaryEntries.size())));
        }
        if (searchQuery != null) {
            loreList.add(tl("head_browser.search_info", searchQuery));
        }
        pageMeta.lore(loreList);
        pageInfo.setItemMeta(pageMeta);
        inventory.setItem(49, pageInfo);

        // ──── Slot 51：下一頁 ▶ ────
        final boolean hasNext = page < totalPages - 1;
        inventory.setItem(51, buildNavButton(Material.ARROW, "next",
                "head_browser.next_button", "white", hasNext));

        // ──── Slot 52：跳後 5 頁 ▶▶ ────
        inventory.setItem(52, buildNavButton(Material.ARROW, "jump_next5",
                "head_browser.jump_next5", "white", hasNext));

        // ──── Slot 53：最後一頁 ⏭ ────
        inventory.setItem(53, buildNavButton(Material.DIAMOND, "last",
                "head_browser.last_button", "aqua", hasNext));
    }

    /**
     * 建立導航按鈕。enabled=false 時顯示灰色玻璃。
     */
    private ItemStack buildNavButton(final Material activeMat, final String actionId,
                                      final String langKey, final String color,
                                      final boolean enabled) {
        final ItemStack btn = new ItemStack(enabled ? activeMat : Material.GRAY_STAINED_GLASS_PANE);
        final ItemMeta meta = btn.getItemMeta();
        meta.displayName(tl(langKey, enabled ? color : "dark_gray")
                .decoration(TextDecoration.ITALIC, false));
        if (enabled) {
            meta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, actionId);
        }
        btn.setItemMeta(meta);
        return btn;
    }

    // ====================== 頭顱物品建構 ======================

    /**
     * 建立一個 PLAYER_HEAD 物品，套用目標 skin，名稱顯示方塊名稱與座標，
     * 附帶 PDC 標記。
     */
    private ItemStack buildHeadItem(final HeadEntry entry) {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final SkullMeta meta = (SkullMeta) head.getItemMeta();

        // 套用 skin（與 RegistryHeadResolver.buildSkullHead 相同模式）
        if (entry.skin() != null
                && !entry.skin().textureValue().isEmpty()
                && !entry.skin().textureSignature().isEmpty()) {
            final PlayerProfile profile = Bukkit.createProfile(new UUID(0L, 0L), "Sculpt");
            profile.setProperty(new ProfileProperty("textures",
                    entry.skin().textureValue(), entry.skin().textureSignature()));
            meta.setPlayerProfile(profile);
        }

        final String blockName = entry.bakeKey().block().toString();
        final String coordStr = entry.coord().x() + "," + entry.coord().y() + "," + entry.coord().z();
        meta.displayName(
                tl("head_browser.head_item_name",
                        blockName, String.valueOf(entry.gridN()), coordStr)
                        .decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(
                tl("head_browser.head_lore_grid", String.valueOf(entry.gridN())),
                tl("head_browser.head_lore_block", blockName),
                tl("head_browser.head_lore_coord", coordStr),
                Component.text(""),
                tl("head_browser.head_lore_click")));

        // PDC 標記
        meta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING, "true");
        meta.getPersistentDataContainer().set(PDC_BLOCK, PersistentDataType.STRING, blockName);
        meta.getPersistentDataContainer().set(PDC_GRID, PersistentDataType.INTEGER, entry.gridN());
        meta.getPersistentDataContainer().set(PDC_COORD, PersistentDataType.STRING, coordStr);

        head.setItemMeta(meta);
        return head;
    }

    /**
     * Summary 模式的方塊摘要物品 — 使用該方塊的第一個 skin 製作頭顱，
     * 名稱顯示方塊名稱、grid、cell 總數。
     * 點擊後切換至該方塊的 detail 模式。
     */
    private ItemStack buildSummaryItem(final SummaryEntry entry) {
        final String blockName = entry.blockKey().toString();
        final HeadsRegistry.Entry skin = entry.skin();

        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        final SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (skin != null && !skin.textureValue().isEmpty() && !skin.textureSignature().isEmpty()) {
            final PlayerProfile profile = Bukkit.createProfile(new UUID(0L, 0L), "Sculpt");
            profile.setProperty(new ProfileProperty("textures", skin.textureValue(), skin.textureSignature()));
            meta.setPlayerProfile(profile);
        }

        meta.displayName(
                tl("head_browser.summary_item_name", blockName, String.valueOf(entry.gridN()))
                        .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                tl("head_browser.summary_lore_cells",
                        entry.cellCount() < 0 ? "..." : String.valueOf(entry.cellCount())),
                tl("head_browser.summary_lore_click")));

        // PDC：用 "block_<BlockKey>@<gridN>" 標記，handleClick 時解析
        meta.getPersistentDataContainer().set(PDC_BROWSER, PersistentDataType.STRING,
                "block_" + blockName + "@" + entry.gridN());
        head.setItemMeta(meta);
        return head;
    }

    // ====================== InventoryHolder ======================

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // ====================== Click 處理 ======================

    /**
     * 在 InventoryClickEvent 中呼叫。由此 GUI 處理的事件已 setCancelled(true)。
     */
    public static void handleClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof final HeadBrowserGUI gui)) return;
        event.setCancelled(true);

        final ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) return;

        final ItemMeta meta = current.getItemMeta();
        if (meta == null) return;

        final String action = meta.getPersistentDataContainer()
                .get(PDC_BROWSER, PersistentDataType.STRING);
        if (action == null) return;

        final Player player = (Player) event.getWhoClicked();

        switch (action) {
            case "close" -> player.closeInventory();

            case "back" -> {
                // 返回方塊摘要模式，釋放 detail 資料
                gui.mode = BrowserMode.SUMMARY;
                gui.detailBlockKey = null;
                gui.detailGridN = -1;
                gui.detailLoading = false;
                gui.allEntries = new ArrayList<>(); // 釋放 detail 資料
                gui.page = 0;
                gui.applyFilter();
                gui.render();
            }

            case "prev" -> {
                if (gui.page > 0) {
                    gui.page--;
                    gui.render();
                }
            }

            case "next" -> {
                if (gui.mode == BrowserMode.SUMMARY) {
                    gui.page++;
                    gui.render();
                } else if (gui.page < gui.maxPage()) {
                    gui.page++;
                    gui.render();
                }
            }

            case "first" -> {
                if (gui.page > 0) {
                    gui.page = 0;
                    gui.render();
                }
            }

            case "last" -> {
                final int lastPage = gui.mode == BrowserMode.SUMMARY ? gui.lastSummaryPage() : gui.maxPage();
                if (gui.page < lastPage) {
                    gui.page = lastPage;
                    gui.render();
                }
            }

            case "jump_prev5" -> {
                if (gui.page > 0) {
                    gui.page = Math.max(0, gui.page - 5);
                    gui.render();
                }
            }

            case "jump_next5" -> {
                final int maxP = gui.mode == BrowserMode.SUMMARY ? gui.lastSummaryPage() : gui.maxPage();
                if (gui.page < maxP) {
                    gui.page = Math.min(maxP, gui.page + 5);
                    gui.render();
                }
            }

            default -> {
                if (action.startsWith("grid_")) {
                    // Grid 篩選按鈕
                    final String val = action.substring(5);
                    gui.selectedGrid = "all".equals(val) ? -1 : Integer.parseInt(val);
                    gui.page = 0;
                    gui.applyFilter();
                    gui.render();

                } else if (action.startsWith("block_")) {
                    // Summary 方塊項目 — 解析 blockKey + gridN，切換到 detail 模式
                    final String payload = action.substring(6); // "minecraft:stone@4"
                    final int at = payload.lastIndexOf('@');
                    if (at < 0) break;
                    final String blockStr = payload.substring(0, at);
                    final int g = Integer.parseInt(payload.substring(at + 1));
                    gui.beginDetailLoad(BlockKey.of(blockStr), g);

                } else if ("true".equals(action)) {
                    // 頭顱物品 — 給玩家複製品
                    final ItemStack give = current.clone();
                    final ItemStack leftover = player.getInventory().addItem(give).get(0);
                    if (leftover != null) {
                        // 背包已滿 → 掉落在地上
                        player.getWorld().dropItem(player.getLocation(), leftover);
                        player.sendActionBar(gui.tl("head_browser.head_dropped"));
                    } else {
                        player.sendActionBar(gui.tl("head_browser.head_received",
                                blockNameFromItem(give)));
                    }
                }
            }
        }
    }

    /** 從已標記的頭顱物品讀取 BlockKey 字串（用於訊息顯示）。 */
    private static String blockNameFromItem(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return "Unknown";
        final String name = meta.getPersistentDataContainer()
                .get(PDC_BLOCK, PersistentDataType.STRING);
        return name != null ? name : "Unknown";
    }
}
