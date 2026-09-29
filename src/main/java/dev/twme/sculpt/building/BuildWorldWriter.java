package dev.twme.sculpt.building;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.joml.Quaternionf;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.core.VariantResolution;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;

/**
 * Applies detached cell edits and snapshots to the world. Every method must
 * run on the region thread that owns the target block.
 */
public final class BuildWorldWriter {

    enum Status { CHANGED, UNCHANGED, PROTECTED, OBSTRUCTED, LOCKED, LIMIT, STALE }

    /**
     * @param before state before the change, or {@code null} when unchanged
     * @param after  state after the change, or {@code null} when unchanged
     * @param work   display-entity work units consumed
     */
    record Outcome(Status status, BlockSnapshot before, BlockSnapshot after, int work) {
        static Outcome of(final Status status) {
            return new Outcome(status, null, null, 1);
        }
    }

    /** Strategies applied to SculptBlocks created by an edit. */
    public record Strategies(FillMode fillMode, SculptDisplayMode displayMode) {}

    private final Sculpt plugin;

    BuildWorldWriter(final Sculpt plugin) {
        this.plugin = plugin;
    }

    // =====================================================================
    //  Capture
    // =====================================================================

    SculptBlock activeSculpt(final Block block) {
        final SculptBlock sculpt = plugin.getActiveBlock(BlockPosKey.of(block));
        return sculpt != null && sculpt.state == SculptBlock.State.SCULPTED
            && !sculpt.despawned ? sculpt : null;
    }

    /** Whether some registered SculptBlock is in a transitional state here. */
    private boolean hasInactiveRegistration(final Block block) {
        final SculptBlock registered = plugin.getActiveBlock(BlockPosKey.of(block));
        return registered != null && activeSculpt(block) == null;
    }

    BlockSnapshot capture(final Block block) {
        final SculptBlock sculpt = activeSculpt(block);
        final BlockData worldData = block.getBlockData();
        return sculpt == null
            ? BlockSnapshot.regular(worldData)
            : BlockSnapshot.sculpt(worldData, sculpt);
    }

    // =====================================================================
    //  Cell edits
    // =====================================================================

    Outcome applyEdit(
            final Player player,
            final Block block,
            final BlockCellEdit edit,
            final Strategies strategies) {
        if (hasInactiveRegistration(block)) return Outcome.of(Status.STALE);
        final SculptBlock existing = activeSculpt(block);
        final BlockData worldData = block.getBlockData();

        final OctreeNode tree;
        if (existing != null) {
            tree = OctreeCellEditor.copy(existing.root, existing.originalBlockData);
        } else if (isReplaceable(block)) {
            final BlockData firstAdded = edit.firstAddedMaterial();
            if (firstAdded == null) return Outcome.of(Status.UNCHANGED);
            tree = OctreeCellEditor.empty(firstAdded);
        } else if (isConvertible(block, strategies.displayMode())) {
            tree = OctreeCellEditor.full(worldData);
        } else {
            return Outcome.of(Status.OBSTRUCTED);
        }

        final OctreeCellEditor.Result result = OctreeCellEditor.apply(tree, edit);
        if (!result.changed()) {
            return Outcome.of(result.lockedCells() > 0 ? Status.LOCKED : Status.UNCHANGED);
        }
        if (!plugin.canPlayerBuild(player, block)) return Outcome.of(Status.PROTECTED);

        final BlockSnapshot before = existing == null
            ? BlockSnapshot.regular(worldData)
            : BlockSnapshot.sculpt(worldData, existing);
        OctreeCellEditor.canonicalize(tree);

        final Status status = install(block, existing, tree, edit.grid(), strategies, before);
        if (status != Status.CHANGED) return Outcome.of(status);
        return new Outcome(Status.CHANGED, before, capture(block),
            Math.max(1, OctreeCellEditor.occupiedLeaves(tree)));
    }

