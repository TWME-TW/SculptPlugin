package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.block.data.BlockData;

import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;

/**
 * Data-only editing of detached Sculpt octrees.
 *
 * <p>Building operations never mutate a live {@link dev.twme.sculpt.core.SculptBlock}
 * tree cell by cell (that would spawn and destroy display entities for every
 * intermediate step). Instead they edit a detached copy, collapse it to its
 * canonical form, and install the result as a single replacement.
 */
public final class OctreeCellEditor {

    private static final int GRID16 = 16;

    /** Shape of an edited tree after canonicalization. */
    public enum Occupancy { EMPTY, FULL_UNIFORM, PARTIAL }

    /** Counters reported by {@link #apply}. */
    public record Result(boolean changed, int lockedCells) {}

    private OctreeCellEditor() {}

    // =====================================================================
    //  Construction and copying
    // =====================================================================

    /** A full tree occupied by one material, as for a regular block. */
    public static OctreeNode full(final BlockData material) {
        final OctreeNode root = new OctreeNode();
        root.setBlockData(material.clone());
        return root;
    }

    /** An empty tree whose removed cells remember {@code material}. */
    public static OctreeNode empty(final BlockData material) {
        final OctreeNode root = new OctreeNode();
        root.setBlockData(material.clone());
        root.remove();
        return root;
    }

    /**
     * Deep copy {@code source}. Leaves without their own material inherit
     * {@code fallback}, matching how SculptBlocks resolve a null leaf material.
     */
    public static OctreeNode copy(final OctreeNode source, final BlockData fallback) {
        final OctreeNode copy = new OctreeNode();
        copyInto(copy, source, fallback);
        return copy;
    }

    /** Copy {@code source} into a new, unsubdivided leaf {@code destination}. */
    public static void copyInto(
            final OctreeNode destination,
            final OctreeNode source,
            final BlockData fallback) {
        final BlockData data = source.blockData() != null
            ? source.blockData() : fallback;
        if (data != null) destination.setBlockData(data.clone());
        destination.setTextureCoord(source.textureCoord());
        if (source.isBranch()) {
            destination.subdivide();
            for (int index = 0; index < 8; index++) {
                copyInto(destination.children()[index], source.children()[index], data);
            }
            return;
        }
        if (destination.depth() > 0) {
            destination.setPlayerHeadTexture(source.playerHeadTexture());
        }
        if (source.isRemoved()) destination.remove();
    }

    // =====================================================================
    //  Editing
    // =====================================================================

    /** Apply every layer of {@code edit} to the detached tree. */
    public static Result apply(final OctreeNode root, final BlockCellEdit edit) {
        final int grid = edit.grid();
        final int depth = Integer.numberOfTrailingZeros(grid);
        final int side = GRID16 / grid;
        boolean changed = false;
        int locked = 0;
        for (final BlockCellEdit.Layer layer : edit.layers()) {
            final BitSet cells = layer.cells();
            for (int index = cells.nextSetBit(0); index >= 0;
                    index = cells.nextSetBit(index + 1)) {
                final CellStatus status = setCell(root, depth,
                    CellVolume.localX(grid, index) * side,
                    CellVolume.localY(grid, index) * side,
                    CellVolume.localZ(grid, index) * side,
                    layer.operation(), layer.material(),
                    depth == 0 ? null : layer.headTexture());
                if (status == CellStatus.CHANGED) changed = true;
                else if (status == CellStatus.LOCKED) locked++;
            }
        }
        return new Result(changed, locked);
    }

    private enum CellStatus { CHANGED, UNCHANGED, LOCKED }

