package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.assets.shape.VoxelMask;

class OctreeVoxelShapeTest {

    @Test
    void maskRoundTripUsesMaximalOctreeLeaves() {
        final VoxelMask.Builder mask = VoxelMask.builder();
        for (int y = 0; y < 8; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) mask.set(x, y, z);
            }
        }
        final VoxelMask lowerHalf = mask.build();
        final OctreeNode root = new OctreeNode();

        OctreeVoxelShape.initialize(root, lowerHalf, blockData());

        assertEquals(lowerHalf, OctreeVoxelShape.maskOf(root));
        assertEquals(4, OctreeVoxelShape.occupiedLeafCount(root));
        assertEquals(4, lowerHalf.compressedOccupiedLeafCount());
        assertEquals(8, OctreeVoxelShape.allLeafCount(root));
        assertTrue(root.collectLeaves().stream().allMatch(leaf -> leaf.minY() == 0));
    }

    private static BlockData blockData() {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "clone" -> proxy;
                case "getMaterial" -> Material.STONE;
                case "getAsString" -> "minecraft:stone";
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
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
