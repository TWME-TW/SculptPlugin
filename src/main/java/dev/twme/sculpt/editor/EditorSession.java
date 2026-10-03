package dev.twme.sculpt.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.building.BuildWorldWriter;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.building.EditObserver;
import dev.twme.sculpt.building.EditReport;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.preview.PreviewScene;
import dev.twme.sculpt.editor.tool.Tool;
import dev.twme.sculpt.editor.tool.Tools;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * One player's editor: the selected tool, cursor, material palette,
 * selection, previews, and HUD. Created by {@link EditorService} when the
 * player enters the editor and discarded when they leave.
 *
 * <p>Every method runs on the player's thread.</p>
 */
public final class EditorSession {

    private static final int PALETTE_SIZE = 9;
    private static final int MESSAGE_TICKS = 40;

    private final EditorService service;
    private final Player player;
    private final PreviewScene scene;
    private final Map<ToolId, Tool> tools = new EnumMap<>(ToolId.class);
    private final Deque<CellMaterial> palette = new ArrayDeque<>();

    private ToolId tool = ToolId.SCULPT;
    private CellTarget target;
    private CellMaterial material;
    private VoxelBox selection;
    private long ticks;

    private String message;
    private long messageUntil;
    private BossBar progressBar;

    EditorSession(final EditorService service, final Player player, final PreviewScene scene) {
        this.service = service;
        this.player = player;
        this.scene = scene;
        for (final Tool created : Tools.create()) tools.put(created.id(), created);
        this.material = initialMaterial(player);
        remember(material);
    }

    // =====================================================================
    //  Accessors
    // =====================================================================

    public Player player() {
        return player;
    }

    public Sculpt plugin() {
        return service.plugin();
    }

    public EditorService service() {
        return service;
    }

    public PreviewScene scene() {
        return scene;
    }

    public ToolId toolId() {
        return tool;
    }

    public Tool tool() {
        return tools.get(tool);
    }

    @SuppressWarnings("unchecked")
    public <T extends Tool> T tool(final ToolId id) {
        return (T) tools.get(id);
    }

    /** The cell under the cursor this tick, or {@code null}. */
    public CellTarget target() {
        return target;
    }

    public int grid() {
        return plugin().gridSizeFor(player);
    }

    /** Voxels per cell at the current resolution. */
    public int cellSide() {
        return 16 / grid();
    }

    public CellMaterial material() {
        return material;
    }

    public List<CellMaterial> palette() {
        return new ArrayList<>(palette);
    }

    public VoxelBox selection() {
        return selection;
    }

    public void setSelection(final VoxelBox box) {
        this.selection = box;
    }

    public BuildWorldWriter.Strategies strategies() {
        return new BuildWorldWriter.Strategies(
            plugin().fillModeFor(player), plugin().displayModeFor(player));
    }

    // =====================================================================
    //  Tick
    // =====================================================================

    void tick() {
        ticks++;
        final CellTracer.Result traced = new CellTracer(
            player, grid(), service.config().reach(), plugin()::getActiveBlock).trace();
        target = traced == null ? null : CellTarget.of(traced, grid());
        scene.beginFrame();
        scene.follow(player.getLocation());
        try {
            tool().preview(this);
        } catch (final RuntimeException failure) {
            plugin().getLogger().log(Level.WARNING,
                "[Sculpt] editor preview failed for " + player.getName(), failure);
        }
        scene.tick();
        if (ticks % service.config().hudIntervalTicks() == 0) sendHud();
    }

    // =====================================================================
    //  Input
    // =====================================================================

    void primary() {
        if (!canUse(tool)) return;
        tool().primary(this);
    }

    void secondary(final boolean sneaking) {
        if (sneaking) {
            tool().openSettings(this);
            return;
        }
        if (!canUse(tool)) return;
        tool().secondary(this);
    }

    void selectTool(final ToolId next) {
        if (next == tool) return;
        tool().deactivate(this);
        tool = next;
        tool().activate(this);
        if (!canUse(next)) {
            flash("editor.tool.no_permission");
        }
        sendHud();
    }

    /** Switch tools from a dialog, moving the client's hotbar selection too. */
    public void selectToolFromDialog(final ToolId next) {
        player.getInventory().setHeldItemSlot(next.slot());
        selectTool(next);
    }

    void adjust(final int delta) {
        if (!canUse(tool)) return;
        tool().adjust(this, delta);
        sendHud();
    }

