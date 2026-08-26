package dev.twme.sculpt.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical, material-independent collision topology for a SculptBlock.
 *
 * <p>The display octree stores block data and texture information. This tree
 * projects only whether each volume is occupied. Eight homogeneous child
 * volumes are collapsed recursively, so every occupied leaf is one maximal
 * octree-aligned cube. A fully occupied root is represented by one barrier
 * block; occupied leaves in a partial tree are represented by collision
 * entities.
 */
public final class CollisionOctree {

    private final Node root;
    private final List<Node> occupiedLeaves;

    private CollisionOctree(final Node root) {
        this.root = root;
        final List<Node> leaves = new ArrayList<>();
        root.collectOccupiedLeaves(leaves);
        this.occupiedLeaves = List.copyOf(leaves);
    }

    /** Build the canonical collision projection of a display octree. */
    public static CollisionOctree from(final OctreeNode displayRoot) {
        Objects.requireNonNull(displayRoot, "displayRoot");
        return new CollisionOctree(project(displayRoot));
    }

    public Node root() {
        return root;
    }

    /** Canonical occupied leaves in the projected topology. */
    public List<Node> occupiedLeaves() {
        return occupiedLeaves;
    }

    public int occupiedLeafCount() {
        return occupiedLeaves.size();
    }

    public boolean isEmpty() {
        return occupiedLeaves.isEmpty();
    }

    /** Whether the entire source block volume is occupied. */
    public boolean isFullyOccupied() {
        return root.isOccupied();
    }

    /** Total occupied volume in grid-16 voxels. */
    public int occupiedVolume() {
        int volume = 0;
        for (final Node leaf : occupiedLeaves) {
            volume += leaf.side * leaf.side * leaf.side;
        }
        return volume;
    }

    /** Find the canonical node at an exact dot-separated octant path. */
    public Node nodeAtPath(final String path) {
        if (path == null) return null;
        if (path.isEmpty()) return root;

        Node node = root;
        for (final String segment : path.split("\\.")) {
            if (node.children == null) return null;
            final int index;
            try {
                index = Integer.parseInt(segment);
            } catch (final NumberFormatException ignored) {
                return null;
            }
            if (index < 0 || index >= 8) return null;
            node = node.children[index];
        }
        return node.path.equals(path) ? node : null;
    }

    /** Return whether a grid-16 coordinate is inside occupied volume. */
    public boolean isOccupiedAt(final int x, final int y, final int z) {
        if (x < 0 || x >= 16 || y < 0 || y >= 16 || z < 0 || z >= 16) {
            return false;
        }
        Node node = root;
        while (node.children != null) {
            final int half = node.side / 2;
            int index = 0;
            if (x >= node.minX + half) index |= 4;
            if (y >= node.minY + half) index |= 2;
            if (z >= node.minZ + half) index |= 1;
            node = node.children[index];
        }
        return node.occupied;
    }

    private static Node project(final OctreeNode displayNode) {
        if (displayNode.isLeaf()) {
            return Node.leaf(displayNode.pathAsString(), displayNode.depth(),
                displayNode.minX(), displayNode.minY(), displayNode.minZ(),
                displayNode.side(), !displayNode.isRemoved());
        }
        if (!displayNode.hasAnyRemoved()) {
            return Node.leaf(displayNode.pathAsString(), displayNode.depth(),
                displayNode.minX(), displayNode.minY(), displayNode.minZ(),
                displayNode.side(), true);
        }
        if (displayNode.allRemoved()) {
            return Node.leaf(displayNode.pathAsString(), displayNode.depth(),
                displayNode.minX(), displayNode.minY(), displayNode.minZ(),
                displayNode.side(), false);
        }

        final Node[] children = new Node[8];
        boolean homogeneousLeaves = true;
        Boolean occupied = null;
        for (int index = 0; index < children.length; index++) {
            children[index] = project(displayNode.children()[index]);
            final Node child = children[index];
            if (!child.isLeaf()) {
                homogeneousLeaves = false;
            } else if (occupied == null) {
                occupied = child.occupied;
            } else if (occupied.booleanValue() != child.occupied) {
                homogeneousLeaves = false;
            }
        }

        if (homogeneousLeaves) {
            return Node.leaf(displayNode.pathAsString(), displayNode.depth(),
                displayNode.minX(), displayNode.minY(), displayNode.minZ(),
                displayNode.side(), occupied != null && occupied);
        }
        return Node.branch(displayNode.pathAsString(), displayNode.depth(),
            displayNode.minX(), displayNode.minY(), displayNode.minZ(),
            displayNode.side(), children);
    }

    /** Immutable node in the canonical collision tree. */
    public static final class Node {
        private final String path;
        private final int depth;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int side;
        private final boolean occupied;
        private final Node[] children;

        private Node(final String path, final int depth,
                     final int minX, final int minY, final int minZ,
                     final int side, final boolean occupied,
                     final Node[] children) {
            this.path = path;
            this.depth = depth;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.side = side;
            this.occupied = occupied;
            this.children = children;
        }

        private static Node leaf(final String path, final int depth,
                                 final int minX, final int minY, final int minZ,
                                 final int side, final boolean occupied) {
            return new Node(path, depth, minX, minY, minZ, side, occupied, null);
        }

        private static Node branch(final String path, final int depth,
                                   final int minX, final int minY, final int minZ,
                                   final int side, final Node[] children) {
            return new Node(path, depth, minX, minY, minZ, side, false, children);
        }

        public String path() { return path; }
        public int depth() { return depth; }
        public int minX() { return minX; }
        public int minY() { return minY; }
        public int minZ() { return minZ; }
        public int side() { return side; }
        public int[] minCorner() { return new int[]{minX, minY, minZ}; }
        public boolean isLeaf() { return children == null; }
        public boolean isBranch() { return children != null; }
        public boolean isOccupied() { return isLeaf() && occupied; }
        public Node[] children() { return children == null ? null : children.clone(); }

        private void collectOccupiedLeaves(final List<Node> output) {
            if (children == null) {
                if (occupied) output.add(this);
                return;
            }
            for (final Node child : children) child.collectOccupiedLeaves(output);
        }

        @Override
        public String toString() {
            return "CollisionNode{path=" + path + ", depth=" + depth
                + ", leaf=" + isLeaf() + ", occupied=" + occupied + "}";
        }
    }
}
