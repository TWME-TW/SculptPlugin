package dev.twme.sculpt.core;

import java.util.Objects;

import org.bukkit.block.data.BlockData;

import dev.twme.sculpt.assets.shape.VoxelMask;

/** Converts between grid-16 occupancy masks and canonical Sculpt octrees. */
public final class OctreeVoxelShape {

    private OctreeVoxelShape() {}

    /** Populate a new leaf root with the smallest octree representing the mask. */
    public static void initialize(
            final OctreeNode root,
            final VoxelMask mask,
            final BlockData blockData) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(mask, "mask");
        Objects.requireNonNull(blockData, "blockData");
        if (!root.isLeaf() || root.depth() != 0) {
            throw new IllegalArgumentException("target must be a new root leaf");
        }
        root.setBlockData(blockData.clone());
        initializeNode(root, mask);
    }

    /** Project every non-removed display leaf into a grid-16 occupancy mask. */
    public static VoxelMask maskOf(final OctreeNode root) {
        Objects.requireNonNull(root, "root");
        final VoxelMask.Builder mask = VoxelMask.builder();
        project(root, mask);
        return mask.build();
    }

    public static int occupiedLeafCount(final OctreeNode root) {
        if (root.isLeaf()) return root.isRemoved() ? 0 : 1;
        int count = 0;
        for (final OctreeNode child : root.children()) {
            count += occupiedLeafCount(child);
        }
        return count;
    }

    public static int allLeafCount(final OctreeNode root) {
        if (root.isLeaf()) return 1;
        int count = 0;
        for (final OctreeNode child : root.children()) {
            count += allLeafCount(child);
        }
        return count;
    }

    private static void initializeNode(
            final OctreeNode node,
            final VoxelMask mask) {
        final Occupancy occupancy = occupancy(mask, node);
        if (occupancy == Occupancy.EMPTY) {
            node.remove();
            return;
        }
        if (occupancy == Occupancy.FULL || node.side() == 1) return;

        node.subdivide();
        for (final OctreeNode child : node.children()) {
            initializeNode(child, mask);
        }
    }

    private static Occupancy occupancy(
            final VoxelMask mask,
            final OctreeNode node) {
        boolean any = false;
        boolean all = true;
        final int maxX = node.minX() + node.side();
        final int maxY = node.minY() + node.side();
        final int maxZ = node.minZ() + node.side();
        for (int y = node.minY(); y < maxY; y++) {
            for (int z = node.minZ(); z < maxZ; z++) {
                for (int x = node.minX(); x < maxX; x++) {
                    final boolean occupied = mask.occupied(x, y, z);
                    any |= occupied;
                    all &= occupied;
                    if (any && !all) return Occupancy.MIXED;
                }
            }
        }
        return all ? Occupancy.FULL : Occupancy.EMPTY;
    }

    private static void project(
            final OctreeNode node,
            final VoxelMask.Builder output) {
        if (node.isLeaf()) {
            if (node.isRemoved()) return;
            final int maxX = node.minX() + node.side();
            final int maxY = node.minY() + node.side();
            final int maxZ = node.minZ() + node.side();
            for (int y = node.minY(); y < maxY; y++) {
                for (int z = node.minZ(); z < maxZ; z++) {
                    for (int x = node.minX(); x < maxX; x++) {
                        output.set(x, y, z);
                    }
                }
            }
            return;
        }
        for (final OctreeNode child : node.children()) project(child, output);
    }

    private enum Occupancy { EMPTY, FULL, MIXED }
}