    void cycleResolution() {
        final int previous = grid();
        final int next = plugin().cycleGridSize(player);
        if (next == previous) {
            flash("editor.resolution.locked", previous);
        } else {
            flash("editor.resolution.changed", next);
        }
    }

    void cancel() {
        if (!tool().cancel(this)) flash("editor.nothing_to_cancel");
    }

    /** Double {@code Q}: drop everything the current tool holds. */
    void clear() {
        if (!tool().clear(this)) flash("editor.nothing_to_cancel");
    }

    /** Middle click, or left click with the paint tool: pick the material under the cursor. */
    public void pickMaterialAtCursor() {
        if (target == null) return;
        final CellMaterial picked = materialAt(target);
        if (picked == null) return;
        final String error = service.materialError(player, picked, grid());
        if (error != null) {
            flash(error, picked.blockData().getMaterial().getKey().getKey());
            return;
        }
        setMaterial(picked);
        flash("editor.material.picked", describe(picked));
    }

    // =====================================================================
    //  Materials
    // =====================================================================

    public void setMaterial(final CellMaterial next) {
        this.material = next;
        remember(next);
    }

    /** The occupied material at a cell, or the block's own data for a regular block. */
    public CellMaterial materialAt(final CellTarget cell) {
        final SculptBlock sculpt = plugin().getActiveBlock(BlockPosKey.of(cell.block()));
        if (sculpt != null && sculpt.state == SculptBlock.State.SCULPTED) {
            final int side = 16 / cell.grid();
            final OctreeNode leaf = sculpt.root.findLeaf(
                (int) Math.floorMod(cell.x(), cell.grid()) * side + side / 2,
                (int) Math.floorMod(cell.y(), cell.grid()) * side + side / 2,
                (int) Math.floorMod(cell.z(), cell.grid()) * side + side / 2);
            if (leaf == null) return CellMaterial.block(sculpt.originalBlockData);
            final BlockData data = leaf.blockData() == null ? sculpt.originalBlockData : leaf.blockData();
            return new CellMaterial(data.clone(), leaf.playerHeadTexture());
        }
        final BlockData data = cell.block().getBlockData();
        return data.getMaterial().isAir() ? null : CellMaterial.block(data.clone());
    }

    /**
     * The current material, validated for the current resolution. Reports
     * the problem on the action bar and returns {@code null} when unusable.
     */
    public CellMaterial usableMaterial() {
        final String error = service.materialError(player, material, grid());
        if (error == null) return material;
        flash(error, material.blockData().getMaterial().getKey().getKey());
        return null;
    }

    private void remember(final CellMaterial used) {
        palette.removeIf(entry -> entry.equals(used));
        palette.addFirst(used);
        while (palette.size() > PALETTE_SIZE) palette.removeLast();
    }

