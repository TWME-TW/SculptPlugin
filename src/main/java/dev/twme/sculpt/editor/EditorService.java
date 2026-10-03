package dev.twme.sculpt.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.building.BuildEngine;
import dev.twme.sculpt.building.EditObserver;
import dev.twme.sculpt.building.EditReport;
import dev.twme.sculpt.building.ReportMessages;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.packet.HotbarOverlay;
import dev.twme.sculpt.editor.packet.InputInterceptor;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.preview.PreviewScene;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
import dev.twme.textdisplayshape.packet.PacketShapeFactory;
import io.github.twme.virtualentities.VirtualEntities;
import io.github.twme.virtualentities.VirtualEntityManager;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The Sculpt editor: an explicit mode in which the hotbar becomes a tool
 * palette, previews are packet-only, and every change is committed through
 * the {@link BuildEngine} with undo history.
 *
 * <p>Requires the PacketEvents plugin. {@link Sculpt} only creates this
 * service when PacketEvents is enabled, so none of its packet classes load
 * otherwise.</p>
 */
public final class EditorService {

    /** Marker of the Sculpt Knife item that enters the editor. */
    public static final NamespacedKey KNIFE_KEY = new NamespacedKey("sculpt", "knife");

    private final Sculpt plugin;
    private final BuildEngine engine;
    private final VirtualEntityManager entities;
    private final PacketShapeFactory shapes;
    private final HotbarOverlay hotbar = new HotbarOverlay();
    private final Map<UUID, EditorSession> sessions = new ConcurrentHashMap<>();
    private final InputInterceptor input = new InputInterceptor(sessions::containsKey, this::onPacketInput);
    private final Map<UUID, Integer> lastClick = new ConcurrentHashMap<>();
    /** Last press time of each double-tap shortcut, keyed by player and kind. */
    private final Map<String, Long> lastTap = new ConcurrentHashMap<>();
    private volatile EditorConfig config;
    private Object tickTask;

    public EditorService(final Sculpt plugin, final BuildEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.entities = VirtualEntities.create();
        this.shapes = new PacketShapeFactory(entities);
        this.config = EditorConfig.from(plugin.getConfig());
    }

    public void start() {
        hotbar.register();
        input.register();
        tickTask = FoliaScheduler.runGlobalTaskTimer(plugin, this::scheduleTicks, 1L, 1L);
    }

    public void shutdown() {
        FoliaScheduler.cancelTask(tickTask);
        tickTask = null;
        for (final EditorSession session : new ArrayList<>(sessions.values())) {
            try {
                session.close();
                hotbar.hide(session.player());
            } catch (final RuntimeException ignored) {
                // Disabling must continue for every other session.
            }
        }
        sessions.clear();
        input.unregister();
        hotbar.unregister();
        shapes.close();
        entities.close();
    }

    public void reload() {
        this.config = EditorConfig.from(plugin.getConfig());
        for (final EditorSession session : sessions.values()) {
            session.scene().setAnimations(config.animations());
        }
    }

    public Sculpt plugin() {
        return plugin;
    }

    public BuildEngine engine() {
        return engine;
    }

    public EditorConfig config() {
        return config;
    }

    // =====================================================================
    //  Sessions
    // =====================================================================

    public boolean isEditing(final Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public EditorSession session(final Player player) {
        return sessions.get(player.getUniqueId());
    }

    public Collection<EditorSession> sessions() {
        return List.copyOf(sessions.values());
    }

    /** Enter the editor; returns whether the player is now editing. */
    public boolean enter(final Player player) {
        if (isEditing(player)) return true;
        if (!player.hasPermission(SculptPermissions.EDIT)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.EDIT);
            return false;
        }
        if (plugin.getHeadResolver() == null) {
            MessageUtil.sendTranslated(player, "building.not_ready");
            return false;
        }
        final PreviewScene scene = new PreviewScene(shapes, player.getUniqueId(),
            player.getLocation(), config.previewBudget(), config.animations());
        final EditorSession session = new EditorSession(this, player, scene);
        sessions.put(player.getUniqueId(), session);
        hotbar.show(player, toolIcons(player));
        session.start();
        MessageUtil.sendTranslated(player, "editor.entered");
        return true;
    }

    /** Leave the editor, discarding unconfirmed previews. */
    public void exit(final Player player, final boolean announce) {
        final EditorSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        session.close();
        hotbar.hide(player);
        if (announce) MessageUtil.sendTranslated(player, "editor.exited");
    }

    /** Forget a disconnected player without sending them anything. */
    public void forget(final Player player) {
        final EditorSession session = sessions.remove(player.getUniqueId());
        if (session != null) session.close();
        hotbar.forget(player.getUniqueId());
        input.forget(player.getUniqueId());
        lastClick.remove(player.getUniqueId());
        engine.forget(player);
    }

    /** Refresh the visible tool palette, for example after a language change. */
    public void refreshHotbar(final Player player) {
        if (isEditing(player)) hotbar.show(player, toolIcons(player));
    }

    private void scheduleTicks() {
        for (final EditorSession session : sessions.values()) {
            final Player player = session.player();
            FoliaScheduler.runEntityTask(plugin, player, () -> {
                if (player.isOnline() && sessions.get(player.getUniqueId()) == session) {
                    session.tick();
                }
            });
        }
    }

    // =====================================================================
    //  Input
    // =====================================================================

