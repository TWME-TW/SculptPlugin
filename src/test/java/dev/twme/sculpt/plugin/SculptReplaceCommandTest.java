package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.editor.RegionSelection;

class SculptReplaceCommandTest {

    @Test
    void normalizesBareAndNamespacedBlockData() {
        assertEquals("minecraft:stone",
            SculptReplaceCommand.normalizeBlockData("STONE"));
        assertEquals("minecraft:oak_log[axis=x]",
            SculptReplaceCommand.normalizeBlockData("oak_log[axis=X]"));
        assertEquals("example:block[state=true]",
            SculptReplaceCommand.normalizeBlockData("example:block[state=true]"));
    }

    @Test
    void bakeableTargetMustBeANonAirAllowedBlock() {
        assertTrue(SculptReplaceCommand.isBakeableTarget(
            Material.STONE, true, false, ignored -> false));
        assertFalse(SculptReplaceCommand.isBakeableTarget(
            Material.AIR, true, true, ignored -> false));
        assertFalse(SculptReplaceCommand.isBakeableTarget(
            Material.STICK, false, false, ignored -> false));
        assertFalse(SculptReplaceCommand.isBakeableTarget(
            Material.STONE, true, false, material -> material == Material.STONE));
    }

    @Test
    void slabTargetIsNormalizedToAFullDoubleSlab() {
        final AtomicReference<Slab.Type> type =
            new AtomicReference<>(Slab.Type.TOP);
        final Slab slab = slab(type);

        final BlockData normalized =
            SculptReplaceCommand.normalizeTargetBlockData(slab);

        assertEquals(Slab.Type.DOUBLE, ((Slab) normalized).getType());
    }

    @Test
    void selectedVolumeIsPartitionedIntoChunkOwnedBoundedBatches() {
        final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            });
        final RegionSelection selection = new RegionSelection(
            new Location(world, -1, 0, -1),
            new Location(world, 16, 20, 16));

        final List<SculptReplaceCommand.RegionBatch> batches =
            SculptReplaceCommand.createBatches(selection);

        assertEquals(selection.volume(),
            batches.stream().mapToLong(SculptReplaceCommand.RegionBatch::volume).sum());
        assertTrue(batches.stream().allMatch(batch ->
            batch.volume() <= SculptReplaceCommand.MAX_BLOCKS_PER_BATCH));
        assertTrue(batches.stream().allMatch(batch ->
            batch.minX() >> 4 == batch.chunkX()
                && batch.maxX() >> 4 == batch.chunkX()
                && batch.minZ() >> 4 == batch.chunkZ()
                && batch.maxZ() >> 4 == batch.chunkZ()));
    }

    private static Slab slab(final AtomicReference<Slab.Type> type) {
        return (Slab) Proxy.newProxyInstance(
            Slab.class.getClassLoader(), new Class<?>[]{Slab.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "clone" -> slab(new AtomicReference<>(type.get()));
                case "getType" -> type.get();
                case "setType" -> {
                    type.set((Slab.Type) args[0]);
                    yield null;
                }
                case "getMaterial" -> Material.SMOOTH_STONE_SLAB;
                case "getAsString" -> "minecraft:smooth_stone_slab[type="
                    + type.get().name().toLowerCase() + "]";
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