    private static CellMaterial initialMaterial(final Player player) {
        final ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && hand.getType().isBlock() && !hand.getType().isAir()
                && hand.getType().isSolid()) {
            return CellMaterial.block(hand.getType().createBlockData());
        }
        return CellMaterial.block(Material.STONE.createBlockData());
    }

    public static String describe(final CellMaterial cellMaterial) {
        final String name = cellMaterial.blockData().getMaterial().getKey().getKey();
        return cellMaterial.isTexturedPlayerHead() ? name + " ✦" : name;
    }

    // =====================================================================
    //  Commits
    // =====================================================================

    /**
     * Rasterize off the main thread, then commit. {@code edits} must not
     * touch the world; it may throw {@link CellVolume.LimitExceededException}
     * or {@link IllegalArgumentException} to reject the operation.
     */
    public void commitAsync(
            final String label,
            final Supplier<Map<BlockPos, BlockCellEdit>> edits,
            final int color) {
        if (!service.engine().tryBegin(player)) {
            flash("building.busy");
            return;
        }
        final World world = player.getWorld();
        CompletableFuture.supplyAsync(edits).whenComplete((result, failure) ->
            FoliaScheduler.runEntityTask(plugin(), player, () -> {
                if (failure != null) {
                    service.engine().release(player);
                    reportFailure(failure);
                    return;
                }
                if (result.isEmpty()) {
                    service.engine().release(player);
                    flash("building.result.no_changes");
                    return;
                }
                apply(world, label, result, color);
            }));
    }

    /** Commit edits that are already computed. */
    public void commit(
            final String label,
            final Map<BlockPos, BlockCellEdit> edits,
            final int color) {
        if (edits.isEmpty()) {
            flash("building.result.no_changes");
            return;
        }
        if (!service.engine().tryBegin(player)) {
            flash("building.busy");
            return;
        }
        apply(player.getWorld(), label, edits, color);
    }

    /** Reserve the player's single operation slot, reporting when it is taken. */
    public boolean begin() {
        if (service.engine().tryBegin(player)) return true;
        flash("building.busy");
        return false;
    }

    private void apply(
            final World world,
            final String label,
            final Map<BlockPos, BlockCellEdit> edits,
            final int color) {
        service.engine().applyEdits(player, world, label, edits, strategies(),
            observer(color, false));
    }

    /** Feedback for commits, undo, and redo: progress bar, pulse, and skip reasons. */
    EditObserver observer(final int color, final boolean announce) {
        return new EditObserver() {
            @Override
            public void onProgress(final int processed, final int total) {
                showProgress(processed, total);
            }

            @Override
            public void onFinish(final EditReport report, final List<BlockPos> changed) {
                hideProgress();
                pulse(changed, color);
                if (announce) return;
                if (report.skipped() > 0) {
                    dev.twme.sculpt.building.ReportMessages.sendSkipped(player, report);
                } else if (!report.historyRecorded) {
                    MessageUtil.sendTranslated(player, "building.result.not_recorded");
                }
            }
        };
    }

    private void reportFailure(final Throwable failure) {
        final Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof CellVolume.LimitExceededException limit) {
            MessageUtil.sendTranslated(player, limit.blockLimit()
                ? "building.result.too_many_blocks" : "building.result.too_many_cells",
                limit.limit());
        } else if (cause instanceof IllegalArgumentException invalid) {
            MessageUtil.sendTranslated(player, "building.result.invalid_shape", invalid.getMessage());
        } else {
            plugin().getLogger().log(Level.WARNING, "[Sculpt] editor operation failed", cause);
            MessageUtil.sendTranslated(player, "building.result.failed", 1);
        }
    }

    /** Highlight the bounding box of changed blocks, then fade it out. */
    public void pulseBlocks(final List<BlockPos> changed, final int color) {
        pulse(changed, color);
    }

    void pulse(final List<BlockPos> changed, final int color) {
        if (changed.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final BlockPos position : changed) {
            minX = Math.min(minX, position.x());
            minY = Math.min(minY, position.y());
            minZ = Math.min(minZ, position.z());
            maxX = Math.max(maxX, position.x());
            maxY = Math.max(maxY, position.y());
            maxZ = Math.max(maxZ, position.z());
        }
        scene.pulse(new Vector3f(minX, minY, minZ), new Vector3f(maxX + 1, maxY + 1, maxZ + 1),
            Colors.face(color));
    }

    // =====================================================================
    //  HUD
    // =====================================================================

    /** Show a short message on the action bar in place of the HUD for two seconds. */
    public void flash(final String key, final Object... arguments) {
        message = MessageUtil.getTranslated(player, key, arguments);
        messageUntil = ticks + MESSAGE_TICKS;
        MessageUtil.sendActionBar(player, message);
    }

    void sendHud() {
        if (message != null && ticks < messageUntil) {
            MessageUtil.sendActionBar(player, message);
            return;
        }
        message = null;
        final String status = tool().status(this);
        MessageUtil.sendTranslatedActionBar(player, "editor.hud",
            MessageUtil.getTranslated(player, "editor.tool." + tool.id() + ".name"),
            grid(),
            status.isEmpty() ? "" : " <dark_gray>·</dark_gray> " + status,
            describe(material));
    }

    private void showProgress(final int processed, final int total) {
        if (total < service.config().progressBarThreshold()) return;
        final float progress = total == 0 ? 1f : Math.clamp((float) processed / total, 0f, 1f);
        if (progressBar == null) {
            progressBar = BossBar.bossBar(MiniMessage.miniMessage().deserialize(
                MessageUtil.getTranslated(player, "editor.progress")), progress,
                BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            player.showBossBar(progressBar);
        } else {
            progressBar.progress(progress);
        }
    }

    private void hideProgress() {
        if (progressBar == null) return;
        player.hideBossBar(progressBar);
        progressBar = null;
    }

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    boolean canUse(final ToolId id) {
        final String permission = id.permission();
        return permission == null || player.hasPermission(permission);
    }

    void start() {
        tool().activate(this);
        sendHud();
    }

    void close() {
        for (final Tool each : tools.values()) {
            try {
                each.deactivate(this);
            } catch (final RuntimeException ignored) {
                // The scene is cleared below regardless.
            }
        }
        scene.clear();
        hideProgress();
    }
}
