package dev.twme.sculpt.building;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.joml.Vector3d;

import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.editor.SculptClickTarget;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Click handling and particle guides for the Builder and Sculpt Brush tools.
 * Both tools also work on AIR-backed SculptBlocks, whose clicks arrive as
 * entity events on their Interaction proxies.
 */
public final class BuildToolListener implements Listener {

    private static final long GUIDE_PERIOD_TICKS = 5L;
    private static final Particle.DustOptions FIRST_POINT =
        new Particle.DustOptions(Color.fromRGB(0x5FBA6B), 1.2f);
    private static final Particle.DustOptions POINT =
        new Particle.DustOptions(Color.fromRGB(0xE8C96D), 1.0f);
    private static final Particle.DustOptions EDGE =
        new Particle.DustOptions(Color.fromRGB(0x7AB8E8), 0.6f);
    private static final Particle.DustOptions CURSOR =
        new Particle.DustOptions(Color.WHITE, 0.7f);

    private final BuildToolkit toolkit;
    private final BuildCommand buildCommand;
    private final Map<UUID, Integer> lastClickTick = new ConcurrentHashMap<>();
    private Object guideTask;

    public BuildToolListener(final BuildToolkit toolkit, final BuildCommand buildCommand) {
        this.toolkit = toolkit;
        this.buildCommand = buildCommand;
    }

    public void start() {
        guideTask = FoliaScheduler.runGlobalTaskTimer(toolkit.plugin(), this::scheduleGuides,
            GUIDE_PERIOD_TICKS, GUIDE_PERIOD_TICKS);
    }

    public void shutdown() {
        FoliaScheduler.cancelTask(guideTask);
        guideTask = null;
    }