    /**
     * A left ({@code right == false}) or right click. Arm swings, block
     * clicks, and entity clicks can report one physical click several times
     * in the same tick; only the first one acts.
     */
    public void click(final Player player, final boolean right) {
        final EditorSession session = session(player);
        if (session == null) return;
        final int stamp = Bukkit.getCurrentTick() * 2 + (right ? 1 : 0);
        final Integer previous = lastClick.put(player.getUniqueId(), stamp);
        if (previous != null && previous == stamp) return;
        if (right) {
            session.secondary(player.isSneaking());
        } else {
            session.primary();
        }
    }

    /**
     * Whether this is the second press of {@code kind} within the configured
     * double-tap window, resetting the timer either way.
     */
    private boolean doubleTap(final UUID player, final int kind) {
        final long window = plugin.sculptConfig().doubleTapWindowMs();
        final long now = System.currentTimeMillis();
        final String key = player + ":" + kind;
        final Long previous = lastTap.put(key, now);
        return previous != null && now - previous <= window;
    }

    private void onPacketInput(final UUID id, final InputInterceptor.Input kind) {
        final Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            final EditorSession session = session(player);
            if (session == null) return;
            switch (kind) {
                case USE -> click(player, true);
                case DROP -> {
                    // The client already removed the dropped icon.
                    player.updateInventory();
                    if (player.isSneaking()) {
                        revert(player, false, 1);
                    } else if (doubleTap(id, 1)) {
                        session.clear();
                    } else {
                        session.cancel();
                    }
                }
                case SWAP -> {
                    player.updateInventory();
                    if (player.isSneaking()) {
                        exit(player, true);
                    } else {
                        session.cycleResolution();
                    }
                }
            }
        });
    }

    // =====================================================================
    //  Undo / redo
    // =====================================================================

    /** Undo or redo from the editor or the command; shows a rewind pulse when editing. */
    public void revert(final Player player, final boolean redo, final int steps) {
        if (!engine.tryBegin(player)) {
            MessageUtil.sendTranslated(player, "building.busy");
            return;
        }
        final EditorSession session = session(player);
        final EditObserver visuals = session == null ? EditObserver.NONE
            : session.observer(redo ? Colors.ADD : Colors.REMOVE, true);
        engine.revert(player, redo, steps, new EditObserver() {
            @Override
            public void onProgress(final int processed, final int total) {
                visuals.onProgress(processed, total);
            }

            @Override
            public void onFinish(final EditReport report, final List<dev.twme.sculpt.building.BlockPos> changed) {
                visuals.onFinish(report, changed);
                ReportMessages.sendRevert(player, redo, report);
            }
        });
    }

    // =====================================================================
    //  Materials
    // =====================================================================

    /**
     * Why a material cannot be used for cells at {@code grid}, as a
     * translation key taking the material name, or {@code null} when usable.
     */
    public String materialError(final Player player, final CellMaterial cellMaterial, final int grid) {
        if (cellMaterial == null) return "editor.material.missing";
        final Material material = cellMaterial.blockData().getMaterial();
        if (!material.isBlock() || material.isAir()) return "editor.material.unsupported";
        if (material == Material.PLAYER_HEAD || material == Material.PLAYER_WALL_HEAD) {
            if (!cellMaterial.isTexturedPlayerHead()) return "editor.material.head_texture";
            return grid <= 1 ? "editor.material.head_grid" : null;
        }
        return plugin.isMaterialSupported(material, plugin.displayModeFor(player))
            ? null : "editor.material.unsupported";
    }

    // =====================================================================
    //  Items
    // =====================================================================

    /** The Sculpt Knife: right-click it to enter the editor. */
    public ItemStack createKnife(final Player player) {
        final ItemStack knife = new ItemStack(Material.IRON_SWORD);
        final ItemMeta meta = knife.getItemMeta();
        final MiniMessage mini = MiniMessage.miniMessage();
        meta.displayName(mini.deserialize(MessageUtil.getTranslated(player, "editor.knife.name")));
        meta.lore(List.of(mini.deserialize(MessageUtil.getTranslated(player, "editor.knife.lore"))));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(KNIFE_KEY, PersistentDataType.BYTE, (byte) 1);
        knife.setItemMeta(meta);
        return knife;
    }

    public static boolean isKnife(final ItemStack item) {
        if (item == null || item.getType() != Material.IRON_SWORD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(KNIFE_KEY, PersistentDataType.BYTE);
    }

    private List<ItemStack> toolIcons(final Player player) {
        final MiniMessage mini = MiniMessage.miniMessage();
        final List<ItemStack> icons = new ArrayList<>(ToolId.values().length);
        for (final ToolId id : ToolId.values()) {
            final ItemStack icon = new ItemStack(id.icon());
            final ItemMeta meta = icon.getItemMeta();
            meta.displayName(mini.deserialize(
                MessageUtil.getTranslated(player, "editor.tool." + id.id() + ".name")));
            meta.lore(List.of(
                mini.deserialize(MessageUtil.getTranslated(player, "editor.tool." + id.id() + ".left")),
                mini.deserialize(MessageUtil.getTranslated(player, "editor.tool." + id.id() + ".right")),
                mini.deserialize(MessageUtil.getTranslated(player, "editor.tool.common"))));
            meta.setEnchantmentGlintOverride(false);
            icon.setItemMeta(meta);
            icons.add(icon);
        }
        return icons;
    }
}
