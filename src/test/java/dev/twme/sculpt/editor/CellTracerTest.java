package dev.twme.sculpt.editor;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.ChunkHead;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.VariantResolution;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.DisplayHandle;
import dev.twme.sculpt.transport.TransportSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CellTracerTest {

    @Test
    void hoverReadsOneViewSnapshotAndUsesOneCombinedWorldTrace() {
        final AtomicInteger eyeReads = new AtomicInteger();
        final AtomicInteger directionReads = new AtomicInteger();
        final AtomicInteger combinedTraces = new AtomicInteger();
        final AtomicInteger entityTraces = new AtomicInteger();
        final AtomicInteger blockTraces = new AtomicInteger();
        final World world = interfaceProxy(World.class, Map.of(
            "getName", args -> "world",
            "rayTrace", args -> {
                combinedTraces.incrementAndGet();
                return null;
            },
            "rayTraceEntities", args -> {
                entityTraces.incrementAndGet();
                return null;
            },
            "rayTraceBlocks", args -> {
                blockTraces.incrementAndGet();
                return null;
            }));
        final Location eye = new Location(world, 0.5, 65.5, 0.5) {
            @Override
            public Vector getDirection() {
                directionReads.incrementAndGet();
                return new Vector(1, 0, 0);
            }
        };
        final Player player = interfaceProxy(Player.class, Map.of(
            "getWorld", args -> world,
            "getEyeLocation", args -> {
                eyeReads.incrementAndGet();
                return eye;
            }));
        final CellTracer tracer = new CellTracer(player, 4, CellTracer.DEFAULT_REACH, key -> null);

        assertNull(tracer.trace());
        assertEquals(1, eyeReads.get());
        assertEquals(1, directionReads.get());
        assertEquals(1, combinedTraces.get());
        assertEquals(0, entityTraces.get());
        assertEquals(0, blockTraces.get());
    }

    @Test
    void eyeInsideSculptTargetsContainingCellBeforeExternalBlock() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(0, 64, 0, resolver);
        sculpt.leafAt(4, 12, 12).remove();
        rayWorld.setMaterial(1, 64, 0, Material.STONE);
        final Block external = rayWorld.block(1, 64, 0);
        rayWorld.setCombinedRayResult(new RayTraceResult(
            new Vector(1, 64.75, 0.75), external, BlockFace.WEST));

        final Player player = rayPlayer(
            rayWorld.world(), 0.25, 64.75, 0.75, new Vector(1, 0, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).trace();

        assertSame(sculpt, result.sculpt());
        assertEquals(1, result.hit().pgx());
        assertEquals(1, result.hit().pgy());
        assertEquals(1, result.hit().pgz());
        assertEquals(FaceDir.WEST, result.hit().face());
    }

    @Test
    void eyeInsideSculptCanStillTraceThroughAnEmptyPath() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(0, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(1, 64, 0, Material.STONE);
        final Block external = rayWorld.block(1, 64, 0);
        rayWorld.setCombinedRayResult(new RayTraceResult(
            new Vector(1, 64.75, 0.75), external, BlockFace.WEST));

        final Player player = rayPlayer(
            rayWorld.world(), 0.25, 64.75, 0.75, new Vector(1, 0, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).trace();

        assertNull(result.sculpt(), "the ray continues through the empty path");
        assertSame(external, result.hit().block());
        assertEquals(FaceDir.WEST, result.hit().face());
    }

    @Test
    void gapTraceHitsTheBlockBehindAnEmptyShulkerPath() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 2.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).traceBeyond(sculpt);

        assertSame(rayWorld.block(0, 64, 0), result.hit().block());
        assertEquals(FaceDir.EAST, result.hit().face(),
            "the cell in front of the face lies inside the traversed SculptBlock");
    }

    @Test
    void gapTraceWalksSeveralBlocksWithZeroDirectionComponents() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock first = rayWorld.addSculpt(2, 64, 0, resolver);
        final SculptBlock second = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(first);
        removeWestEastRayCells(second);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 3.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).traceBeyond(first);

        assertSame(rayWorld.block(0, 64, 0), result.hit().block());
        assertEquals(FaceDir.EAST, result.hit().face());
    }

    @Test
    void downwardGapTraceFindsTheCellBelowTheOppositeQuarter() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(0, 65, 0, resolver);
        final OctreeNode originalCell = sculpt.leafAt(4, 4, 4);
        for (final OctreeNode leaf : sculpt.root.children()) {
            if (leaf != originalCell) leaf.remove();
        }
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 0.75, 68.0, 0.75, new Vector(0, -1, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).traceBeyond(sculpt);

        assertSame(rayWorld.block(0, 64, 0), result.hit().block());
        assertEquals(FaceDir.UP, result.hit().face());
        assertEquals(1, result.hit().pgx());
        assertEquals(1, result.hit().pgz());
    }

    @Test
    void barrierGapUsesTheSameWorldGridTraversal() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(1, 64, 0, Material.BARRIER);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 2.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final CellTracer.Result result = tracer(player, rayWorld, 2).traceBeyond(sculpt);

        assertSame(rayWorld.block(0, 64, 0), result.hit().block());
    }

    @Test
    void gapRayUsesSlabCollisionAndFindsTheOccupiedLowerCell() {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(0, 64, 0, Material.SMOOTH_STONE_SLAB);

        final Player aboveSlab = rayPlayer(
            rayWorld.world(), 2.5, 64.75, 0.5, new Vector(-1, 0, 0));
        assertNull(tracer(aboveSlab, rayWorld, 2).traceBeyond(sculpt),
            "the empty upper half must not block the ray");

        final Player throughSlab = rayPlayer(
            rayWorld.world(), 2.5, 64.25, 0.5, new Vector(-1, 0, 0));
        final CellTracer.Result result = tracer(throughSlab, rayWorld, 2).traceBeyond(sculpt);

        assertEquals(0, result.hit().pgy());
    }

    private static SculptBlock sculptBlock(
            TestWorld testWorld, HeadResolver resolver) {
        return sculptBlock(
            testWorld.world(), new Location(testWorld.world(), 0, 64, 0), resolver);
    }

    private static SculptBlock sculptBlock(
            World world, Location location, HeadResolver resolver) {
        final SculptBlock sculpt = new SculptBlock(
            world, location,
            blockData(Material.STONE), "", new Quaternionf(),
            new EmptyTransportSession(), resolver, 0);
        sculpt.root.subdivide();
        for (final OctreeNode leaf : sculpt.root.children()) {
            leaf.setBlockData(blockData(Material.STONE));
        }
        sculpt.state = SculptBlock.State.SCULPTED;
        return sculpt;
    }

    private static void removeWestEastRayCells(final SculptBlock sculpt) {
        sculpt.leafAt(4, 12, 12).remove();
        sculpt.leafAt(12, 12, 12).remove();
    }

    private static CellTracer tracer(
            final Player player, final RayWorld world, final int gridSize) {
        return new CellTracer(player, gridSize, CellTracer.DEFAULT_REACH, world::sculptAt);
    }

    private static Player rayPlayer(
            final World world,
            final double x,
            final double y,
            final double z,
            final Vector direction) {
        final Location eye = new Location(world, x, y, z) {
            @Override
            public Vector getDirection() {
                return direction.clone();
            }
        };
        return interfaceProxy(Player.class, Map.of(
            "getEyeLocation", args -> eye,
            "getWorld", args -> world));
    }

    private static BlockData blockData(Material material) {
        if (material.name().endsWith("_SLAB")) {
            return slabData(material, Slab.Type.BOTTOM);
        }
        return interfaceProxy(BlockData.class, Map.of(
            "clone", args -> blockData(material),
            "getMaterial", args -> material,
            "getAsString", args -> material.getKey().toString()));
    }

    private static Slab slabData(
            final Material material,
            final Slab.Type initialType) {
        final AtomicReference<Slab.Type> type = new AtomicReference<>(initialType);
        return interfaceProxy(Slab.class, Map.of(
            "clone", args -> slabData(material, type.get()),
            "getMaterial", args -> material,
            "getType", args -> type.get(),
            "setType", args -> {
                type.set((Slab.Type) args[0]);
                return null;
            },
            "getAsString", args -> material.getKey()
                + "[type=" + type.get().name().toLowerCase() + "]"));
    }

    private static final class CountingHeadResolver implements HeadResolver {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChunkHead headFor(OctreeNode node, SculptBlock block) {
            calls.incrementAndGet();
            return new ChunkHead(null, null);
        }

        @Override
        public VariantResolution resolveVariant(BlockData data, int gridN) {
            calls.incrementAndGet();
            return new VariantResolution(new Quaternionf(), "");
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class TestWorld {
        private final AtomicReference<Material> material;
        private final World world;
        private final Block block;

        TestWorld(Material initialMaterial) {
            this.material = new AtomicReference<>(initialMaterial);
            final AtomicReference<Block> blockReference = new AtomicReference<>();
            this.world = interfaceProxy(World.class, Map.of(
                "getBlockAt", args -> blockReference.get(),
                "getName", args -> "world"));
            this.block = interfaceProxy(Block.class, Map.of(
                "getType", args -> material.get(),
                "setType", args -> {
                    material.set((Material) args[0]);
                    return null;
                },
                "setBlockData", args -> {
                    material.set(((BlockData) args[0]).getMaterial());
                    return null;
                },
                "getWorld", args -> world,
                "getLocation", args -> new Location(world, 0, 64, 0),
                "getBlockData", args -> blockData(material.get())));
            blockReference.set(block);
        }

        Material material() {
            return material.get();
        }

        World world() {
            return world;
        }

        Block block() {
            return block;
        }
    }

    private static final class RayWorld {
        private final Map<BlockPosKey, AtomicReference<Material>> materials = new HashMap<>();
        private final Map<BlockPosKey, Block> blocks = new HashMap<>();
        private final Map<BlockPosKey, SculptBlock> sculpts = new HashMap<>();
        private final AtomicReference<RayTraceResult> combinedRayResult =
            new AtomicReference<>();
        private final World world;

        RayWorld() {
            this.world = interfaceProxy(World.class, Map.of(
                "getName", args -> "world",
                "getBlockAt", this::blockAt,
                "rayTrace", args -> combinedRayResult.get()));
        }

        World world() {
            return world;
        }

        SculptBlock addSculpt(
                final int x,
                final int y,
                final int z,
                final HeadResolver resolver) {
            setMaterial(x, y, z, Material.AIR);
            final SculptBlock sculpt = sculptBlock(
                world, new Location(world, x, y, z), resolver);
            sculpts.put(new BlockPosKey("world", x, y, z), sculpt);
            return sculpt;
        }

        SculptBlock sculptAt(final BlockPosKey key) {
            return sculpts.get(key);
        }

        void setCombinedRayResult(final RayTraceResult result) {
            combinedRayResult.set(result);
        }

        void setMaterial(final int x, final int y, final int z, final Material material) {
            materials.computeIfAbsent(
                new BlockPosKey("world", x, y, z), ignored -> new AtomicReference<>())
                .set(material);
        }

        private Object blockAt(final Object[] args) {
            if (args.length == 1 && args[0] instanceof Location location) {
                return block(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            }
            return block((int) args[0], (int) args[1], (int) args[2]);
        }

        private Block block(final int x, final int y, final int z) {
            final BlockPosKey key = new BlockPosKey("world", x, y, z);
            return blocks.computeIfAbsent(key, ignored -> interfaceProxy(Block.class, Map.of(
                "getX", args -> x,
                "getY", args -> y,
                "getZ", args -> z,
                "getType", args -> materials.computeIfAbsent(
                    key, unused -> new AtomicReference<>(Material.AIR)).get(),
                "setType", args -> {
                    setMaterial(x, y, z, (Material) args[0]);
                    return null;
                },
                "getWorld", args -> world,
                "getLocation", args -> new Location(world, x, y, z),
                "getBlockData", args -> blockData(materials.computeIfAbsent(
                    key, unused -> new AtomicReference<>(Material.AIR)).get()),
                "rayTrace", args -> new org.bukkit.util.BoundingBox(
                    x, y, z, x + 1,
                    materials.get(key).get().name().endsWith("_SLAB")
                        ? y + 0.5 : y + 1,
                    z + 1).rayTrace(
                        ((Location) args[0]).toVector(),
                        (Vector) args[1], (double) args[2]),
                "getRelative", args -> {
                    final BlockFace face = (BlockFace) args[0];
                    return block(x + face.getModX(), y + face.getModY(), z + face.getModZ());
                })));
        }
    }

    private static final class EmptyTransportSession implements TransportSession {
        private final DisplayHandle handle = new EmptyDisplayHandle();

        @Override
        public DisplayHandle spawn(Location loc, ItemStack head, Transformation transform) {
            return null;
        }

        @Override
        public DisplayHandle spawnRoot(Location blockCenter) {
            return null;
        }

        @Override
        public DisplayHandle spawnRiding(
                DisplayHandle vehicle, Location loc, ItemStack head,
                Transformation transform) {
            return handle;
        }

        @Override
        public void removePassenger(DisplayHandle vehicle, DisplayHandle child) {
        }

        @Override
        public void destroy(DisplayHandle handle) {
        }

        @Override
        public void destroyAll() {
        }

        @Override
        public void setVisible(Player viewer, boolean visible) {
        }

        @Override
        public DisplayHandle getRootEntity() {
            return null;
        }

        @Override
        public Map<String, DisplayHandle> getPassengerMap() {
            return Map.of();
        }
    }

    private static final class EmptyDisplayHandle implements DisplayHandle {
        @Override public void setItemStack(ItemStack head) {}
        @Override public void setTransformation(Transformation transformation) {}
        @Override public void despawn() {}
        @Override public void setVisible(Player viewer, boolean visible) {}
        @Override public UUID getEntityId() { return UUID.randomUUID(); }
        @Override public Location getLocation() { return null; }
        @Override public boolean isValid() { return true; }
        @Override public void setPDC(NamespacedKey key, String value) {}
        @Override public String getPDC(NamespacedKey key) { return null; }
        @Override public void setPDCBytes(NamespacedKey key, byte[] value) {}
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T interfaceProxy(
            Class<T> type, Map<String, Invocation> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) return type.getSimpleName();
                final Invocation invocation = methods.get(method.getName());
                if (invocation != null) {
                    return invocation.invoke(args == null ? new Object[0] : args);
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
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