    private Status install(
            final Block block,
            final SculptBlock existing,
            final OctreeNode tree,
            final int grid,
            final Strategies strategies,
            final BlockSnapshot before) {
        switch (OctreeCellEditor.occupancy(tree)) {
            case EMPTY -> {
                writeVanilla(block, existing, Material.AIR.createBlockData());
                return Status.CHANGED;
            }
            case FULL_UNIFORM -> {
                final BlockData material = OctreeCellEditor.uniformMaterial(tree);
                if (material != null && canBeVanillaBlock(material)) {
                    writeVanilla(block, existing, material);
                    return Status.CHANGED;
                }
            }
            case PARTIAL -> {
                // Installed as a SculptBlock below.
            }
        }

        if (tree.isLeaf()) tree.subdivide();
        final SculptBlock target;
        if (existing != null) {
            target = newSculpt(block, existing.originalBlockData, existing.matchedVariantKey,
                existing.blockRotation, existing.tintArgb, tree,
                existing.fillMode(), existing.displayMode());
        } else {
            final BlockData base = OctreeCellEditor.dominantMaterial(tree);
            final VariantResolution variant = plugin.getHeadResolver()
                .resolveVariant(base, Math.max(2, grid));
            target = newSculpt(block, base, variant.matchedVariant(), variant.rotation(),
                null, tree, strategies.fillMode(), strategies.displayMode());
        }
        return installSculpt(block, existing, target, before);
    }

    // =====================================================================
    //  Snapshot restoration (undo / redo)
    // =====================================================================

    /**
     * Restore {@code target} if the position still shows {@code expected}.
     * The returned outcome's {@code after} is the state actually installed.
     */
    Outcome restore(
            final Player player,
            final Block block,
            final BlockSnapshot expected,
            final BlockSnapshot target) {
        if (hasInactiveRegistration(block)) return Outcome.of(Status.STALE);
        final SculptBlock existing = activeSculpt(block);
        final BlockData worldData = block.getBlockData();
        if (!expected.matches(worldData, existing)) return Outcome.of(Status.STALE);
        if (!plugin.canPlayerBuild(player, block)) return Outcome.of(Status.PROTECTED);

        final BlockSnapshot before = existing == null
            ? BlockSnapshot.regular(worldData)
            : BlockSnapshot.sculpt(worldData, existing);
        if (target.kind() == BlockSnapshot.Kind.REGULAR) {
            writeVanilla(block, existing, target.worldData());
        } else {
            final BlockSnapshot.SculptState state = target.sculptState();
            final SculptBlock restored = newSculpt(block, state.originalBlockData(),
                state.matchedVariantKey(), state.rotation(), state.tintArgb(),
                OctreeCellEditor.copy(state.tree(), state.originalBlockData()),
                state.fillMode(), state.displayMode());
            restored.setMixed(state.mixed());
            final Status status = installSculpt(block, existing, restored, before);
            if (status != Status.CHANGED) return Outcome.of(status);
        }
        return new Outcome(Status.CHANGED, before, capture(block), target.workUnits());
    }

    // =====================================================================
    //  World mutation
    // =====================================================================

    private void writeVanilla(
            final Block block,
            final SculptBlock existing,
            final BlockData data) {
        block.setBlockData(data, false);
        if (existing != null) existing.despawn();
    }

    private SculptBlock newSculpt(
            final Block block,
            final BlockData original,
            final String variantKey,
            final Quaternionf rotation,
            final Integer tint,
            final OctreeNode tree,
            final FillMode fillMode,
            final SculptDisplayMode displayMode) {
        final World world = block.getWorld();
        final Location location = block.getLocation();
        final SculptBlock sculpt = tint == null
            ? new SculptBlock(world, location, original.clone(), variantKey,
                new Quaternionf(rotation), new BukkitTransportSession(world),
                plugin.getHeadResolver())
            : new SculptBlock(world, location, original.clone(), variantKey,
                new Quaternionf(rotation), new BukkitTransportSession(world),
                plugin.getHeadResolver(), tint);
        OctreeCellEditor.copyInto(sculpt.root, tree, original);
        sculpt.rebuildCollisionTopology();
        sculpt.setMixed(sculpt.recomputeMixedState());
        sculpt.configureStrategies(fillMode, displayMode, plugin.getTextBlockRenderer());
        final BlockPosKey key = BlockPosKey.of(block);
        sculpt.setOnCleared(() -> plugin.unregisterSculptBlock(key, sculpt));
        return sculpt;
    }