    private static CellStatus setCell(
            final OctreeNode root,
            final int targetDepth,
            final int gx,
            final int gy,
            final int gz,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final PlayerHeadTexture texture) {
        OctreeNode node = root;
        while (node.depth() < targetDepth) {
            if (node.isLeaf()) {
                if (!needsChange(node, operation, material, texture)) {
                    return CellStatus.UNCHANGED;
                }
                if (!refine(node)) return CellStatus.LOCKED;
            }
            node = childContaining(node, gx, gy, gz);
        }

        if (node.isBranch()) {
            if (operation == BlockCellEdit.Operation.PAINT && texture == null) {
                return paintLeaves(node, material)
                    ? CellStatus.CHANGED : CellStatus.UNCHANGED;
            }
            if (operation == BlockCellEdit.Operation.PAINT
                    && !anyLeafOccupied(node)) {
                return CellStatus.UNCHANGED;
            }
            if (!anyLeafNeedsChange(node, operation, material, texture)) {
                return CellStatus.UNCHANGED;
            }
            node.coarsen();
            node.setTextureCoord(null);
            // A coarsened branch is always an occupied leaf; the operation
            // below establishes its final state.
            node.setBlockData(material != null ? material.clone()
                : firstLeafMaterial(node));
            node.restore();
            applyToLeaf(node, operation, material, texture);
            return CellStatus.CHANGED;
        }

        if (!needsChange(node, operation, material, texture)) return CellStatus.UNCHANGED;
        applyToLeaf(node, operation, material, texture);
        return CellStatus.CHANGED;
    }

    /**
     * Split a leaf into eight children that preserve its state. Occupied
     * player-head cells are atomic and cannot be split.
     */
    private static boolean refine(final OctreeNode leaf) {
        final boolean removed = leaf.isRemoved();
        if (leaf.playerHeadTexture() != null) {
            if (!removed) return false;
            leaf.setPlayerHeadTexture(null);
        }
        leaf.subdivide();
        for (final OctreeNode child : leaf.children()) {
            if (removed) child.remove();
        }
        return true;
    }

    private static OctreeNode childContaining(
            final OctreeNode node,
            final int gx,
            final int gy,
            final int gz) {
        final int half = node.side() / 2;
        int child = 0;
        if (gx >= node.minX() + half) child |= 4;
        if (gy >= node.minY() + half) child |= 2;
        if (gz >= node.minZ() + half) child |= 1;
        return node.children()[child];
    }

    private static boolean needsChange(
            final OctreeNode leaf,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final PlayerHeadTexture texture) {
        return switch (operation) {
            case ADD -> leaf.isRemoved() || !materialMatches(leaf, material, texture);
            case CARVE -> !leaf.isRemoved();
            case PAINT -> !leaf.isRemoved() && !materialMatches(leaf, material, texture);
        };
    }

    private static boolean materialMatches(
            final OctreeNode leaf,
            final BlockData material,
            final PlayerHeadTexture texture) {
        return Objects.equals(leaf.blockData(), material)
            && Objects.equals(leaf.playerHeadTexture(), texture)
            && leaf.textureCoord() == null;
    }

    private static boolean anyLeafOccupied(final OctreeNode node) {
        if (node.isLeaf()) return !node.isRemoved();
        for (final OctreeNode child : node.children()) {
            if (anyLeafOccupied(child)) return true;
        }
        return false;
    }

    private static boolean anyLeafNeedsChange(
            final OctreeNode node,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final PlayerHeadTexture texture) {
        if (node.isLeaf()) return needsChange(node, operation, material, texture);
        for (final OctreeNode child : node.children()) {
            if (anyLeafNeedsChange(child, operation, material, texture)) return true;
        }
        return false;
    }

    private static void applyToLeaf(
            final OctreeNode leaf,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final PlayerHeadTexture texture) {
        switch (operation) {
            case ADD -> {
                clearTexture(leaf);
                leaf.setBlockData(material.clone());
                if (texture != null && leaf.depth() > 0) leaf.setPlayerHeadTexture(texture);
                leaf.restore();
            }
            case CARVE -> leaf.remove();
            case PAINT -> {
                if (leaf.isRemoved()) return;
                clearTexture(leaf);
                leaf.setBlockData(material.clone());
                if (texture != null && leaf.depth() > 0) leaf.setPlayerHeadTexture(texture);
            }
        }
    }

