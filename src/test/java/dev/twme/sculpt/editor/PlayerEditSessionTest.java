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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEditSessionTest {

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
        final PlayerEditSession session = new PlayerEditSession(player, 4);
        session.setPluginHooks(
            key -> null, (key, sculpt) -> true, (key, sculpt) -> {},
            ignored -> 4, ignored -> true);

        assertEquals(4, session.tickHoverAndGetGrid());
        assertEquals(1, eyeReads.get());
        assertEquals(1, directionReads.get());
        assertEquals(1, combinedTraces.get());
        assertEquals(0, entityTraces.get());
        assertEquals(0, blockTraces.get());
    }

    @Test
    void finerGridLeftClickRemovesTheWholeAtomicPlayerHeadCell() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode atomicCell = sculpt.leafAt(4, 4, 4);
        atomicCell.setPlayerHeadTexture(new PlayerHeadTexture("texture", ""));

        final PlayerEditSession session = session(testWorld.block(), true, 4);
        setField(session, "hoveredSculpt", sculpt);
        session.onLeftClick(resolver);

        assertTrue(atomicCell.isRemoved());
        assertTrue(atomicCell.isLeaf());
        assertEquals(1, atomicCell.depth());
    }

    @Test
    void rightClickPlacesOneAtomicHeadAtTheActiveGridDepth() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode coarseCell = sculpt.leafAt(4, 4, 4);
        sculpt.remove(coarseCell);
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "signature");

        final PlayerEditSession session = session(testWorld.block(), true, 4);
        session.restoreCellMaterialAt(
            sculpt, 2, 2, 2, 4,
            new CellMaterial(blockData(Material.PLAYER_HEAD), texture));

        final OctreeNode placed = sculpt.leafAt(2, 2, 2);
        assertEquals(2, placed.depth());
        assertFalse(placed.isRemoved());
        assertEquals(Material.PLAYER_HEAD, placed.blockData().getMaterial());
        assertEquals(texture, placed.playerHeadTexture());
        assertEquals(1, java.util.Arrays.stream(placed.parent().children())
            .filter(child -> !child.isRemoved()).count());
        assertFalse(sculpt.subdivide(placed));
    }

    @Test
    void replacingRemovedHeadWithOriginalMaterialCanCompleteTheBlock() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final BlockData stone = blockData(Material.STONE);
        for (final OctreeNode leaf : sculpt.root.children()) leaf.setBlockData(stone);
        final OctreeNode cell = sculpt.leafAt(4, 4, 4);
        cell.setPlayerHeadTexture(new PlayerHeadTexture("texture", ""));
        sculpt.setMixed(sculpt.recomputeMixedState());
        sculpt.remove(cell);

        final PlayerEditSession session = session(testWorld.block(), true, 2);
        session.restoreCellMaterialAt(
            sculpt, 4, 4, 4, 2,
            CellMaterial.block(stone));

        assertEquals(SculptBlock.State.COMPLETE, sculpt.state);
        assertEquals(Material.STONE, testWorld.material());
    }

    @Test
    void resolutionOneIgnoresNormalBlockWithoutResolvingTexture() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.STONE);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final PlayerEditSession session = session(testWorld.block(), true);

        session.onLeftClick(resolver);

        assertEquals(Material.STONE, testWorld.material());
        assertEquals(0, resolver.calls());
    }

    @Test
    void resolutionOneRemovesEntireSculptBlockWithoutResolvingTexture() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final AtomicInteger cleared = new AtomicInteger();
        sculpt.setOnCleared(cleared::incrementAndGet);

        final PlayerEditSession session = session(testWorld.block(), true);
        setField(session, "hoveredSculpt", sculpt);
        session.onLeftClick(resolver);

        assertEquals(Material.AIR, testWorld.material());
        assertEquals(SculptBlock.State.COMPLETE, sculpt.state);
        assertEquals(1, cleared.get());
        assertEquals(0, resolver.calls());
    }

    @Test
    void resolutionOneSculptRemovalStillHonorsBuildProtection() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final PlayerEditSession session = session(testWorld.block(), false);
        setField(session, "hoveredSculpt", sculpt);

        session.onLeftClick(resolver);

        assertEquals(Material.BARRIER, testWorld.material());
        assertEquals(SculptBlock.State.SCULPTED, sculpt.state);
        assertEquals(0, resolver.calls());
    }

    @Test
    void resolutionOneRightClickRestoresEntireSculptBlock() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final AtomicInteger cleared = new AtomicInteger();
        sculpt.setOnCleared(cleared::incrementAndGet);

        final PlayerEditSession session = session(testWorld.block(), true);
        setField(session, "hoveredSculpt", sculpt);

        assertTrue(session.restoreWholeBlock());
        assertEquals(Material.STONE, testWorld.material());
        assertEquals(SculptBlock.State.COMPLETE, sculpt.state);
        assertEquals(1, cleared.get());
        assertEquals(0, resolver.calls());
    }

    @Test
    void resolutionOneWholeBlockRestoreStillHonorsBuildProtection() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);

        final PlayerEditSession session = session(testWorld.block(), false);
        setField(session, "hoveredSculpt", sculpt);

        assertFalse(session.restoreWholeBlock());
        assertEquals(Material.BARRIER, testWorld.material());
        assertEquals(SculptBlock.State.SCULPTED, sculpt.state);
        assertEquals(0, resolver.calls());
    }

    @Test
    void rightClickRestoresAdjacentCellWithItsExistingMaterial() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode target = sculpt.leafAt(12, 4, 4);
        sculpt.remove(target);

        final PlayerEditSession session = session(testWorld.block(), true, 2);
        setField(session, "hoveredSculpt", sculpt);
        setField(session, "hoveredHit",
            new VirtualGridHit(0, 0, 0, FaceDir.EAST, testWorld.block()));

        session.onRightClick(null);

        assertFalse(target.isRemoved());
        assertEquals(Material.STONE, target.blockData().getMaterial());
    }

    @Test
    void rightClickCanReplaceTheRestoredCellsMaterial() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode target = sculpt.leafAt(12, 4, 4);
        sculpt.remove(target);

        final PlayerEditSession session = session(testWorld.block(), true, 2);
        setField(session, "hoveredSculpt", sculpt);
        setField(session, "hoveredHit",
            new VirtualGridHit(0, 0, 0, FaceDir.EAST, testWorld.block()));

        session.onRightClick(blockData(Material.DIRT));

        assertFalse(target.isRemoved());
        assertEquals(Material.DIRT, target.blockData().getMaterial());
    }

    @Test
    void rightClickRestoreStillHonorsBuildProtection() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode target = sculpt.leafAt(12, 4, 4);
        sculpt.remove(target);

        final PlayerEditSession session = session(testWorld.block(), false, 2);
        setField(session, "hoveredSculpt", sculpt);
        setField(session, "hoveredHit",
            new VirtualGridHit(0, 0, 0, FaceDir.EAST, testWorld.block()));

        session.onRightClick(blockData(Material.DIRT));

        assertTrue(target.isRemoved());
        assertEquals(Material.STONE, target.blockData().getMaterial());
    }

    @Test
    void gapRestoreFillsTheWholePlayerGridCellWhenTreeIsMoreRefined() throws Exception {
        final TestWorld testWorld = new TestWorld(Material.BARRIER);
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = sculptBlock(testWorld, resolver);
        final OctreeNode coarseCell = sculpt.leafAt(12, 4, 4);
        coarseCell.subdivide();
        final List<OctreeNode> fineLeaves = List.of(coarseCell.children());
        fineLeaves.forEach(OctreeNode::remove);

        final PlayerEditSession session = session(testWorld.block(), true, 2);
        setGapRestoreTarget(session, sculpt, 12, 4, 4);
        session.onRightClick(blockData(Material.DIRT));

        assertTrue(fineLeaves.stream().noneMatch(OctreeNode::isRemoved));
        assertTrue(fineLeaves.stream().allMatch(
            leaf -> leaf.blockData().getMaterial() == Material.DIRT));
    }

    @Test
    void shulkerGapRegistersTheInitialSculptBlockBesideTheHitSurface() throws Exception {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 2.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final PlayerEditSession session = raySession(player, rayWorld, 2);

        assertTrue(session.traceWorldGap(player, sculpt, 2));
        assertTrue(session.hasGapRestoreTarget());
        assertSame(sculpt, fieldValue(session, "gapRestoreSculpt"));

        final OctreeNode restoredCell = sculpt.leafAt(4, 12, 12);
        session.onRightClick(null);
        assertFalse(restoredCell.isRemoved());
    }

    @Test
    void shulkerGapTraversesSeveralBlocksWithZeroDirectionComponents() throws Exception {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock first = rayWorld.addSculpt(2, 64, 0, resolver);
        final SculptBlock second = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(first);
        removeWestEastRayCells(second);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 3.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final PlayerEditSession session = raySession(player, rayWorld, 2);

        assertTrue(session.traceWorldGap(player, first, 2));
        assertSame(second, fieldValue(session, "gapRestoreSculpt"));

        final OctreeNode firstExitCell = first.leafAt(4, 12, 12);
        final OctreeNode secondExitCell = second.leafAt(4, 12, 12);
        session.onRightClick(null);
        assertTrue(firstExitCell.isRemoved());
        assertFalse(secondExitCell.isRemoved());
    }

    @Test
    void downwardShulkerGapRestoresTheOppositeGridTwoCell() throws Exception {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(0, 65, 0, resolver);
        final OctreeNode originalCell = sculpt.leafAt(4, 4, 4);
        final OctreeNode oppositeCell = sculpt.leafAt(12, 4, 12);
        for (final OctreeNode leaf : sculpt.root.children()) {
            if (leaf != originalCell) leaf.remove();
        }
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 0.75, 68.0, 0.75, new Vector(0, -1, 0));
        final PlayerEditSession session = raySession(player, rayWorld, 2);

        assertTrue(session.traceWorldGap(player, sculpt, 2));
        assertSame(sculpt, fieldValue(session, "gapRestoreSculpt"));
        assertEquals(12, fieldValue(session, "gapRestoreX"));
        assertEquals(4, fieldValue(session, "gapRestoreY"));
        assertEquals(12, fieldValue(session, "gapRestoreZ"));

        session.onRightClick(blockData(Material.DIRT));

        assertFalse(originalCell.isRemoved());
        assertFalse(oppositeCell.isRemoved());
        assertEquals(Material.DIRT, oppositeCell.blockData().getMaterial());
    }

    @Test
    void barrierGapUsesTheSameWorldGridTraversal() throws Exception {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(1, 64, 0, Material.BARRIER);
        rayWorld.setMaterial(0, 64, 0, Material.STONE);

        final Player player = rayPlayer(
            rayWorld.world(), 2.5, 64.5, 0.5, new Vector(-1, 0, 0));
        final PlayerEditSession session = raySession(player, rayWorld, 2);

        assertTrue(session.traceWorldGap(player, sculpt, 2));
        assertTrue(session.hasGapRestoreTarget());
        assertSame(sculpt, fieldValue(session, "gapRestoreSculpt"));
    }

    @Test
    void gapRayUsesSlabCollisionAndFindsTheOccupiedLowerCell() throws Exception {
        final RayWorld rayWorld = new RayWorld();
        final CountingHeadResolver resolver = new CountingHeadResolver();
        final SculptBlock sculpt = rayWorld.addSculpt(1, 64, 0, resolver);
        removeWestEastRayCells(sculpt);
        rayWorld.setMaterial(0, 64, 0, Material.SMOOTH_STONE_SLAB);

        final Player aboveSlab = rayPlayer(
            rayWorld.world(), 2.5, 64.75, 0.5, new Vector(-1, 0, 0));
        assertFalse(raySession(aboveSlab, rayWorld, 2)
            .traceWorldGap(aboveSlab, sculpt, 2),
            "the empty upper half must not block the ray");

        final Player throughSlab = rayPlayer(
            rayWorld.world(), 2.5, 64.25, 0.5, new Vector(-1, 0, 0));
        final PlayerEditSession session = raySession(throughSlab, rayWorld, 2);

        assertTrue(session.traceWorldGap(throughSlab, sculpt, 2));
        final VirtualGridHit slabHit = (VirtualGridHit) fieldValue(
            session, "gapBehindHit");
        assertEquals(0, slabHit.pgy());
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

    private static PlayerEditSession session(Block block, boolean canEdit)
            throws ReflectiveOperationException {
        return session(block, canEdit, 1);
    }

    private static PlayerEditSession session(
            Block block, boolean canEdit, int gridSize)
            throws ReflectiveOperationException {
        final Player player = interfaceProxy(Player.class, Map.of());
        final PlayerEditSession session = new PlayerEditSession(player, gridSize);
        session.setPluginHooks(key -> null, (key, sculpt) -> true,
            (key, sculpt) -> {}, ignored -> gridSize, ignored -> canEdit);
        setField(session, "hoveredHit",
            new VirtualGridHit(0, 0, 0, FaceDir.UP, block));
        return session;
    }

    private static PlayerEditSession raySession(
            final Player player, final RayWorld world, final int gridSize) {
        final PlayerEditSession session = new PlayerEditSession(player, gridSize);
        session.setPluginHooks(
            world::sculptAt, (key, sculpt) -> true, (key, sculpt) -> {},
            ignored -> gridSize, ignored -> true);
        return session;
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

    private static void setGapRestoreTarget(
            final PlayerEditSession session,
            final SculptBlock sculpt,
            final int x,
            final int y,
            final int z) throws ReflectiveOperationException {
        setField(session, "gapRestoreX", x);
        setField(session, "gapRestoreY", y);
        setField(session, "gapRestoreZ", z);
        setField(session, "gapRestoreSculpt", sculpt);
    }

    private static Object fieldValue(final Object target, final String name)
            throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
        private final World world;

        RayWorld() {
            this.world = interfaceProxy(World.class, Map.of(
                "getName", args -> "world",
                "getBlockAt", this::blockAt));
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