    private Status installSculpt(
            final Block block,
            final SculptBlock existing,
            final SculptBlock target,
            final BlockSnapshot before) {
        final BlockPosKey key = BlockPosKey.of(block);
        final BlockData previousWorldData = block.getBlockData().clone();
        if (existing == null) {
            if (!plugin.registerSculptBlock(key, target)) return Status.LIMIT;
            try {
                target.enterSculpted();
            } catch (final RuntimeException failure) {
                target.despawn();
                plugin.unregisterSculptBlock(key, target);
                block.setBlockData(previousWorldData, false);
                throw failure;
            }
            return Status.CHANGED;
        }

        if (!plugin.replaceSculptBlock(key, existing, target)) return Status.STALE;
        try {
            existing.despawn();
            target.enterSculpted();
        } catch (final RuntimeException failure) {
            target.despawn();
            plugin.unregisterSculptBlock(key, target);
            try {
                rollback(block, before);
            } catch (final RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        return Status.CHANGED;
    }

    private void rollback(final Block block, final BlockSnapshot before) {
        if (before.kind() == BlockSnapshot.Kind.REGULAR) {
            block.setBlockData(before.worldData(), false);
            return;
        }
        final BlockSnapshot.SculptState state = before.sculptState();
        final SculptBlock restored = newSculpt(block, state.originalBlockData(),
            state.matchedVariantKey(), state.rotation(), state.tintArgb(),
            OctreeCellEditor.copy(state.tree(), state.originalBlockData()),
            state.fillMode(), state.displayMode());
        restored.setMixed(state.mixed());
        final BlockPosKey key = BlockPosKey.of(block);
        if (!plugin.restoreSculptBlock(key, restored)) {
            block.setBlockData(before.worldData(), false);
            return;
        }
        try {
            restored.enterSculpted();
        } catch (final RuntimeException failure) {
            restored.despawn();
            plugin.unregisterSculptBlock(key, restored);
            block.setBlockData(before.worldData(), false);
            throw failure;
        }
    }

    // =====================================================================
    //  Classification helpers
    // =====================================================================

    /** Air, fluids, grass, and other blocks a placement would overwrite. */
    static boolean isReplaceable(final Block block) {
        return block.getType().isAir() || block.isReplaceable();
    }

    /**
     * Regular blocks can join a cell edit when the server allows converting
     * normal blocks and they are plain full cubes the active display can
     * render. Block entities are never converted, so a build cannot silently
     * delete container contents or other block state.
     */
    private boolean isConvertible(final Block block, final SculptDisplayMode displayMode) {
        return isConvertible(block) && plugin.isMaterialSupported(block.getType(), displayMode);
    }

    /** Shape and configuration checks for converting a regular block. */
    boolean isConvertible(final Block block) {
        if (!plugin.sculptConfig().blockBreakListenerEnabled()) return false;
        if (block.getState(false) instanceof TileState) return false;
        final var boxes = block.getCollisionShape().getBoundingBoxes();
        if (boxes.size() != 1) return false;
        final BoundingBox box = boxes.iterator().next();
        return box.getMinX() <= 0.0 && box.getMinY() <= 0.0 && box.getMinZ() <= 0.0
            && box.getMaxX() >= 1.0 && box.getMaxY() >= 1.0 && box.getMaxZ() >= 1.0;
    }

    /** Only occluding full blocks collapse back to one vanilla block. */
    static boolean canBeVanillaBlock(final BlockData material) {
        if (material instanceof Slab slab) return slab.getType() == Slab.Type.DOUBLE;
        return material.getMaterial().isOccluding();
    }
}
