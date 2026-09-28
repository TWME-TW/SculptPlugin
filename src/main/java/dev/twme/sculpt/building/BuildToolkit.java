package dev.twme.sculpt.building;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.joml.Vector3d;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.editor.PlayerEditSession;
import dev.twme.sculpt.editor.VirtualGridHit;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Shared services of the building toolkit: material and target resolution,
 * shape execution, and access to the edit engine and per-player state.
 */
public final class BuildToolkit {

    private final Sculpt plugin;
    private final BuildEngine engine;
    private final BuildPlayerState state = new BuildPlayerState();

    public BuildToolkit(final Sculpt plugin) {
        this.plugin = plugin;
        this.engine = new BuildEngine(plugin, BuildLimits.from(plugin.getConfig()));
    }

    public Sculpt plugin() {
        return plugin;
    }

    public BuildEngine engine() {
        return engine;
    }

    public BuildPlayerState state() {
        return state;
    }

    public BuildLimits limits() {
        return engine.limits();
    }

    public void reload() {
        engine.reload(BuildLimits.from(plugin.getConfig()));
    }

    public void forget(final Player player) {
        state.forget(player.getUniqueId());
        engine.forget(player);
    }

    public boolean isReady() {
        return plugin.getHeadResolver() != null;
    }

    // =====================================================================
    //  Materials
    // =====================================================================

    /** A resolved material, or the translation key explaining the failure. */
    public record MaterialChoice(BlockData material, String errorKey, Object errorArgument) {
        static MaterialChoice of(final BlockData material) {
            return new MaterialChoice(material, null, null);
        }

        static MaterialChoice error(final String key, final Object argument) {
            return new MaterialChoice(null, key, argument);
        }

        public boolean ok() {
            return material != null;
        }
    }

    /**
     * Resolve the building material: an explicit block-data argument wins,
     * then the brush material, then a block in the off hand, then a block in
     * the main hand.
     */
    public MaterialChoice resolveMaterial(
            final Player player,
            final String explicit,
            final BlockData preferred) {
        BlockData data = null;
        if (explicit != null) {
            try {
                data = Bukkit.createBlockData(normalizeBlockData(explicit));
            } catch (final IllegalArgumentException invalid) {
                return MaterialChoice.error("building.material.invalid", explicit);
            }
        } else if (preferred != null) {
            data = preferred.clone();
        } else {
            data = blockDataOf(player.getInventory().getItemInOffHand());
            if (data == null) data = blockDataOf(player.getInventory().getItemInMainHand());
        }
        if (data == null) return MaterialChoice.error("building.material.missing", null);
        return validateMaterial(player, data);
    }

    public MaterialChoice validateMaterial(final Player player, final BlockData data) {
        final Material material = data.getMaterial();
        if (!material.isBlock() || material.isAir() || material == Material.PLAYER_HEAD
                || material == Material.PLAYER_WALL_HEAD) {
            return MaterialChoice.error("building.material.unsupported", material.getKey());
        }
        final SculptDisplayMode displayMode = plugin.displayModeFor(player);
        if (!plugin.isMaterialSupported(material, displayMode)) {
            return MaterialChoice.error("building.material.unsupported", material.getKey());
        }
        return MaterialChoice.of(normalizeMaterial(data));
    }

