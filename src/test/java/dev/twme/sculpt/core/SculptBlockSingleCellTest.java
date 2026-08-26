package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.render.TextBlockRenderHandle;
import dev.twme.sculpt.render.TextBlockRenderer;
import dev.twme.sculpt.transport.DisplayHandle;
import dev.twme.sculpt.transport.TransportSession;

class SculptBlockSingleCellTest {

    @Test
    void materialReplacementPreservesPartialShapeAndClearsHeadMetadata() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicInteger resolutions = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            resolutions.incrementAndGet();
            return new ChunkHead(null, null);
        });
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "signature");
        sculpt.initSingleCell(4, 4, 4, 2, texture);
        final OctreeNode occupied = sculpt.leafAt(4, 4, 4);
        occupied.setTextureCoord(new ChunkCoord(1, 0, 1));
        sculpt.storedCoords = Map.of(occupied.pathAsString(), new int[]{1, 0, 1});
        sculpt.enterSculpted();

        final BlockData dirt = blockData(Material.DIRT, "minecraft:dirt");
        final Quaternionf rotation = new Quaternionf().rotateY(1.0f);

        assertTrue(sculpt.replaceAllMaterials(
            dirt, new VariantResolution(rotation, "axis=x")));

        final ArrayList<OctreeNode> allLeaves = new ArrayList<>();
        sculpt.root.collectAllLeaves(allLeaves);
        assertEquals(SculptBlock.State.SCULPTED, sculpt.state);
        assertEquals(1, sculpt.root.collectLeaves().size());
        assertSame(dirt, sculpt.originalBlockData);
        assertEquals("axis=x", sculpt.matchedVariantKey);
        assertEquals(rotation, sculpt.blockRotation);
        assertNull(sculpt.storedCoords);
        assertTrue(allLeaves.stream().allMatch(leaf -> leaf.blockData() == dirt));
        assertTrue(allLeaves.stream().allMatch(leaf -> leaf.textureCoord() == null));
        assertTrue(allLeaves.stream().allMatch(leaf -> leaf.playerHeadTexture() == null));
        assertEquals(2, resolutions.get(),
            "the occupied leaf is resolved once on spawn and once after replacement");
        assertFalse(sculpt.replaceAllMaterials(
            dirt, new VariantResolution(rotation, "axis=x")));
        assertEquals(2, resolutions.get(), "an identical replacement is a no-op");
    }

    @Test
    void materialReplacementCompletesFullyOccupiedSculptAsOneVanillaBlock() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final SculptBlock sculpt = sculpt(
            world, transport, (node, block) -> new ChunkHead(null, null));
        final AtomicInteger cleared = new AtomicInteger();
        sculpt.setOnCleared(cleared::incrementAndGet);
        sculpt.initLeavesDataOnly();
        sculpt.enterSculpted();
        final BlockData dirt = blockData(Material.DIRT, "minecraft:dirt");

        assertTrue(sculpt.replaceAllMaterials(
            dirt, new VariantResolution(new Quaternionf(), "")));

        assertEquals(SculptBlock.State.COMPLETE, sculpt.state);
        assertTrue(sculpt.despawned);
        assertSame(dirt, world.backingData.get());
        assertEquals(1, cleared.get());
    }

    @Test
    void heldPlayerHeadCellCannotBeSubdivided() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final SculptBlock sculpt = sculpt(
            world, transport, (node, block) -> new ChunkHead(null, null));
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "signature");
        sculpt.initSingleCell(4, 4, 4, 1, texture);
        final OctreeNode cell = sculpt.leafAt(4, 4, 4);

        assertEquals(texture, cell.playerHeadTexture());
        assertFalse(sculpt.subdivide(cell));
        assertTrue(cell.isLeaf());

        cell.remove();
        assertFalse(sculpt.subdivide(cell), "removed head metadata remains atomic");

        final OctreeNode replacement = sculpt.refineRemovedLeafAt(cell, 2, 2, 2, 2);
        assertEquals(2, replacement.depth());
        assertNull(replacement.playerHeadTexture());
        assertTrue(replacement.isRemoved());
    }

    @Test
    void singleCellInitializationSpawnsOnlyTheRequestedLeafAtEveryDepth() {
        for (int depth = 1; depth <= 4; depth++) {
            final TestWorld world = new TestWorld();
            final CountingTransportSession transport = new CountingTransportSession(false);
            final AtomicInteger resolutions = new AtomicInteger();
            final HeadResolver resolver = (node, block) -> {
                resolutions.incrementAndGet();
                return new ChunkHead(null, null);
            };
            final SculptBlock sculpt = sculpt(world, transport, resolver);

            sculpt.initSingleCell(15, 0, 15, depth);

            final ArrayList<OctreeNode> allLeaves = new ArrayList<>();
            sculpt.root.collectAllLeaves(allLeaves);
            final OctreeNode occupied = sculpt.root.collectLeaves().getFirst();
            final int side = 16 >> depth;
            assertEquals(7 * depth + 1, allLeaves.size(), "all leaves at depth " + depth);
            assertEquals(7 * depth, allLeaves.stream().filter(OctreeNode::isRemoved).count(),
                "removed leaves at depth " + depth);
            assertEquals(1, sculpt.root.collectLeaves().size(),
                "occupied leaves at depth " + depth);
            assertEquals(depth, occupied.depth());
            assertEquals(16 - side, occupied.minX());
            assertEquals(0, occupied.minY());
            assertEquals(16 - side, occupied.minZ());
            assertEquals(0, resolutions.get(), "initialization must not resolve temporary heads");

            sculpt.enterSculpted();

            assertEquals(1, transport.rootSpawns.get());
            assertEquals(1, transport.leafSpawns.get(),
                "spawned leaves at depth " + depth);
            assertEquals(1, resolutions.get(), "resolved heads at depth " + depth);
            assertEquals(SculptBlock.State.SCULPTED, sculpt.state);
            assertSame(transport.root, sculpt.rootEntity);
        }
    }

    @Test
    void dataOnlyInitializationDoesNotResolveTemporaryHeads() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicInteger resolutions = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            resolutions.incrementAndGet();
            return new ChunkHead(null, null);
        });

        sculpt.initLeavesDataOnly();

        assertEquals(8, sculpt.root.collectLeaves().size());
        assertEquals(0, resolutions.get());
    }

    @Test
    void structuralRemovalRoutesThroughTransportSessionDestroy() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final SculptBlock sculpt = sculpt(
            world, transport, (node, block) -> new ChunkHead(null, null));
        sculpt.initSingleCell(4, 4, 4, 1);
        sculpt.enterSculpted();

        final OctreeNode cell = sculpt.leafAt(4, 4, 4);
        assertTrue(sculpt.subdivide(cell));
        assertEquals(1, transport.destroyCalls.get(),
            "replacing a parent display must unregister it through the session");
    }

    @Test
    void failedLeafSpawnRollsBackTheEntireStateTransition() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(true);
        final SculptBlock sculpt = sculpt(
            world, transport, (node, block) -> new ChunkHead(null, null));
        final AtomicInteger cleared = new AtomicInteger();
        sculpt.setOnCleared(cleared::incrementAndGet);
        sculpt.initSingleCell(4, 4, 4, 1);

        assertThrows(IllegalStateException.class, sculpt::enterSculpted);

        assertEquals(1, transport.rootSpawns.get());
        assertEquals(1, transport.leafSpawns.get());
        assertEquals(1, transport.destroyAllCalls.get());
        assertEquals(1, cleared.get());
        assertEquals(SculptBlock.State.COMPLETE, sculpt.state);
        assertNull(sculpt.rootEntity);
        assertSame(world.originalData, world.backingData.get());
    }

    @Test
    void textDisplayEditsReuseHandleUntilDisplayModeChanges() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final SculptBlock sculpt = sculpt(
            world, transport, (node, block) -> new ChunkHead(null, null));
        final CountingTextRenderHandle handle = new CountingTextRenderHandle();
        final AtomicInteger renderRequests = new AtomicInteger();
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.TEXT_DISPLAY,
            block -> {
                renderRequests.incrementAndGet();
                return handle;
            });
        sculpt.initLeavesDataOnly();
        sculpt.enterSculpted();

        assertSame(handle, sculpt.textRenderHandle());
        assertEquals(1, renderRequests.get());
        assertEquals(0, handle.despawns.get());

        sculpt.remove(sculpt.root.children()[0]);

        assertSame(handle, sculpt.textRenderHandle());
        assertEquals(2, renderRequests.get());
        assertEquals(0, handle.despawns.get(),
            "ordinary edits must retain the active incremental renderer");

        sculpt.setDisplayMode(SculptDisplayMode.HEAD);

        assertNull(sculpt.textRenderHandle());
        assertEquals(1, handle.despawns.get(),
            "changing display mode still performs a full renderer teardown");
    }

    @Test
    void autoUsesTextUntilAnOpaqueHeadBecomesReady() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicReference<AutoDisplayMaterialStatus> modelStatus =
            new AtomicReference<>(AutoDisplayMaterialStatus.LOADING);
        final java.util.concurrent.atomic.AtomicBoolean headReady =
            new java.util.concurrent.atomic.AtomicBoolean();
        final AtomicInteger headResolutions = new AtomicInteger();
        final AtomicInteger textRenders = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            headResolutions.incrementAndGet();
            return new ChunkHead(null, null, !headReady.get());
        });
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(modelStatus, textRenders, transport.operations));
        sculpt.initSingleCell(4, 4, 4, 1);
        final OctreeNode leaf = sculpt.root.collectLeaves().getFirst();

        sculpt.enterSculpted();

        assertEquals(0, headResolutions.get(),
            "model loading must not start an unsupported head bake early");
        assertEquals(0, transport.leafSpawns.get(),
            "AUTO must not spawn the yellow-wool placeholder");
        assertTrue(sculpt.rendersLeafWithTextDisplay(leaf));

        modelStatus.set(AutoDisplayMaterialStatus.OPAQUE);
        sculpt.refreshAutoDisplay();

        assertEquals(1, headResolutions.get());
        assertEquals(0, transport.leafSpawns.get(),
            "a missing opaque head remains represented by TextDisplay");
        assertTrue(sculpt.rendersLeafWithTextDisplay(leaf));

        headReady.set(true);
        transport.operations.clear();
        sculpt.reRender();

        assertEquals(1, transport.leafSpawns.get());
        assertEquals(List.of("spawn-leaf", "request-text-render"),
            transport.operations,
            "AUTO must attach the completed head before removing its pixels");
        assertFalse(sculpt.rendersLeafWithTextDisplay(leaf),
            "the newly attached head takes ownership before the pixel delta runs");
        assertTrue(textRenders.get() >= 3,
            "every ownership transition must reconcile the incremental pixels");
    }

    @Test
    void asynchronousAutoRefreshIgnoresAnInvalidRoot() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicReference<AutoDisplayMaterialStatus> modelStatus =
            new AtomicReference<>(AutoDisplayMaterialStatus.LOADING);
        final SculptBlock sculpt = sculpt(world, transport,
            (node, block) -> new ChunkHead(null, null, false));
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(modelStatus, new AtomicInteger()));
        sculpt.initSingleCell(4, 4, 4, 1);
        sculpt.enterSculpted();
        assertTrue(sculpt.canRefreshDisplays());

        modelStatus.set(AutoDisplayMaterialStatus.OPAQUE);
        transport.root.invalidate();

        assertDoesNotThrow(sculpt::reRender);
        assertEquals(0, transport.leafSpawns.get(),
            "a completed bake must not attach a head to an unloaded root");
        assertFalse(sculpt.canRefreshDisplays());
    }

    @Test
    void autoKeepsTransparentCellsOnTextDisplayWithoutRequestingHeads() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicInteger headResolutions = new AtomicInteger();
        final AtomicInteger textRenders = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            headResolutions.incrementAndGet();
            return new ChunkHead(null, null);
        });
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(
                new AtomicReference<>(AutoDisplayMaterialStatus.TRANSPARENT),
                textRenders));
        sculpt.initSingleCell(4, 4, 4, 1);
        final OctreeNode leaf = sculpt.root.collectLeaves().getFirst();

        sculpt.enterSculpted();
        sculpt.reRender();

        assertEquals(0, headResolutions.get());
        assertEquals(0, transport.leafSpawns.get());
        assertTrue(sculpt.rendersLeafWithTextDisplay(leaf));
        assertTrue(textRenders.get() >= 2);
    }

    @Test
    void autoCanMixAtomicPlayerHeadsWithTransparentTextCells() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicInteger headResolutions = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            headResolutions.incrementAndGet();
            return new ChunkHead(null, null);
        });
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(
                new AtomicReference<>(AutoDisplayMaterialStatus.TRANSPARENT),
                new AtomicInteger()));
        sculpt.initLeavesDataOnly();
        final OctreeNode atomicHead = sculpt.root.children()[0];
        atomicHead.setPlayerHeadTexture(
            new PlayerHeadTexture("texture", "signature"));

        sculpt.enterSculpted();

        assertEquals(1, headResolutions.get());
        assertEquals(1, transport.leafSpawns.get());
        assertFalse(sculpt.rendersLeafWithTextDisplay(atomicHead));
        assertEquals(7, sculpt.root.collectLeaves().stream()
            .filter(sculpt::rendersLeafWithTextDisplay)
            .count());
    }

    @Test
    void autoMaterialReplacementDoesNotRetainThePreviousHead() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicReference<AutoDisplayMaterialStatus> modelStatus =
            new AtomicReference<>(AutoDisplayMaterialStatus.OPAQUE);
        final java.util.concurrent.atomic.AtomicBoolean headReady =
            new java.util.concurrent.atomic.AtomicBoolean(true);
        final SculptBlock sculpt = sculpt(world, transport, (node, block) ->
            new ChunkHead(null, null, !headReady.get()));
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(modelStatus, new AtomicInteger()));
        sculpt.initSingleCell(4, 4, 4, 1);
        sculpt.enterSculpted();
        final OctreeNode leaf = sculpt.root.collectLeaves().getFirst();
        assertFalse(sculpt.rendersLeafWithTextDisplay(leaf));

        headReady.set(false);
        modelStatus.set(AutoDisplayMaterialStatus.LOADING);
        final BlockData dirt = blockData(Material.DIRT, "minecraft:dirt");

        assertTrue(sculpt.replaceAllMaterials(
            dirt, new VariantResolution(new Quaternionf(), "")));

        assertTrue(sculpt.rendersLeafWithTextDisplay(leaf));
        assertEquals(1, transport.destroyCalls.get(),
            "the head resolved for the previous material must be removed");
        assertEquals(1, transport.leafSpawns.get(),
            "no placeholder may replace the invalidated head");
    }

    @Test
    void autoModelCompletionRefreshesOnlyCellsWithThatMaterial() {
        final TestWorld world = new TestWorld();
        final CountingTransportSession transport = new CountingTransportSession(false);
        final AtomicReference<AutoDisplayMaterialStatus> modelStatus =
            new AtomicReference<>(AutoDisplayMaterialStatus.LOADING);
        final AtomicInteger headResolutions = new AtomicInteger();
        final SculptBlock sculpt = sculpt(world, transport, (node, block) -> {
            headResolutions.incrementAndGet();
            return new ChunkHead(null, null);
        });
        sculpt.configureStrategies(
            FillMode.BARRIER,
            SculptDisplayMode.AUTO,
            autoRenderer(modelStatus, new AtomicInteger()));
        sculpt.initLeavesDataOnly();
        final OctreeNode dirt = sculpt.root.children()[0];
        dirt.setBlockData(blockData(Material.DIRT, "minecraft:dirt"));
        sculpt.enterSculpted();

        modelStatus.set(AutoDisplayMaterialStatus.OPAQUE);
        sculpt.refreshAutoDisplay(Material.DIRT);

        assertEquals(1, headResolutions.get());
        assertEquals(1, transport.leafSpawns.get());
        assertFalse(sculpt.rendersLeafWithTextDisplay(dirt));
        assertEquals(7, sculpt.root.collectLeaves().stream()
            .filter(sculpt::rendersLeafWithTextDisplay)
            .count());
    }

    private static TextBlockRenderer autoRenderer(
            final AtomicReference<AutoDisplayMaterialStatus> status,
            final AtomicInteger renders) {
        return autoRenderer(status, renders, null);
    }

    private static TextBlockRenderer autoRenderer(
            final AtomicReference<AutoDisplayMaterialStatus> status,
            final AtomicInteger renders,
            final java.util.List<String> operations) {
        final CountingTextRenderHandle handle = new CountingTextRenderHandle();
        return new TextBlockRenderer() {
            @Override
            public TextBlockRenderHandle render(final SculptBlock block) {
                renders.incrementAndGet();
                if (operations != null) operations.add("request-text-render");
                return handle;
            }

            @Override
            public AutoDisplayMaterialStatus autoMaterialStatus(
                    final SculptBlock block,
                    final Material material) {
                return status.get();
            }
        };
    }

    private static SculptBlock sculpt(
            final TestWorld world,
            final TransportSession transport,
            final HeadResolver resolver) {
        return new SculptBlock(
            world.world,
            new Location(world.world, 3, 64, 7),
            world.originalData,
            "",
            new Quaternionf(),
            transport,
            resolver,
            0);
    }

    private static BlockData blockData(final Material material, final String serialized) {
        final AtomicReference<BlockData> self = new AtomicReference<>();
        final BlockData data = interfaceProxy(BlockData.class, Map.of(
            "clone", args -> self.get(),
            "getMaterial", args -> material,
            "getAsString", args -> serialized));
        self.set(data);
        return data;
    }

    private static final class TestWorld {
        private final BlockData originalData = interfaceProxy(BlockData.class, Map.of(
            "clone", args -> originalData(),
            "getAsString", args -> "minecraft:stone",
            "getMaterial", args -> Material.STONE));
        private final AtomicReference<BlockData> backingData =
            new AtomicReference<>(originalData);
        private final Block block = interfaceProxy(Block.class, Map.of(
            "getBlockData", args -> backingData.get(),
            "setBlockData", args -> {
                backingData.set((BlockData) args[0]);
                return null;
            },
            "setType", args -> null));
        private final World world = interfaceProxy(World.class, Map.of(
            "getName", args -> "world",
            "getBlockAt", args -> block));

        private BlockData originalData() {
            return originalData;
        }
    }

    private static final class CountingTransportSession implements TransportSession {
        private final EmptyDisplayHandle root = new EmptyDisplayHandle();
        private final boolean failLeafSpawn;
        private final AtomicInteger rootSpawns = new AtomicInteger();
        private final AtomicInteger leafSpawns = new AtomicInteger();
        private final AtomicInteger destroyCalls = new AtomicInteger();
        private final AtomicInteger destroyAllCalls = new AtomicInteger();
        private final java.util.List<String> operations = new ArrayList<>();

        private CountingTransportSession(final boolean failLeafSpawn) {
            this.failLeafSpawn = failLeafSpawn;
        }

        @Override
        public DisplayHandle spawn(
                final Location loc,
                final ItemStack head,
                final Transformation transform) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DisplayHandle spawnRoot(final Location blockCenter) {
            rootSpawns.incrementAndGet();
            return root;
        }

        @Override
        public DisplayHandle spawnRiding(
                final DisplayHandle vehicle,
                final Location loc,
                final ItemStack head,
                final Transformation transform) {
            leafSpawns.incrementAndGet();
            operations.add("spawn-leaf");
            if (failLeafSpawn) throw new IllegalStateException("simulated mount failure");
            return new EmptyDisplayHandle();
        }

        @Override public void removePassenger(DisplayHandle vehicle, DisplayHandle child) {}
        @Override public void destroy(DisplayHandle handle) { destroyCalls.incrementAndGet(); }
        @Override public void destroyAll() { destroyAllCalls.incrementAndGet(); }
        @Override public void setVisible(Player viewer, boolean visible) {}
        @Override public DisplayHandle getRootEntity() { return root; }
        @Override public Map<String, DisplayHandle> getPassengerMap() { return Map.of(); }
    }

    private static final class EmptyDisplayHandle implements DisplayHandle {
        private boolean valid = true;

        private void invalidate() {
            valid = false;
        }

        @Override public void setItemStack(ItemStack head) {}
        @Override public void setTransformation(Transformation transformation) {}
        @Override public void despawn() {}
        @Override public void setVisible(Player viewer, boolean visible) {}
        @Override public UUID getEntityId() { return UUID.randomUUID(); }
        @Override public Location getLocation() { return null; }
        @Override public boolean isValid() { return valid; }
        @Override public void setPDC(NamespacedKey key, String value) {}
        @Override public String getPDC(NamespacedKey key) { return null; }
        @Override public void setPDCBytes(NamespacedKey key, byte[] value) {}
    }

    private static final class CountingTextRenderHandle
            implements TextBlockRenderHandle {
        private final AtomicInteger despawns = new AtomicInteger();

        @Override public void despawn() { despawns.incrementAndGet(); }
        @Override public boolean isCancelled() { return despawns.get() > 0; }
        @Override public int entityCount() { return 0; }
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T interfaceProxy(
            final Class<T> type,
            final Map<String, Invocation> methods) {
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
