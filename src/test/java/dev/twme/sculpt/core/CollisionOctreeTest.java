package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Shulker;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class CollisionOctreeTest {

    @Test
    void mixedDisplayMaterialsCollapseIntoOneCollisionLeaf() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        for (int index = 0; index < 8; index++) {
            displayRoot.children()[index].setBlockData(materialMarker("material-" + index));
        }

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertTrue(collision.root().isLeaf());
        assertTrue(collision.root().isOccupied());
        assertEquals("", collision.root().path());
        assertEquals(16, collision.root().side());
        assertTrue(collision.isFullyOccupied());
        assertFalse(collision.isEmpty());
        assertEquals(1, collision.occupiedLeafCount());
        assertEquals(4096, collision.occupiedVolume());
    }

    @Test
    void partialVolumeUsesMaximalOccupiedCubes() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        displayRoot.children()[0].subdivide();
        displayRoot.children()[0].children()[0].remove();

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertTrue(collision.root().isBranch());
        assertFalse(collision.isFullyOccupied());
        assertFalse(collision.isEmpty());
        assertEquals(14, collision.occupiedLeafCount());
        assertEquals(4096 - 64, collision.occupiedVolume());
        assertFalse(collision.isOccupiedAt(0, 0, 0));
        assertTrue(collision.isOccupiedAt(4, 0, 0));
        assertTrue(collision.isOccupiedAt(15, 15, 15));
    }

    @Test
    void fullyRemovedDisplayBranchCollapsesIntoOneEmptyRoot() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        for (final OctreeNode child : displayRoot.children()) child.remove();

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertTrue(collision.root().isLeaf());
        assertFalse(collision.root().isOccupied());
        assertFalse(collision.isFullyOccupied());
        assertTrue(collision.isEmpty());
        assertEquals(0, collision.occupiedLeafCount());
        assertEquals(0, collision.occupiedVolume());
    }

    @Test
    void projectionPreservesEveryGridVoxelAcrossMixedDepths() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        displayRoot.children()[0].remove();
        displayRoot.children()[7].subdivide();
        displayRoot.children()[7].children()[0].remove();
        displayRoot.children()[7].children()[7].subdivide();
        displayRoot.children()[7].children()[7].children()[3].remove();

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(!displayRoot.findLeaf(x, y, z).isRemoved(),
                        collision.isOccupiedAt(x, y, z),
                        "occupancy differs at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void restoringHomogeneousChildrenCoalescesRecursively() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        displayRoot.children()[0].subdivide();
        displayRoot.children()[0].children()[0].remove();
        assertEquals(14, CollisionOctree.from(displayRoot).occupiedLeafCount());

        displayRoot.children()[0].children()[0].restore();
        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertTrue(collision.root().isOccupied());
        assertEquals(1, collision.occupiedLeafCount());
    }

    @Test
    void singleMissingVoxelUsesCanonicalMinimalOctreeCover() {
        final OctreeNode displayRoot = new OctreeNode();
        OctreeNode node = displayRoot;
        for (int depth = 0; depth < 4; depth++) {
            node.subdivide();
            node = node.children()[0];
        }
        node.remove();

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertEquals(28, collision.occupiedLeafCount());
        assertEquals(4095, collision.occupiedVolume());
        assertFalse(collision.isOccupiedAt(0, 0, 0));
    }

    @Test
    void maximallyFragmentedDepthFourPatternHasBoundedLeafCount() {
        final OctreeNode displayRoot = new OctreeNode();
        subdivideToDepth(displayRoot, 4);
        for (final OctreeNode leaf : displayRoot.collectLeaves()) {
            if ((leaf.minX() & 1) == 0 && (leaf.minY() & 1) == 0
                    && (leaf.minZ() & 1) == 0) {
                leaf.remove();
            }
        }

        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertEquals(3584, collision.occupiedLeafCount());
        assertEquals(3584, collision.occupiedVolume());
    }

    @Test
    void exactPathLookupRejectsPathsHiddenByCanonicalCoarsening() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertNotNull(collision.nodeAtPath(""));
        assertNull(collision.nodeAtPath("0"));
        assertNull(collision.nodeAtPath("bad.path"));
        assertNull(collision.nodeAtPath("8"));
        assertNull(collision.nodeAtPath(null));
    }

    @Test
    void exactPathLookupRejectsAlternatePathSpellings() {
        final OctreeNode displayRoot = new OctreeNode();
        displayRoot.subdivide();
        displayRoot.children()[7].remove();
        final CollisionOctree collision = CollisionOctree.from(displayRoot);

        assertNotNull(collision.nodeAtPath("0"));
        assertNull(collision.nodeAtPath("0."));
        assertNull(collision.nodeAtPath("00"));
    }

    @Test
    void fullyOccupiedRootRejectsCollisionEntities() {
        final World world = interfaceMarker(World.class);
        final SculptBlock sculpt = new SculptBlock(world, new Location(world, 0, 0, 0),
            materialMarker("original"), "", new Quaternionf(), null, null, 0);
        sculpt.root.subdivide();
        for (int index = 0; index < 8; index++) {
            sculpt.root.children()[index].setBlockData(materialMarker("material-" + index));
        }
        sculpt.rebuildCollisionTopology();
        sculpt.setFillMode(FillMode.SHULKER);
        sculpt.state = SculptBlock.State.SCULPTED;

        final Shulker canonical = interfaceMarker(Shulker.class);
        assertTrue(sculpt.usesFullBlockCollision());
        assertFalse(sculpt.usesEntityCollision());
        assertFalse(sculpt.attachCollisionShulker("0", canonical));
        assertFalse(sculpt.attachCollisionShulker("", canonical));
        assertEquals(0, sculpt.collisionEntityCount());
        assertNull(sculpt.collisionShulker(""));
    }

    @Test
    void partialVolumeAttachesOnlyCanonicalCollisionLeaves() {
        final World world = interfaceMarker(World.class);
        final SculptBlock sculpt = new SculptBlock(world, new Location(world, 0, 0, 0),
            materialMarker("original"), "", new Quaternionf(), null, null, 0);
        sculpt.root.subdivide();
        sculpt.root.children()[7].remove();
        sculpt.rebuildCollisionTopology();
        sculpt.setFillMode(FillMode.SHULKER);
        sculpt.state = SculptBlock.State.SCULPTED;

        final Shulker canonical = interfaceMarker(Shulker.class);
        final Shulker duplicate = interfaceMarker(Shulker.class);

        assertFalse(sculpt.usesFullBlockCollision());
        assertTrue(sculpt.usesEntityCollision());
        assertFalse(sculpt.attachCollisionShulker("", canonical));
        assertTrue(sculpt.attachCollisionShulker("0", canonical));
        assertTrue(sculpt.attachCollisionShulker("0", canonical));
        assertFalse(sculpt.attachCollisionShulker("0", duplicate));
        assertEquals(1, sculpt.collisionEntityCount());
        assertSame(canonical, sculpt.collisionShulker("0"));
    }

    @Test
    void noFillUsesOnlyTheInteractionProxyForPartialContent() {
        final World world = interfaceMarker(World.class);
        final SculptBlock sculpt = new SculptBlock(world, new Location(world, 0, 0, 0),
            materialMarker("original"), "", new Quaternionf(), null, null, 0);
        sculpt.root.subdivide();
        sculpt.root.children()[7].remove();
        sculpt.rebuildCollisionTopology();
        sculpt.setFillMode(FillMode.NONE);
        sculpt.state = SculptBlock.State.SCULPTED;

        assertTrue(sculpt.usesEntityInteraction());
        assertFalse(sculpt.usesEntityCollision());
        assertFalse(sculpt.usesFullBlockCollision());
    }

    @Test
    void barrierFillUsesNeitherInteractionNorEntityCollision() {
        final World world = interfaceMarker(World.class);
        final SculptBlock sculpt = new SculptBlock(world, new Location(world, 0, 0, 0),
            materialMarker("original"), "", new Quaternionf(), null, null, 0);
        sculpt.root.subdivide();
        sculpt.root.children()[7].remove();
        sculpt.rebuildCollisionTopology();
        sculpt.setFillMode(FillMode.BARRIER);
        sculpt.state = SculptBlock.State.SCULPTED;

        assertFalse(sculpt.usesEntityInteraction());
        assertFalse(sculpt.usesEntityCollision());
        assertFalse(sculpt.usesFullBlockCollision());
    }

    private static BlockData materialMarker(final String id) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "clone" -> proxy;
                case "getAsString", "toString" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
    }

    private static void subdivideToDepth(final OctreeNode node, final int targetDepth) {
        if (node.depth() >= targetDepth) return;
        node.subdivide();
        for (final OctreeNode child : node.children()) {
            subdivideToDepth(child, targetDepth);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T interfaceMarker(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "isValid" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> type.getSimpleName() + "Marker";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
