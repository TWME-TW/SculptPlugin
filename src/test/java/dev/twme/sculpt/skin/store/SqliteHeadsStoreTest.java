package dev.twme.sculpt.skin.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;

class SqliteHeadsStoreTest {

    private static final Logger LOG = Logger.getLogger("SqliteHeadsStoreTest");

    @Test
    void batchRoundTripPersistsTintChunksVariantsAndSkin(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey key = new BakeKey(new BlockKey("minecraft", "oak_leaves"), 0xFF7FBF2E);
        HeadsStore.StoredBlock block = new HeadsStore.StoredBlock(key, Map.of(
                new ChunkCoord(0, 0, 0), "hash-a",
                new ChunkCoord(3, 2, 1), "hash-b"), Map.of(
                "persistent=true", new VariantRotation(90, 180)));

        try (SqliteHeadsStore store = store(database, 4, "1.21.11")) {
            store.writeBatch(block, List.of(
                    new HeadsStore.StoredSkin("hash-a", "value-a", "sig-a", "uuid-a"),
                    new HeadsStore.StoredSkin("hash-b", "value-b", "sig-b", null)));
        }

        try (SqliteHeadsStore reopened = store(database, 4, "1.21.11")) {
            assertEquals(List.of(key), List.copyOf(reopened.listBlocks()));
            HeadsStore.StoredBlock actual = reopened.readBlock(key).orElseThrow();
            assertEquals(block.chunkHashes(), actual.chunkHashes());
            assertEquals(90, actual.variants().get("persistent=true").xDeg());
            assertEquals("value-a", reopened.readSkin("hash-a").orElseThrow().value());
            assertTrue(reopened.skinExists("hash-b"));
        }
    }

    @Test
    void gridsShareOneDatabaseWithoutMixingBlockIndexes(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey grid2Key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        BakeKey grid4Key = BakeKey.untinted(new BlockKey("minecraft", "dirt"));

        try (SqliteHeadsStore grid2 = store(database, 2, "1.21.11");
             SqliteHeadsStore grid4 = store(database, 4, "1.21.11")) {
            grid2.writeBatch(StoreTestFixtures.fullBlock(
                    2, grid2Key, StoreTestFixtures.hash(1), Map.of()),
                    List.of(StoreTestFixtures.skin(1)));
            grid4.writeBatch(StoreTestFixtures.fullBlock(
                    4, grid4Key, StoreTestFixtures.hash(2), Map.of()),
                    List.of(StoreTestFixtures.skin(2)));

            assertEquals(List.of(grid2Key), List.copyOf(grid2.listBlocks()));
            assertEquals(List.of(grid4Key), List.copyOf(grid4.listBlocks()));
            assertTrue(grid2.readBlock(grid4Key).isEmpty());
            assertTrue(grid4.readBlock(grid2Key).isEmpty());
            assertTrue(grid2.readSkin(StoreTestFixtures.hash(2)).isPresent(),
                    "skin payloads are shared and deduplicated across grids");
        }
    }

    @Test
    void replacingBlockDeletesStaleChunksAndVariants(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        try (SqliteHeadsStore store = store(database, 4, "1.21.11")) {
            store.writeBlock(new HeadsStore.StoredBlock(key, Map.of(
                    new ChunkCoord(0, 0, 0), "old-a",
                    new ChunkCoord(1, 0, 0), "old-b"),
                    Map.of("old", new VariantRotation(90, 0))));
            store.writeBlock(new HeadsStore.StoredBlock(key,
                    Map.of(new ChunkCoord(2, 0, 0), "new"),
                    Map.of("new", new VariantRotation(0, 90))));

            HeadsStore.StoredBlock actual = store.readBlock(key).orElseThrow();
            assertEquals(Map.of(new ChunkCoord(2, 0, 0), "new"), actual.chunkHashes());
            assertEquals(Map.of("new", new VariantRotation(0, 90)), actual.variants());
        }
    }

    @Test
    void failedBatchRollsBackNewSkinAndBlock(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        HeadsStore.StoredSkin skin = StoreTestFixtures.skin(7);
        HeadsStore.StoredBlock invalid = new HeadsStore.StoredBlock(key,
                Map.of(new ChunkCoord(4, 0, 0), skin.hash()), Map.of());

        try (SqliteHeadsStore store = store(database, 4, "1.21.11")) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.writeBatch(invalid, List.of(skin)));
            assertFalse(store.skinExists(skin.hash()));
            assertTrue(store.readBlock(key).isEmpty());
        }
    }

    @Test
    void removingBlockRemovesEveryTintButKeepsSharedSkins(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BlockKey leaves = new BlockKey("minecraft", "oak_leaves");
        HeadsStore.StoredSkin skin = StoreTestFixtures.skin(8);
        try (SqliteHeadsStore store = store(database, 4, "1.21.11")) {
            store.writeBatch(new HeadsStore.StoredBlock(
                    new BakeKey(leaves, 0), Map.of(), Map.of()), List.of(skin));
            store.writeBlock(new HeadsStore.StoredBlock(
                    new BakeKey(leaves, 0xFF00FF00), Map.of(), Map.of()));
            store.removeBlock(leaves);

            assertTrue(store.listBlocks().isEmpty());
            assertTrue(store.skinExists(skin.hash()));
        }
    }

    @Test
    void minecraftVersionChangeInvalidatesRuntimeData(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        try (SqliteHeadsStore old = store(database, 4, "1.21.10")) {
            old.writeBatch(new HeadsStore.StoredBlock(key, Map.of(), Map.of()),
                    List.of(StoreTestFixtures.skin(9)));
        }
        try (SqliteHeadsStore current = store(database, 4, "1.21.11")) {
            assertTrue(current.listBlocks().isEmpty());
            assertTrue(current.readSkin(StoreTestFixtures.hash(9)).isEmpty());
            assertEquals("1.21.11", current.metadata().orElseThrow().mcVersion());
        }
    }

    @Test
    void databaseUsesWalAndExpectedIndexes(@TempDir Path dir) throws Exception {
        Path database = dir.resolve("heads.sqlite");
        try (SqliteHeadsStore ignored = store(database, 4, "1.21.11");
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='idx_blocks_grid_block'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void grid16BatchFitsRuntimeBudget(@TempDir Path dir) {
        Path database = dir.resolve("heads.sqlite");
        BakeKey key = BakeKey.untinted(new BlockKey("minecraft", "stone"));
        Map<ChunkCoord, String> chunks = new LinkedHashMap<>();
        List<HeadsStore.StoredSkin> skins = new java.util.ArrayList<>();
        for (int index = 0; index < 64; index++) skins.add(StoreTestFixtures.skin(index + 100));
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    chunks.put(new ChunkCoord(x, y, z), skins.get(index++ % skins.size()).hash());
                }
            }
        }

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (SqliteHeadsStore store = store(database, 16, "1.21.11")) {
                store.writeBatch(new HeadsStore.StoredBlock(key, chunks, Map.of()), skins);
                assertEquals(4096, store.readBlock(key).orElseThrow().chunkHashes().size());
            }
        });
    }

    private static SqliteHeadsStore store(Path database, int gridN, String version) {
        return new SqliteHeadsStore(LOG, database, gridN, version, "test");
    }
}
