package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.skin.bake.BlockBaker;
import dev.twme.sculpt.skin.store.HeadsStore;
import dev.twme.sculpt.skin.store.HeadsStore.StoredBlock;
import dev.twme.sculpt.skin.store.HeadsStore.StoredSkin;

class RegistryHeadResolverTest {

    private static final Logger LOG = Logger.getLogger("RegistryHeadResolverTest");

    @Test
    void missingGrid4CellRequestsItsSiblingBatchAndMissingGrid2Ancestor() {
        HeadsRegistry grid2 = HeadsRegistry.empty(LOG, 2, "1.21.11");
        HeadsRegistry grid4 = HeadsRegistry.empty(LOG, 4, "1.21.11");
        List<BlockBaker.Batch> requested = new ArrayList<>();
        RegistryHeadResolver resolver = resolver(grid2, grid4, requested);
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));

        resolver.requestMissingHierarchy(
                key, 4, new ChunkCoord(3, 1, 2), null);

        assertEquals(List.of(
                new BlockBaker.Batch(key, 4, new ChunkCoord(2, 0, 2)),
                new BlockBaker.Batch(key, 2, new ChunkCoord(0, 0, 0))),
                requested);
    }

    @Test
    void availableGrid2AncestorIsNotRequestedAgain() {
        HeadsRegistry grid2 = HeadsRegistry.empty(LOG, 2, "1.21.11");
        HeadsRegistry grid4 = HeadsRegistry.empty(LOG, 4, "1.21.11");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        grid2.registerPartial(key, Map.of(
                new ChunkCoord(1, 0, 1),
                new HeadsRegistry.Entry("h", "value", "signature", "uuid")), Map.of());
        List<BlockBaker.Batch> requested = new ArrayList<>();
        RegistryHeadResolver resolver = resolver(grid2, grid4, requested);

        resolver.requestMissingHierarchy(
                key, 4, new ChunkCoord(3, 1, 2), null);

        assertEquals(List.of(
                new BlockBaker.Batch(key, 4, new ChunkCoord(2, 0, 2))),
                requested);
    }

    @Test
    void knownPartialCatalogStillBakesAnAbsentCoordinate() {
        HeadsRegistry grid2 = HeadsRegistry.empty(LOG, 2, "1.21.11");
        HeadsRegistry grid4 = HeadsRegistry.empty(LOG, 4, "1.21.11");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        HeadsRegistry.Entry entry =
                new HeadsRegistry.Entry("h", "value", "signature", "uuid");
        grid4.registerPartial(key, Map.of(new ChunkCoord(0, 0, 0), entry), Map.of());
        grid2.registerPartial(key, Map.of(new ChunkCoord(1, 0, 1), entry), Map.of());
        List<BlockBaker.Batch> requested = new ArrayList<>();
        RegistryHeadResolver resolver = resolver(grid2, grid4, requested);

        resolver.requestMissingHierarchy(
                key, 4, new ChunkCoord(3, 1, 2), null);

        assertEquals(List.of(
                new BlockBaker.Batch(key, 4, new ChunkCoord(2, 0, 2))),
                requested);
    }

    @Test
    void coldKnownCatalogDoesNotBlockMissingHierarchyCaller() throws Exception {
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        HeadsStore store = new HeadsStore() {
            @Override public int gridN() { return 4; }
            @Override public Optional<HeadsStore.Metadata> metadata() { return Optional.empty(); }
            @Override public Collection<BakeKey> listBlocks() { return List.of(key); }
            @Override public Optional<StoredBlock> readBlock(BakeKey requested) {
                readStarted.countDown();
                try {
                    releaseRead.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                return Optional.of(new StoredBlock(requested,
                        Map.of(new ChunkCoord(0, 0, 0), "h"), Map.of()));
            }
            @Override public Optional<StoredSkin> readSkin(String hash) {
                return Optional.of(new StoredSkin(hash, "value", "signature", null));
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            HeadsRegistry grid4 = HeadsRegistry.loadFrom(
                    LOG, store, 4, "1.21.11", 64, io);
            RegistryHeadResolver resolver = new RegistryHeadResolver(
                    Map.of(4, grid4), Map.of(), null);

            assertTimeout(Duration.ofSeconds(1), () -> resolver.requestMissingHierarchy(
                    key, 4, new ChunkCoord(0, 0, 0), null));
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseRead.countDown();
            io.shutdownNow();
        }
    }

    @Test
    void multipleColdCellsScheduleOneBlockRefresh() throws Exception {
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        ChunkCoord first = new ChunkCoord(0, 0, 0);
        ChunkCoord second = new ChunkCoord(1, 0, 0);
        CountDownLatch indexReadStarted = new CountDownLatch(1);
        CountDownLatch releaseIndexRead = new CountDownLatch(1);
        HeadsStore store = new HeadsStore() {
            @Override public int gridN() { return 4; }
            @Override public Optional<HeadsStore.Metadata> metadata() { return Optional.empty(); }
            @Override public Collection<BakeKey> listBlocks() { return List.of(key); }
            @Override public Optional<StoredBlock> readBlock(BakeKey requested) {
                indexReadStarted.countDown();
                try {
                    releaseIndexRead.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                return Optional.of(new StoredBlock(requested, Map.of(
                        first, "h-first", second, "h-second"), Map.of()));
            }
            @Override public Optional<StoredSkin> readSkin(String hash) {
                return Optional.of(new StoredSkin(hash, "value", "signature", null));
            }
        };
        ExecutorService io = Executors.newSingleThreadExecutor();
        CountDownLatch refreshed = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        try {
            HeadsRegistry grid4 = HeadsRegistry.loadFrom(
                    LOG, store, 4, "1.21.11", 64, io);
            RegistryHeadResolver resolver = new RegistryHeadResolver(
                    Map.of(4, grid4), Map.of(), null,
                    (ignored, refresh) -> {
                        refresh.run();
                        refreshes.incrementAndGet();
                        refreshed.countDown();
                    });
            SculptBlock block = dummyBlock();

            resolver.requestMissingHierarchy(key, 4, first, block);
            assertTrue(indexReadStarted.await(1, TimeUnit.SECONDS));
            resolver.requestMissingHierarchy(key, 4, second, block);
            releaseIndexRead.countDown();

            assertTrue(refreshed.await(2, TimeUnit.SECONDS));
            io.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(1, refreshes.get());
        } finally {
            releaseIndexRead.countDown();
            io.shutdownNow();
        }
    }

    private static SculptBlock dummyBlock() {
        BlockData data = (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "clone" -> proxy;
                    case "toString", "getAsString" -> "minecraft:stone";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return new SculptBlock(null, new Location(null, 0, 0, 0), data,
                "", new Quaternionf(), null, null, 0);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static RegistryHeadResolver resolver(
            HeadsRegistry grid2, HeadsRegistry grid4,
            List<BlockBaker.Batch> requested) {
        McAssetClient assets = new McAssetClient(Path.of("build/test-assets"));
        BlockBaker baker2 = baker(assets, grid2);
        BlockBaker baker4 = baker(assets, grid4);
        return new RegistryHeadResolver(
                Map.of(2, grid2, 4, grid4),
                Map.of(2, baker2, 4, baker4),
                (batch, ignored) -> requested.add(batch));
    }

    private static BlockBaker baker(McAssetClient assets, HeadsRegistry registry) {
        return new BlockBaker(
                LOG, () -> false, assets, "1.21.11", registry,
                null, null, Path.of("build/test-pngs"), Runnable::run,
                10L, null);
    }
}