    private static boolean paintLeaves(final OctreeNode node, final BlockData material) {
        if (node.isLeaf()) {
            if (!needsChange(node, BlockCellEdit.Operation.PAINT, material, null)) return false;
            applyToLeaf(node, BlockCellEdit.Operation.PAINT, material, null);
            return true;
        }
        boolean changed = false;
        for (final OctreeNode child : node.children()) {
            changed |= paintLeaves(child, material);
        }
        return changed;
    }

    private static void clearTexture(final OctreeNode leaf) {
        if (leaf.depth() > 0) leaf.setPlayerHeadTexture(null);
        leaf.setTextureCoord(null);
    }

    private static BlockData firstLeafMaterial(final OctreeNode node) {
        return node.blockData() == null ? null : node.blockData().clone();
    }

    // =====================================================================
    //  Canonical form and classification
    // =====================================================================

    /**
     * Merge every branch whose eight leaves are indistinguishable. Removed
     * leaves only merge when they remember the same material.
     */
    public static void canonicalize(final OctreeNode node) {
        if (node.isLeaf()) return;
        for (final OctreeNode child : node.children()) canonicalize(child);

        final OctreeNode first = node.children()[0];
        if (first.isBranch() || first.playerHeadTexture() != null
                || first.textureCoord() != null) return;
        for (final OctreeNode child : node.children()) {
            if (child.isBranch()
                    || child.isRemoved() != first.isRemoved()
                    || child.playerHeadTexture() != null
                    || child.textureCoord() != null
                    || !Objects.equals(child.blockData(), first.blockData())) {
                return;
            }
        }
        final boolean removed = first.isRemoved();
        final BlockData data = first.blockData();
        node.coarsen();
        node.setBlockData(data);
        node.setTextureCoord(null);
        if (removed) node.remove();
        else node.restore();
    }

    public static Occupancy occupancy(final OctreeNode root) {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        BlockData shared = null;
        boolean any = false;
        boolean uniform = true;
        for (final OctreeNode leaf : leaves) {
            if (leaf.isRemoved()) {
                uniform = false;
                continue;
            }
            if (leaf.playerHeadTexture() != null || leaf.textureCoord() != null) {
                uniform = false;
            }
            if (!any) {
                shared = leaf.blockData();
                any = true;
            } else if (!Objects.equals(shared, leaf.blockData())) {
                uniform = false;
            }
        }
        if (!any) return Occupancy.EMPTY;
        return uniform ? Occupancy.FULL_UNIFORM : Occupancy.PARTIAL;
    }

    /** Material covering the most occupied volume, or {@code null} when empty. */
    public static BlockData dominantMaterial(final OctreeNode root) {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        final Map<BlockData, Long> volume = new HashMap<>();
        BlockData best = null;
        long bestVolume = -1L;
        for (final OctreeNode leaf : leaves) {
            if (leaf.isRemoved() || leaf.blockData() == null) continue;
            final long side = leaf.side();
            final long total = volume.merge(leaf.blockData(), side * side * side, Long::sum);
            if (total > bestVolume) {
                bestVolume = total;
                best = leaf.blockData();
            }
        }
        return best;
    }

    /** Material of any occupied leaf, used when a tree is fully uniform. */
    public static BlockData uniformMaterial(final OctreeNode root) {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            if (!leaf.isRemoved()) return leaf.blockData();
        }
        return null;
    }

    /** Material of the occupied cell at grid-16 coordinates, or {@code null} when empty. */
    public static BlockData materialAt(
            final OctreeNode root,
            final int gx,
            final int gy,
            final int gz) {
        final OctreeNode leaf = root.findLeaf(gx, gy, gz);
        return leaf == null || leaf.isRemoved() ? null : leaf.blockData();
    }

    /** Number of occupied leaves, used to budget display-entity work. */
    public static int occupiedLeaves(final OctreeNode root) {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        int count = 0;
        for (final OctreeNode leaf : leaves) {
            if (!leaf.isRemoved()) count++;
        }
        return count;
    }
}