    private static BlockData blockDataOf(final ItemStack item) {
        if (item == null || item.getType().isAir() || BuildTools.isTool(item)) return null;
        final Material material = item.getType();
        if (!material.isBlock()) return null;
        final ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockDataMeta blockDataMeta && blockDataMeta.hasBlockData()) {
            return blockDataMeta.getBlockData(material);
        }
        return material.createBlockData();
    }

    /** Slab cells render the full double-slab texture, as {@code /sculpt replace} does. */
    static BlockData normalizeMaterial(final BlockData input) {
        final BlockData normalized = input.clone();
        if (normalized instanceof Slab slab) slab.setType(Slab.Type.DOUBLE);
        return normalized;
    }

    static String normalizeBlockData(final String input) {
        final String normalized = input.trim().toLowerCase(Locale.ROOT);
        final int stateStart = normalized.indexOf('[');
        final String id = stateStart < 0 ? normalized : normalized.substring(0, stateStart);
        return id.contains(":") ? normalized : "minecraft:" + normalized;
    }

    // =====================================================================
    //  Targeting
    // =====================================================================

    /**
     * The cell under the player's cursor at the current resolution.
     *
     * @param face outward normal of the face the cursor entered through
     */
    public record CellTarget(World world, int grid, long x, long y, long z,
                             int faceX, int faceY, int faceZ) {
        public long adjacentX() {
            return x + faceX;
        }

        public long adjacentY() {
            return y + faceY;
        }

        public long adjacentZ() {
            return z + faceZ;
        }

        /** World-space center of a cell in block units. */
        public Vector3d center(final boolean adjacent) {
            final long cx = adjacent ? adjacentX() : x;
            final long cy = adjacent ? adjacentY() : y;
            final long cz = adjacent ? adjacentZ() : z;
            return new Vector3d((cx + 0.5) / grid, (cy + 0.5) / grid, (cz + 0.5) / grid);
        }
    }

    /** Trace the cursor through SculptBlock cells and regular blocks. */
    public CellTarget trace(final Player player) {
        final int grid = plugin.gridSizeFor(player);
        final PlayerEditSession session = new PlayerEditSession(player, grid);
        session.setPluginHooks(plugin::getActiveBlock,
            (key, block) -> false, (key, block) -> {}, ignored -> grid, block -> true);
        session.tickHover();
        final VirtualGridHit hit = session.getHoveredHit();
        if (hit == null || hit.block() == null) return null;
        return new CellTarget(hit.block().getWorld(), grid,
            (long) hit.block().getX() * grid + hit.pgx(),
            (long) hit.block().getY() * grid + hit.pgy(),
            (long) hit.block().getZ() * grid + hit.pgz(),
            hit.face().dx, hit.face().dy, hit.face().dz);
    }

    public BuildWorldWriter.Strategies strategies(final Player player) {
        return new BuildWorldWriter.Strategies(
            plugin.fillModeFor(player), plugin.displayModeFor(player));
    }

    // =====================================================================
    //  Shape execution
    // =====================================================================

    /**
     * Rasterize a shape off the main thread and apply it. The caller must
     * already hold the player's operation slot; it is always released.
     */
    public void runShape(
            final Player player,
            final World world,
            final String label,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final int grid,
            final Consumer<CellVolume> rasterizer,
            final boolean announce) {
        final BuildLimits limits = engine.limits();
        final BuildWorldWriter.Strategies strategies = strategies(player);
        CompletableFuture.supplyAsync(() -> {
            final CellVolume volume = new CellVolume(grid, limits.maxBlocks(), limits.maxCells());
            rasterizer.accept(volume);
            return volume;
        }).whenComplete((volume, failure) -> {
            if (failure != null) {
                engine.release(player);
                final Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                if (cause instanceof CellVolume.LimitExceededException limit) {
                    message(player, limit.blockLimit()
                        ? "building.result.too_many_blocks" : "building.result.too_many_cells",
                        limit.limit());
                } else if (cause instanceof IllegalArgumentException) {
                    message(player, "building.result.invalid_shape", cause.getMessage());
                } else {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "[Sculpt] failed to rasterize building shape", cause);
                    message(player, "building.result.failed", 1);
                }
                return;
            }
            if (volume.isEmpty()) {
                engine.release(player);
                if (announce) message(player, "building.result.no_changes");
                return;
            }
            final Map<BlockPos, BlockCellEdit> edits = new HashMap<>();
            volume.blocks().forEach((position, cells) -> edits.put(position,
                BlockCellEdit.single(grid, operation, material, (BitSet) cells.clone())));
            if (announce) {
                message(player, "building.result.started", label,
                    volume.cellCount(), volume.blockCount());
            }
            engine.applyEdits(player, world, label, edits, strategies, announce);
        });
    }

    /**
     * Smooth the cells covered by {@code area}. The caller must already hold
     * the player's operation slot.
     */
    public void runSmooth(final Player player, final World world, final CellVolume area) {
        final int grid = area.grid();
        final Set<BlockPos> sampled = new HashSet<>();
        for (final BlockPos block : area.blocks().keySet()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        sampled.add(new BlockPos(block.x() + dx, block.y() + dy, block.z() + dz));
                    }
                }
            }
        }
        final BuildWorldWriter.Strategies strategies = strategies(player);
        engine.sample(player, world, sampled, grid, lookup -> {
            final Map<BlockPos, BlockCellEdit> edits = BrushSmoother.smooth(area, lookup);
            if (edits.isEmpty()) {
                engine.release(player);
                return;
            }
            engine.applyEdits(player, world, "smooth", edits, strategies, false);
        });
    }

    void message(final Player player, final String key, final Object... arguments) {
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (player.isOnline()) MessageUtil.sendTranslated(player, key, arguments);
        });
    }
}
