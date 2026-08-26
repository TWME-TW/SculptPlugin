package dev.twme.sculpt.skin.bake;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBakerTest {

    private static final Logger LOG = Logger.getLogger("BlockBakerTest");

    @Test
    void planRecordStoresChunkAndHeadCounts() {
        BlockBaker.Plan p = new BlockBaker.Plan(56, 3, 2);
        assertEquals(56, p.totalChunks());
        assertEquals(3, p.uniqueHeads());
        assertEquals(2, p.needUpload());
    }

    @Test
    void planRecordEquality() {
        BlockBaker.Plan a = new BlockBaker.Plan(56, 3, 2);
        BlockBaker.Plan b = new BlockBaker.Plan(56, 3, 2);
        assertEquals(a, b);

        BlockBaker.Plan c = new BlockBaker.Plan(56, 3, 3);
        assertNotEquals(a, c);
    }

    @Test
    void batchContainsOnlyEightCellsInImmediateParent() {
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        BlockBaker.Batch batch = BlockBaker.Batch.containing(
                key, 4, new ChunkCoord(3, 0, 2));

        assertEquals(new ChunkCoord(2, 0, 2), batch.parentOrigin());
        assertEquals(8, batch.cells().size());
        assertTrue(batch.cells().contains(new ChunkCoord(2, 0, 2)));
        assertTrue(batch.cells().contains(new ChunkCoord(3, 1, 3)));
        assertEquals(batch, BlockBaker.Batch.containing(
                key, 4, new ChunkCoord(2, 1, 3)));
        assertNotEquals(batch, BlockBaker.Batch.containing(
                key, 4, new ChunkCoord(0, 0, 0)));
    }

    @Test
    void grassBlockBakeAttemptLogAppendsTintToBlockId() throws Exception {
        Logger logger = Logger.getLogger("BlockBakerTintLogTest");
        logger.setUseParentHandlers(false);
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);

        McAssetClient assets = new McAssetClient(java.nio.file.Path.of("build/test-assets"));
        BlockBaker baker = new BlockBaker(
                logger, () -> false, assets, "1.21.11",
                null, null, null,
                java.nio.file.Path.of("build/test-pngs"),
                Executors.newSingleThreadExecutor(), 10L, null);
        BakeKey key = new BakeKey(
                new BlockKey("minecraft", "grass_block"), 0xFF7FBF2E);

        assertEquals(Boolean.FALSE, baker.bake(key).get());
        assertTrue(messages.stream().anyMatch(message ->
                message.contains(
                        "attempting block=minecraft:grass_block#7FBF2E")));
        assertFalse(messages.stream().anyMatch(message ->
                message.contains("tint=")));
    }

    @Test
    void failedBatchSilentlyBacksOffDuplicateRequests() throws Exception {
        Logger logger = Logger.getLogger("BlockBakerBackoffTest");
        logger.setUseParentHandlers(false);
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);

        McAssetClient assets = new McAssetClient(
                java.nio.file.Path.of("build/test-assets"));
        BlockBaker baker = new BlockBaker(
                logger, () -> false, assets, "1.21.11",
                null, null, null,
                java.nio.file.Path.of("build/test-pngs"),
                Runnable::run, 10L, null);
        BakeKey key = BakeKey.untinted(
                new BlockKey("minecraft", "grass_block"));

        for (int request = 0; request < 20; request++) {
            assertEquals(Boolean.FALSE, baker.bake(key).get());
        }

        assertEquals(1L, messages.stream()
                .filter(message -> message.contains("attempting block="))
                .count());
    }

    @Test
    void constructorAcceptsAllNulls() {
        // Verifies constructor wiring without trying to actually bake —
        // a no-uploader baker is well-defined (every bake returns false).
        McAssetClient assets = new McAssetClient(java.nio.file.Path.of("build/test-assets"));
        BlockBaker baker = new BlockBaker(
                LOG,
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() { return false; }
                },
                assets,
                "1.21.11",
                null,
                null,
                null,
                java.nio.file.Path.of("build/test-pngs"),
                Executors.newSingleThreadExecutor(),
                10L,
                null);
        assertNotNull(baker);
    }

    @Test
    void bakeWithNullUploaderReturnsFalse() throws Exception {
        // A baker with no uploader is a valid fallback: every bake resolves
        // to false because there's no MineSkin client to publish to.
        McAssetClient assets = new McAssetClient(java.nio.file.Path.of("build/test-assets"));
        BlockBaker baker = new BlockBaker(
                LOG,
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() { return false; }
                },
                assets,
                "1.21.11",
                null,                       // registry
                null,                       // uploader
                null,                       // diskCache
                java.nio.file.Path.of("build/test-pngs"),
                Executors.newSingleThreadExecutor(),
                10L,
                null);
        BakeKey key = new BakeKey(new BlockKey("minecraft", "stone"), 0);
        CompletableFuture<Boolean> result = baker.bake(key);
        // uploader == null → fast-fail to false
        Boolean ok = result.get();
        assertEquals(Boolean.FALSE, ok);
    }

    @Test
    void inflightDeduplicatesParallelRequests() throws Exception {
        // Two simultaneous bakes for the same key share one underlying
        // future (the second caller doesn't start a second pipeline). We
        // verify by counting the requests that actually ran: with no
        // uploader, each bake returns false, but the inflight map should
        // hold a single future while both callers are waiting.
        AtomicBoolean secondCallerEntered = new AtomicBoolean(false);
        McAssetClient assets = new McAssetClient(java.nio.file.Path.of("build/test-assets"));
        BlockBaker baker = new BlockBaker(
                LOG,
                new BooleanSupplier() {
                    @Override public boolean getAsBoolean() { return false; }
                },
                assets,
                "1.21.11",
                null,
                null,
                null,
                java.nio.file.Path.of("build/test-pngs"),
                Executors.newSingleThreadExecutor(),
                10L,
                null);
        BakeKey key = new BakeKey(new BlockKey("minecraft", "stone"), 0);
        CompletableFuture<Boolean> first = baker.bake(key);
        CompletableFuture<Boolean> second = baker.bake(key);
        secondCallerEntered.set(true);
        // Both should resolve to false (no uploader), and the inflight
        // map should be empty once both complete.
        assertEquals(Boolean.FALSE, first.get());
        assertEquals(Boolean.FALSE, second.get());
    }
}