    // =====================================================================
    //  Events
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Player player = event.getPlayer();
        final BuildTools.Tool tool = BuildTools.heldTool(player);
        if (tool == null) return;
        final Action action = event.getAction();
        final boolean left = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        final boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        if (!left && !right) return;
        event.setCancelled(true);
        handle(player, tool, left);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        final BuildTools.Tool tool = BuildTools.heldTool(player);
        if (tool == null || SculptClickTarget.blockLocation(event.getEntity()) == null) return;
        event.setCancelled(true);
        handle(player, tool, true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Player player = event.getPlayer();
        final BuildTools.Tool tool = BuildTools.heldTool(player);
        if (tool == null || SculptClickTarget.blockLocation(event.getRightClicked()) == null) {
            return;
        }
        event.setCancelled(true);
        handle(player, tool, false);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        lastClickTick.remove(event.getPlayer().getUniqueId());
        toolkit.forget(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        toolkit.state().clearPoints(event.getPlayer().getUniqueId());
    }

    /**
     * Arm swings and entity clicks can both report the same physical click
     * within one tick; only the first one acts.
     */
    private boolean claimClick(final Player player, final boolean left) {
        final int tick = Bukkit.getCurrentTick() * 2 + (left ? 0 : 1);
        final Integer previous = lastClickTick.put(player.getUniqueId(), tick);
        return previous == null || previous != tick;
    }

    private void handle(final Player player, final BuildTools.Tool tool, final boolean left) {
        if (!claimClick(player, left)) return;
        switch (tool) {
            case BUILDER -> handleBuilder(player, left);
            case BRUSH -> handleBrush(player, left);
        }
    }

    // =====================================================================
    //  Builder
    // =====================================================================

    private void handleBuilder(final Player player, final boolean left) {
        if (!player.hasPermission(SculptPermissions.BUILD)) {
            MessageUtil.sendTranslatedActionBar(player, "general.no_permission");
            return;
        }
        if (!left) {
            buildCommand.addPointAtCursor(player, player.isSneaking());
        } else if (player.isSneaking()) {
            toolkit.state().clearPoints(player.getUniqueId());
            MessageUtil.sendTranslatedActionBar(player, "building.points.cleared");
        } else {
            buildCommand.removeLastPoint(player);
        }
    }

    // =====================================================================
    //  Brush
    // =====================================================================

    private void handleBrush(final Player player, final boolean left) {
        if (!player.hasPermission(SculptPermissions.BRUSH)) {
            MessageUtil.sendTranslatedActionBar(player, "general.no_permission");
            return;
        }
        if (!toolkit.isReady()) {
            MessageUtil.sendTranslatedActionBar(player, "building.not_ready");
            return;
        }
        final BuildToolkit.CellTarget target = toolkit.trace(player);
        if (target == null) return;
        final BuildPlayerState.BrushSettings brush = toolkit.state().brush(player.getUniqueId());

        if (brush.mode() == BuildPlayerState.BrushMode.PAINT && left) {
            pickMaterial(player, target, brush);
            return;
        }

        BlockData material = null;
        final boolean adding = !left && brush.mode() == BuildPlayerState.BrushMode.SCULPT;
        if (brush.mode() == BuildPlayerState.BrushMode.PAINT || adding) {
            final BuildToolkit.MaterialChoice choice =
                toolkit.resolveMaterial(player, null, brush.material());
            if (!choice.ok()) {
                MessageUtil.sendTranslatedActionBar(player, choice.errorKey(),
                    choice.errorArgument());
                return;
            }
            material = choice.material();
        }

        if (!toolkit.engine().tryBegin(player)) {
            MessageUtil.sendTranslatedActionBar(player, "building.busy");
            return;
        }
        final long cx = adding ? target.adjacentX() : target.x();
        final long cy = adding ? target.adjacentY() : target.y();
        final long cz = adding ? target.adjacentZ() : target.z();
        final World world = target.world();

        if (brush.mode() == BuildPlayerState.BrushMode.SMOOTH) {
            final BuildLimits limits = toolkit.limits();
            final CellVolume area = new CellVolume(target.grid(),
                limits.maxBlocks(), limits.maxCells());
            try {
                ShapeRasterizer.brush(cx, cy, cz, brush.radius(), brush.shape(), area);
            } catch (final CellVolume.LimitExceededException tooLarge) {
                toolkit.engine().release(player);
                MessageUtil.sendTranslatedActionBar(player, "building.result.too_many_cells",
                    tooLarge.limit());
                return;
            }
            toolkit.runSmooth(player, world, area);
            return;
        }

        final BlockCellEdit.Operation operation = switch (brush.mode()) {
            case PAINT -> BlockCellEdit.Operation.PAINT;
            default -> adding ? BlockCellEdit.Operation.ADD : BlockCellEdit.Operation.CARVE;
        };
        toolkit.runShape(player, world, "brush", operation, material, target.grid(),
            volume -> ShapeRasterizer.brush(cx, cy, cz, brush.radius(), brush.shape(), volume),
            false);
    }

    /** Paint mode left-click: copy the material under the cursor. */
    private void pickMaterial(
            final Player player,
            final BuildToolkit.CellTarget target,
            final BuildPlayerState.BrushSettings brush) {
        final int grid = target.grid();
        final Block block = target.world().getBlockAt(
            (int) Math.floorDiv(target.x(), grid),
            (int) Math.floorDiv(target.y(), grid),
            (int) Math.floorDiv(target.z(), grid));
        final SculptBlock sculpt = toolkit.plugin().getActiveBlock(BlockPosKey.of(block));
        BlockData picked;
        if (sculpt != null && sculpt.state == SculptBlock.State.SCULPTED) {
            final int side = 16 / grid;
            picked = OctreeCellEditor.materialAt(sculpt.root,
                (int) Math.floorMod(target.x(), grid) * side + side / 2,
                (int) Math.floorMod(target.y(), grid) * side + side / 2,
                (int) Math.floorMod(target.z(), grid) * side + side / 2);
            if (picked == null) picked = sculpt.originalBlockData;
        } else {
            picked = block.getBlockData();
        }
        final BuildToolkit.MaterialChoice choice = toolkit.validateMaterial(player, picked);
        if (!choice.ok()) {
            MessageUtil.sendTranslatedActionBar(player, choice.errorKey(), choice.errorArgument());
            return;
        }
        toolkit.state().setBrush(player.getUniqueId(), brush.withMaterial(choice.material()));
        MessageUtil.sendTranslatedActionBar(player, "building.brush.picked",
            choice.material().getAsString());
    }

    // =====================================================================
    //  Particle guides
    // =====================================================================

    private void scheduleGuides() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            FoliaScheduler.runEntityTask(toolkit.plugin(), player, () -> drawGuides(player));
        }
    }

    private void drawGuides(final Player player) {
        if (!player.isOnline()) return;
        final BuildTools.Tool tool = BuildTools.heldTool(player);
        if (tool == null) return;
        if (tool == BuildTools.Tool.BUILDER) {
            drawPoints(player);
            final BuildToolkit.CellTarget target = toolkit.trace(player);
            if (target != null) drawDot(player, target.center(!player.isSneaking()), CURSOR);
            return;
        }
        final BuildToolkit.CellTarget target = toolkit.trace(player);
        if (target == null) return;
        final BuildPlayerState.BrushSettings brush = toolkit.state().brush(player.getUniqueId());
        drawBrush(player, target, brush);
    }

    private void drawPoints(final Player player) {
        final List<Vector3d> points = toolkit.state().points(
            player.getUniqueId(), player.getWorld().getUID());
        for (int index = 0; index < points.size(); index++) {
            drawDot(player, points.get(index), index == 0 ? FIRST_POINT : POINT);
            if (index > 0) drawLine(player, points.get(index - 1), points.get(index));
        }
    }

    private static void drawLine(final Player player, final Vector3d from, final Vector3d to) {
        final double length = from.distance(to);
        final int steps = Math.min(48, Math.max(1, (int) Math.ceil(length / 0.25)));
        for (int step = 1; step < steps; step++) {
            final double t = (double) step / steps;
            drawDot(player, new Vector3d(from).lerp(to, t), EDGE);
        }
    }

    private static void drawBrush(
            final Player player,
            final BuildToolkit.CellTarget target,
            final BuildPlayerState.BrushSettings brush) {
        final Vector3d center = target.center(false);
        drawDot(player, center, CURSOR);
        final double radius = (brush.radius() + 0.5) / target.grid();
        final int segments = 16;
        for (int index = 0; index < segments; index++) {
            final double angle = Math.PI * 2.0 * index / segments;
            final double a = Math.cos(angle) * radius;
            final double b = Math.sin(angle) * radius;
            drawDot(player, new Vector3d(center).add(a, 0, b), EDGE);
            drawDot(player, new Vector3d(center).add(a, b, 0), EDGE);
            drawDot(player, new Vector3d(center).add(0, a, b), EDGE);
        }
    }

    private static void drawDot(
            final Player player,
            final Vector3d point,
            final Particle.DustOptions dust) {
        player.spawnParticle(Particle.DUST,
            new Location(player.getWorld(), point.x, point.y, point.z),
            1, 0.0, 0.0, 0.0, 0.0, dust);
    }
}
