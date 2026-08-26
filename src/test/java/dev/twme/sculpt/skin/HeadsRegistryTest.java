package dev.twme.sculpt.skin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.joml.Quaternionf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.skin.store.HeadsStore;
import dev.twme.sculpt.skin.store.HeadsStore.StoredBlock;
import dev.twme.sculpt.skin.store.HeadsStore.StoredSkin;
import dev.twme.sculpt.skin.store.SqliteHeadsStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadsRegistryTest {

    private static final Logger LOG = Logger.getLogger("HeadsRegistryTest");
    private final List<SqliteHeadsStore> openedStores = new ArrayList<>();

    @AfterEach
    void closeStores() {
        for (SqliteHeadsStore store : openedStores) store.close();
        openedStores.clear();
    }

    @Test
    void loadFromDefersBlockPayloadUntilFirstAccess() {
        BakeKey stone = new BakeKey(new BlockKey("minecraft", "stone"), 0);
        AtomicInteger blockReads = new AtomicInteger();
        HeadsStore store = new HeadsStore() {
            @Override
            public int gridN() { return 4; }

            @Override
            public Optional<HeadsStore.Metadata> metadata() {
                return Optional.empty();
            }

            @Override
            public Collection<BakeKey> listBlocks() {
                return List.of(stone);
            }

            @Override
            public Optional<StoredBlock> readBlock(BakeKey key) {
                blockReads.incrementAndGet();
                return Optional.of(new StoredBlock(key, Map.of(
                        new ChunkCoord(0, 0, 0), "h-a"), Map.of()));
            }

            @Override
            public Optional<StoredSkin> readSkin(String hash) {
                return Optional.empty();
            }
        };

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        assertEquals(0, blockReads.get(), "startup should only enumerate block keys");
        assertEquals(1, reg.knownBlockKeys().size());

        assertTrue(reg.has(stone));
        assertTrue(reg.has(stone));
        assertEquals(1, blockReads.get(), "the block payload should be cached after first access");
    }

    @Test
    void prefetchMovesColdBlockAndSkinReadsOffCallerThread() throws Exception {
        BakeKey stone = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        AtomicInteger blockReads = new AtomicInteger();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        HeadsStore store = new HeadsStore() {
            @Override public int gridN() { return 4; }
            @Override public Optional<HeadsStore.Metadata> metadata() { return Optional.empty(); }
            @Override public Collection<BakeKey> listBlocks() { return List.of(stone); }
            @Override public Optional<StoredBlock> readBlock(BakeKey key) {
                blockReads.incrementAndGet();
                readStarted.countDown();
                try {
                    releaseRead.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                return Optional.of(new StoredBlock(key,
                        Map.of(new ChunkCoord(0, 0, 0), "h"), Map.of()));
            }
            @Override public Optional<StoredSkin> readSkin(String hash) {
                return Optional.of(new StoredSkin(hash, "value", "signature", null));
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64, io);
            assertFalse(reg.hasLoaded(stone));
            CompletableFuture<Boolean> future = org.junit.jupiter.api.Assertions.assertTimeout(
                    Duration.ofSeconds(1), () -> reg.prefetch(stone));
            assertTrue(readStarted.await(1, TimeUnit.SECONDS),
                    "the cold read should be running on the supplied executor");
            assertEquals(1, blockReads.get());
            releaseRead.countDown();
            assertTrue(future.get(2, TimeUnit.SECONDS));
            assertTrue(reg.getIfLoaded(stone, new ChunkCoord(0, 0, 0)).isPresent());
        } finally {
            releaseRead.countDown();
            io.shutdownNow();
        }
    }

    @Test
    void cellPrefetchLoadsOnlyTheRequestedSkinPayload() throws Exception {
        BakeKey stone = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        ChunkCoord requested = new ChunkCoord(0, 0, 0);
        ChunkCoord untouched = new ChunkCoord(1, 0, 0);
        List<String> skinReads = new ArrayList<>();
        HeadsStore store = new HeadsStore() {
            @Override public int gridN() { return 16; }
            @Override public Optional<HeadsStore.Metadata> metadata() { return Optional.empty(); }
            @Override public Collection<BakeKey> listBlocks() { return List.of(stone); }
            @Override public Optional<StoredBlock> readBlock(BakeKey key) {
                return Optional.of(new StoredBlock(key, Map.of(
                        requested, "h-requested",
                        untouched, "h-untouched"), Map.of()));
            }
            @Override public Optional<StoredSkin> readSkin(String hash) {
                skinReads.add(hash);
                return Optional.of(new StoredSkin(hash, "value", "signature", null));
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            HeadsRegistry reg = HeadsRegistry.loadFrom(
                    LOG, store, 16, "1.21.11", 64, io);

            assertTrue(reg.prefetch(stone, requested).get(2, TimeUnit.SECONDS));

            assertEquals(List.of("h-requested"), skinReads);
            assertTrue(reg.getIfLoaded(stone, requested).isPresent());
            assertTrue(reg.getIfLoaded(stone, untouched).isEmpty(),
                    "a sparse edit must not decode unrelated grid-16 cells");
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void variantsLoadLazilyFromRepresentativeBlock() {
        BlockKey log = new BlockKey("minecraft", "oak_log");
        BakeKey key = BakeKey.untinted(log);
        AtomicInteger blockReads = new AtomicInteger();
        HeadsStore store = blockStore(4, List.of(key), requested -> {
            blockReads.incrementAndGet();
            return new StoredBlock(requested, Map.of(
                    new ChunkCoord(0, 0, 0), "h"), Map.of(
                    "axis=x", new VariantRotation(90, 0)));
        });

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        assertEquals(0, blockReads.get());

        assertEquals(90, reg.variantsFor(log).get("axis=x").xDeg());
        Quaternionf rotation = reg.rotationFor(log, "axis=x");
        assertEquals(0.7071068f, rotation.x, 1e-5f);
        assertEquals(1, blockReads.get(), "variants and rotation should share one block-index load");
    }

    @Test
    void runtimeVariantsOverrideEmptyCatalogRepresentative() {
        BlockKey leaves = new BlockKey("minecraft", "oak_leaves");
        BakeKey untinted = BakeKey.untinted(leaves);
        AtomicInteger blockReads = new AtomicInteger();
        HeadsStore store = blockStore(4, List.of(untinted), requested -> {
            blockReads.incrementAndGet();
            return new StoredBlock(requested, Map.of(
                    new ChunkCoord(0, 0, 0), "h"), Map.of());
        });
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = Map.of(
                new ChunkCoord(0, 0, 0), new HeadsRegistry.Entry("h", "v", "s", null));
        BakeKey tinted = new BakeKey(leaves, 0xFF7FBF2E);

        reg.register(tinted, chunks, Map.of("persistent=true", new VariantRotation(0, 90)));
        reg.register(untinted, chunks);

        assertEquals(90, reg.variantsFor(leaves).get("persistent=true").yDeg());
        assertEquals(0, blockReads.get(), "registered variants should not fall back to the empty catalog index");

        assertEquals(0, reg.reindex());
        assertEquals(90, reg.variantsFor(leaves).get("persistent=true").yDeg(),
            "reindex must find non-empty variants even when the untinted key is representative");
        assertEquals(0, blockReads.get());
    }

    @Test
    void asyncVariantPrefetchFallsBackFromEmptyUntintedRepresentative() throws Exception {
        BlockKey leaves = new BlockKey("minecraft", "oak_leaves");
        BakeKey untinted = BakeKey.untinted(leaves);
        BakeKey tinted = new BakeKey(leaves, 0xFF7FBF2E);
        HeadsStore store = blockStore(4, List.of(untinted, tinted), requested ->
                new StoredBlock(requested,
                        Map.of(new ChunkCoord(0, 0, 0), "h"),
                        requested.equals(tinted)
                                ? Map.of("persistent=true", new VariantRotation(0, 90))
                                : Map.of()));
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            HeadsRegistry reg = HeadsRegistry.loadFrom(
                    LOG, store, 4, "1.21.11", 64, io);
            assertTrue(reg.prefetch(leaves).get(2, TimeUnit.SECONDS));
            assertEquals(90,
                    reg.variantsForIfLoaded(leaves).get("persistent=true").yDeg());
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void reindexRefreshesCachedBlocksWithoutEagerReads() {
        BakeKey stone = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        BakeKey dirt = BakeKey.untinted(new BlockKey("minecraft", "dirt"));
        List<BakeKey> keys = new ArrayList<>(List.of(stone));
        Map<BakeKey, StoredBlock> blocks = new LinkedHashMap<>();
        blocks.put(stone, new StoredBlock(stone, Map.of(
                new ChunkCoord(0, 0, 0), "h-a"), Map.of()));
        AtomicInteger blockReads = new AtomicInteger();
        HeadsStore store = blockStore(4, keys, requested -> {
            blockReads.incrementAndGet();
            return blocks.get(requested);
        });
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);

        assertEquals(1, reg.chunkCountFor(stone));
        blocks.put(stone, new StoredBlock(stone, Map.of(
                new ChunkCoord(0, 0, 0), "h-a",
                new ChunkCoord(1, 0, 0), "h-b"), Map.of()));
        blocks.put(dirt, new StoredBlock(dirt, Map.of(
                new ChunkCoord(0, 0, 0), "h-c"), Map.of()));
        keys.add(dirt);

        assertEquals(1, reg.reindex());
        assertEquals(1, blockReads.get(), "reindex should enumerate keys without decoding blocks");
        assertEquals(2, reg.chunkCountFor(stone), "cached indexes must refresh after reindex");
        assertTrue(reg.has(dirt));
        assertEquals(3, blockReads.get());
    }

    @Test
    void grid16BlockCacheRetainsBoundedWorkingSet() {
        List<BakeKey> keys = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            keys.add(BakeKey.untinted(new BlockKey("test", "block_" + index)));
        }
        Map<ChunkCoord, String> chunks = new LinkedHashMap<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunks.put(new ChunkCoord(x, y, z), "h");
                }
            }
        }
        Map<ChunkCoord, String> immutableChunks = Map.copyOf(chunks);
        AtomicInteger blockReads = new AtomicInteger();
        HeadsStore store = blockStore(16, keys, requested -> {
            blockReads.incrementAndGet();
            return new StoredBlock(requested, immutableChunks, Map.of());
        });

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 16, "1.21.11", 64);
        assertEquals(0, blockReads.get(), "100 grid16 blocks must not materialize at startup");
        assertEquals(100, reg.knownBlockKeys().size());

        for (BakeKey key : keys) {
            assertEquals(4096, reg.chunkCountFor(key));
        }
        assertEquals(100, blockReads.get());
        assertTrue(reg.cachedBlockIndexCount() <= 16,
                "65,536-chunk budget should retain at most 16 grid16 block indexes");
    }

    @Test
    void loadFromPopulatesIndexAndReadSkins(@TempDir Path dir) {
        SqliteHeadsStore store = sqlite(dir);
        BakeKey stone = new BakeKey(new BlockKey("minecraft", "stone"), 0);
        store.writeBlock(new StoredBlock(stone, Map.of(
                new ChunkCoord(0, 0, 0), "h-a",
                new ChunkCoord(3, 2, 1), "h-b"
        ), Map.of()));
        store.writeSkin(new StoredSkin("h-a", "v-a", "s-a", "u-a"));
        store.writeSkin(new StoredSkin("h-b", "v-b", "s-b", null));

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        assertTrue(reg.has(stone));
        assertEquals(1, reg.knownBlockKeys().size());

        // chunksFor resolves all skin payloads in one go.
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = reg.chunksFor(stone);
        assertEquals(2, chunks.size());
        assertEquals("v-a", chunks.get(new ChunkCoord(0, 0, 0)).textureValue());
        assertEquals("u-a", chunks.get(new ChunkCoord(0, 0, 0)).mineskinUuid());
        assertNotNull(chunks.get(new ChunkCoord(3, 2, 1)));
    }

    @Test
    void registerPersistsToStoreAndPreWarmsCache(@TempDir Path dir) {
        SqliteHeadsStore store = sqlite(dir);
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);

        BakeKey key = new BakeKey(new BlockKey("minecraft", "dirt"), 0);
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
        chunks.put(new ChunkCoord(0, 0, 0),
                new HeadsRegistry.Entry("h-1", "v-1", "s-1", "u-1"));
        chunks.put(new ChunkCoord(1, 0, 0),
                new HeadsRegistry.Entry("h-2", "v-2", "s-2", null));
        reg.register(key, chunks);

        // Hot path: readBlock + readSkin now find what we just registered.
        StoredBlock b = store.readBlock(key).orElseThrow();
        assertEquals("h-1", b.chunkHashes().get(new ChunkCoord(0, 0, 0)));
        assertEquals("v-1", store.readSkin("h-1").orElseThrow().value());
        assertEquals("v-2", store.readSkin("h-2").orElseThrow().value());

        // Cached: second get() should not re-load from disk.
        Optional<HeadsRegistry.Entry> got = reg.get(key, new ChunkCoord(0, 0, 0));
        assertTrue(got.isPresent());
        assertEquals("v-1", got.get().textureValue());
    }

    @Test
    void registerPartialMergesSeparateBatchesAndPersistsCombinedIndex(@TempDir Path dir) {
        SqliteHeadsStore store = sqlite(dir);
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        ChunkCoord firstCoord = new ChunkCoord(0, 0, 0);
        ChunkCoord secondCoord = new ChunkCoord(2, 0, 0);

        reg.registerPartial(key, Map.of(firstCoord,
                new HeadsRegistry.Entry("h-1", "v-1", "s-1", "u-1")), Map.of());
        reg.registerPartial(key, Map.of(secondCoord,
                new HeadsRegistry.Entry("h-2", "v-2", "s-2", "u-2")), Map.of());

        assertEquals(2, reg.chunkCountFor(key));
        assertEquals("v-1", reg.get(key, firstCoord).orElseThrow().textureValue());
        assertEquals("v-2", reg.get(key, secondCoord).orElseThrow().textureValue());

        StoredBlock persisted = store.readBlock(key).orElseThrow();
        assertEquals(Map.of(firstCoord, "h-1", secondCoord, "h-2"),
                persisted.chunkHashes());
    }

    @Test
    void invalidateRemovesAllTintedVariantsAndCallsStore(@TempDir Path dir) {
        SqliteHeadsStore store = sqlite(dir);
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        BlockKey leaves = new BlockKey("minecraft", "oak_leaves");

        reg.register(new BakeKey(leaves, 0), Map.of(
                new ChunkCoord(0, 0, 0),
                new HeadsRegistry.Entry("h", "v", "s", "u")));
        reg.register(new BakeKey(leaves, 0xFF7FBF2E), Map.of(
                new ChunkCoord(0, 0, 0),
                new HeadsRegistry.Entry("h", "v", "s", "u")));
        // knownBlockKeys dedupes by BlockKey (no tint), so both tinted
        // variants collapse into a single entry.
        assertEquals(1, reg.knownBlockKeys().size());

        boolean removed = reg.invalidate(leaves);
        assertTrue(removed);
        assertFalse(reg.has(new BakeKey(leaves, 0)));
        assertFalse(reg.has(new BakeKey(leaves, 0xFF7FBF2E)));
        // The in-memory map dropped the leaves key; stone-style block list
        // is now empty.
        assertEquals(0, reg.knownBlockKeys().size());
        // The folder store no longer has a leaves block file (either tint).
        assertTrue(store.readBlock(new BakeKey(leaves, 0)).isEmpty());
        assertTrue(store.readBlock(new BakeKey(leaves, 0xFF7FBF2E)).isEmpty());
    }

    @Test
    void rotationForReturnsIdentityForUnknownVariant() {
        HeadsRegistry reg = HeadsRegistry.empty(LOG, 4, "1.21.11");
        BlockKey stone = new BlockKey("minecraft", "stone");
        // No variants registered → identity rotation.
        Quaternionf q = reg.rotationFor(stone, "axis=y");
        assertEquals(0f, q.x, 1e-6f);
        assertEquals(0f, q.y, 1e-6f);
        assertEquals(0f, q.z, 1e-6f);
        assertEquals(1f, q.w, 1e-6f);
    }

    @Test
    void rotationForReturnsQuatFromVariantMap() {
        HeadsRegistry reg = HeadsRegistry.empty(LOG, 4, "1.21.11");
        BlockKey log = new BlockKey("minecraft", "oak_log");
        // Y-axis log: 90° around X. (JOML rotateX is right-handed.)
        Map<String, VariantRotation> variants = Map.of(
                "axis=x", new VariantRotation(90, 0),
                "axis=y", new VariantRotation(0, 0));
        // Re-create the registry with the variants by going through register,
        // which is the supported mutation path.
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = Map.of(
                new ChunkCoord(0, 0, 0), new HeadsRegistry.Entry("h", "v", "s", null));
        reg.register(new BakeKey(log, 0), chunks, variants);

        Quaternionf q = reg.rotationFor(log, "axis=x");
        // 90° around X → quaternion (sin(45°), 0, 0, cos(45°)) ≈ (0.7071, 0, 0, 0.7071)
        assertEquals(0.7071068f, q.x, 1e-5f);
        assertEquals(0f, q.y, 1e-6f);
        assertEquals(0f, q.z, 1e-6f);
        assertEquals(0.7071068f, q.w, 1e-5f);
    }

    @Test
    void toHeadSkinBuildsCompletedSkinFromEntry() {
        HeadsRegistry.Entry e = new HeadsRegistry.Entry("h-1", "v-1", "s-1", "u-1");
        HeadSkin h = HeadsRegistry.toHeadSkin(e);
        assertEquals(SkinState.COMPLETED, h.state());
        assertEquals("v-1", h.textureValue());
        assertEquals("s-1", h.textureSignature());
        assertEquals("u-1", h.mineskinUuid());
    }

    @Test
    void warmDedupesByHash(@TempDir Path dir) {
        SqliteHeadsStore store = sqlite(dir);
        store.writeBlock(new StoredBlock(
                new BakeKey(new BlockKey("minecraft", "stone"), 0),
                Map.of(
                        new ChunkCoord(0, 0, 0), "h-1",
                        new ChunkCoord(1, 0, 0), "h-1",
                        new ChunkCoord(2, 0, 0), "h-1",
                        new ChunkCoord(3, 0, 0), "h-2"
                ),
                Map.of()));
        store.writeSkin(new StoredSkin("h-1", "v-1", "s-1", null));
        store.writeSkin(new StoredSkin("h-2", "v-2", "s-2", null));

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.11", 64);
        int warmed = reg.warm(new BakeKey(new BlockKey("minecraft", "stone"), 0));
        assertEquals(2, warmed, "two unique hashes despite 4 chunks pointing at them");
    }

    @Test
    void nullStoreEmptyFactoryBehaves(@TempDir Path dir) {
        // heads registry without a backing store can still register + serve
        // entries from the in-memory Caffeine cache.
        HeadsRegistry reg = HeadsRegistry.empty(LOG, 4, "1.21.11");
        BakeKey key = new BakeKey(new BlockKey("minecraft", "stone"), 0);
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = Map.of(
                new ChunkCoord(0, 0, 0),
                new HeadsRegistry.Entry("h", "v", "s", null));
        reg.register(key, chunks);
        assertTrue(reg.has(key));
        assertEquals("v", reg.get(key, new ChunkCoord(0, 0, 0)).orElseThrow().textureValue());
        // knownBlockKeys is the live set.
        Collection<BlockKey> known = reg.knownBlockKeys();
        assertTrue(known.contains(new BlockKey("minecraft", "stone")));
    }

    @Test
    void emptyRegistryRetainsRuntimeIndexesPastCacheBudget() {
        HeadsRegistry reg = HeadsRegistry.empty(LOG, 16, "1.21.11");
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
        HeadsRegistry.Entry entry = new HeadsRegistry.Entry("h", "v", "s", null);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunks.put(new ChunkCoord(x, y, z), entry);
                }
            }
        }
        List<BakeKey> keys = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            BakeKey key = BakeKey.untinted(new BlockKey("test", "runtime_" + index));
            keys.add(key);
            reg.register(key, chunks);
        }

        assertTrue(reg.cachedBlockIndexCount() <= 16);
        for (BakeKey key : keys) {
            assertEquals(4096, reg.chunkCountFor(key),
                    "unpersisted runtime indexes must survive cache eviction");
        }
    }

    private static HeadsStore blockStore(
            int gridN,
            Collection<BakeKey> keys,
            java.util.function.Function<BakeKey, StoredBlock> blockLoader) {
        return new HeadsStore() {
            @Override
            public int gridN() { return gridN; }

            @Override
            public Optional<HeadsStore.Metadata> metadata() {
                return Optional.empty();
            }

            @Override
            public Collection<BakeKey> listBlocks() {
                return keys;
            }

            @Override
            public Optional<StoredBlock> readBlock(BakeKey key) {
                return Optional.ofNullable(blockLoader.apply(key));
            }

            @Override
            public Optional<StoredSkin> readSkin(String hash) {
                return Optional.empty();
            }
        };
    }

    private SqliteHeadsStore sqlite(Path dir) {
        SqliteHeadsStore store = new SqliteHeadsStore(LOG, dir.resolve("heads.sqlite"),
                4, "1.21.11", "test");
        openedStores.add(store);
        return store;
    }
}
