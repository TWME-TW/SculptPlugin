package dev.twme.sculpt.integration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/**
 * WorldEdit {@code Extent} wrapper that tracks BARRIER
 * block placements and created Sculpt root entities during paste operations.
 *
 * <p>The concrete class name is intentional: FAWE uses it to apply the
 * {@code extent.allowed-plugins} allow-list. All calls otherwise delegate to
 * the original extent.</p>
 *
 * <p>The tracked positions are used after the edit session completes to
 * reconcile the destination chunk's complete Sculpt entity graph.</p>
 */
public class SculptPasteExtent extends AbstractDelegateExtent {

    private final Set<BlockVector3> trackedPositions = ConcurrentHashMap.newKeySet();

    /**
     * Create a named wrapper around the original WorldEdit extent.
     *
     * @param originalExtent the original WE Extent to wrap
     */
    public SculptPasteExtent(Extent originalExtent) {
        super(originalExtent);
    }

    /**
     * Returns a snapshot of tracked positions.
     */
    public Set<BlockVector3> getTrackedPositions() {
        return new HashSet<>(trackedPositions);
    }

    /**
     * Whether any relevant block placement or entity creation was tracked.
     */
    public boolean isDirty() {
        return !trackedPositions.isEmpty();
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(
            BlockVector3 position, T block) throws WorldEditException {
        trackIfBarrier(position, block);
        return super.setBlock(position, block);
    }

    /**
     * Overrides FAWE's runtime-only integer-coordinate overload while keeping
     * compilation compatible with the vanilla WorldEdit API.
     */
    public <T extends BlockStateHolder<T>> boolean setBlock(
            int x, int y, int z, T block) throws WorldEditException {
        BlockVector3 position = BlockVector3.at(x, y, z);
        trackIfBarrier(position, block);
        return getExtent().setBlock(position, block);
    }

    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        Entity created = super.createEntity(location, entity);
        if (created != null && SculptClipboardEntityFilter.isSculptRoot(entity)) {
            trackedPositions.add(BlockVector3.at(
                    location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        }
        return created;
    }

    private void trackIfBarrier(BlockVector3 position, BlockStateHolder<?> state) {
        if ("minecraft:barrier".equals(state.getBlockType().id())) {
            trackedPositions.add(position);
        }
    }
}
