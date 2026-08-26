package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Slab;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FaceDir;

class HoverEnginePartialBlockTest {

    @Test
    void bottomSlabTopSurfaceSelectsTheOccupiedLowerCell() {
        final Block block = slabAt(10, 64, -3, Slab.Type.BOTTOM);

        final VirtualGridHit hit = HoverEngine.traceNormalAtHit(
            2, block, FaceDir.UP, new Vector(10.75, 64.5, -2.25));

        assertEquals(1, hit.pgx());
        assertEquals(0, hit.pgy());
        assertEquals(1, hit.pgz());
    }

    @Test
    void topSlabBottomSurfaceSelectsTheOccupiedUpperCell() {
        final Block block = slabAt(10, 64, -3, Slab.Type.TOP);

        final VirtualGridHit hit = HoverEngine.traceNormalAtHit(
            2, block, FaceDir.DOWN, new Vector(10.25, 64.5, -2.75));

        assertEquals(0, hit.pgx());
        assertEquals(1, hit.pgy());
        assertEquals(0, hit.pgz());
    }

    @Test
    void fullCubeTopSurfaceStillSelectsTheTopCell() {
        final Block block = blockAt(10, 64, -3, null);

        final VirtualGridHit hit = HoverEngine.traceNormalAtHit(
            2, block, FaceDir.UP, new Vector(10.25, 65.0, -2.75));

        assertEquals(1, hit.pgy());
    }

    @Test
    void bottomSlabSideHitCannotSelectItsEmptyUpperHalf() {
        final Block block = slabAt(10, 64, -3, Slab.Type.BOTTOM);

        final VirtualGridHit hit = HoverEngine.traceNormalAtHit(
            4, block, FaceDir.NORTH, new Vector(10.25, 64.9, -3.0));

        assertEquals(1, hit.pgy());
    }

    @Test
    void topSlabSideHitCannotSelectItsEmptyLowerHalf() {
        final Block block = slabAt(10, 64, -3, Slab.Type.TOP);

        final VirtualGridHit hit = HoverEngine.traceNormalAtHit(
            4, block, FaceDir.NORTH, new Vector(10.25, 64.1, -3.0));

        assertEquals(2, hit.pgy());
    }

    private static Block slabAt(
            final int x,
            final int y,
            final int z,
            final Slab.Type type) {
        return blockAt(x, y, z, slabData(type));
    }

    private static Block blockAt(
            final int x,
            final int y,
            final int z,
            final Slab slab) {
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(), new Class<?>[]{Block.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getBlockData" -> slab;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> defaultValue(method.getReturnType());
            });
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
                case "clone" -> slabData(type.get());
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
