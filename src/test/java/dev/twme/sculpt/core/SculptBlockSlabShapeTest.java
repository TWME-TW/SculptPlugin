package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.block.data.type.Slab;
import org.junit.jupiter.api.Test;

class SculptBlockSlabShapeTest {

    @Test
    void bottomSlabInitializesOnlyTheLowerHalf() {
        final OctreeNode root = initialized(Slab.Type.BOTTOM);

        assertEquals(4, root.collectLeaves().size());
        assertTrue(root.collectLeaves().stream().allMatch(leaf -> leaf.minY() == 0));
        assertEquals(2048, CollisionOctree.from(root).occupiedVolume());
        assertTrue(SculptBlock.matchesSlabOccupancy(root, Slab.Type.BOTTOM));
        assertFalse(SculptBlock.matchesSlabOccupancy(root, Slab.Type.TOP));
    }

    @Test
    void topSlabInitializesOnlyTheUpperHalf() {
        final OctreeNode root = initialized(Slab.Type.TOP);

        assertEquals(4, root.collectLeaves().size());
        assertTrue(root.collectLeaves().stream().allMatch(leaf -> leaf.minY() == 8));
        assertEquals(2048, CollisionOctree.from(root).occupiedVolume());
        assertTrue(SculptBlock.matchesSlabOccupancy(root, Slab.Type.TOP));
        assertFalse(SculptBlock.matchesSlabOccupancy(root, Slab.Type.BOTTOM));
    }

    @Test
    void doubleSlabInitializesAsAFullBlock() {
        final OctreeNode root = initialized(Slab.Type.DOUBLE);

        assertEquals(8, root.collectLeaves().size());
        assertEquals(4096, CollisionOctree.from(root).occupiedVolume());
        assertTrue(SculptBlock.matchesSlabOccupancy(root, Slab.Type.DOUBLE));
    }

    @Test
    void fillingTheAbsentHalfProducesDoubleSlabOccupancy() {
        final Slab original = slabData(Slab.Type.BOTTOM);
        final OctreeNode root = initialized(original);
        final ArrayList<OctreeNode> allLeaves = new ArrayList<>();
        root.collectAllLeaves(allLeaves);
        allLeaves.forEach(OctreeNode::restore);

        assertTrue(SculptBlock.matchesSlabOccupancy(root, Slab.Type.DOUBLE));
        assertFalse(SculptBlock.matchesSlabOccupancy(root, Slab.Type.BOTTOM));
        assertEquals(Slab.Type.DOUBLE,
            ((Slab) SculptBlock.slabCompletionData(root, original)).getType());
    }

    @Test
    void originalHalfSlabShapeCanReturnToAVanillaSlab() {
        final Slab original = slabData(Slab.Type.TOP);
        final OctreeNode root = initialized(original);

        assertEquals(Slab.Type.TOP,
            ((Slab) SculptBlock.slabCompletionData(root, original)).getType());
        root.collectLeaves().getFirst().remove();
        assertNull(SculptBlock.slabCompletionData(root, original));
    }

    private static OctreeNode initialized(final Slab.Type type) {
        return initialized(slabData(type));
    }

    private static OctreeNode initialized(final Slab data) {
        final OctreeNode root = new OctreeNode();
        SculptBlock.initializeOriginalBlockShape(root, data);
        return root;
    }

    private static Slab slabData(final Slab.Type initialType) {
        final AtomicReference<Slab.Type> type = new AtomicReference<>(initialType);
        return (Slab) Proxy.newProxyInstance(
            Slab.class.getClassLoader(), new Class<?>[]{Slab.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getType" -> type.get();
                case "setType" -> {
                    type.set((Slab.Type) args[0]);
                    yield null;
                }
                case "getMaterial" -> Material.SMOOTH_STONE_SLAB;
                case "getAsString" -> "minecraft:smooth_stone_slab[type="
                    + type.get().name().toLowerCase() + "]";
                case "clone" -> slabData(type.get());
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "TestSlab[" + type.get() + "]";
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
