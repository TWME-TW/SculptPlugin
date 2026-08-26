package dev.twme.sculpt.skin.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;

class LayeredHeadsStoreTest {

    private static final Logger LOG = Logger.getLogger("LayeredHeadsStoreTest");

    @Test
    void sqliteRuntimeShadowsInstalledCatalog(@TempDir Path dir) {
        StoreTestFixtures.MemoryStore catalog = new StoreTestFixtures.MemoryStore(4, true);
        HeadsStore.StoredSkin catalogSkin = StoreTestFixtures.skin(1);
        catalog.writeBlock(new HeadsStore.StoredBlock(stone(),
                Map.of(new ChunkCoord(0, 0, 0), catalogSkin.hash()), Map.of()));
        catalog.writeSkin(catalogSkin);

        try (LayeredHeadsStore layered = new LayeredHeadsStore(sqlite(dir), catalog)) {
            layered.writableLayer().writeBlock(new HeadsStore.StoredBlock(stone(),
                    Map.of(new ChunkCoord(0, 0, 0), "runtime-hash"), Map.of()));
            assertEquals("runtime-hash", layered.readBlock(stone()).orElseThrow()
                    .chunkHashes().get(new ChunkCoord(0, 0, 0)));
            assertEquals(catalogSkin.value(), layered.readSkin(catalogSkin.hash()).orElseThrow().value());
        }
    }

    @Test
    void listBlocksIsDeduplicatedUnionAndWritesOnlyRuntime(@TempDir Path dir) {
        StoreTestFixtures.MemoryStore catalog = new StoreTestFixtures.MemoryStore(4, true);
        catalog.writeBlock(new HeadsStore.StoredBlock(stone(), Map.of(), Map.of()));
        catalog.writeBlock(new HeadsStore.StoredBlock(dirt(), Map.of(), Map.of()));

        try (SqliteHeadsStore runtimeStore = sqlite(dir);
             LayeredHeadsStore layered = new LayeredHeadsStore(runtimeStore, catalog)) {
            runtimeStore.writeBlock(new HeadsStore.StoredBlock(stone(), Map.of(), Map.of()));
            BakeKey grass = BakeKey.untinted(new BlockKey("minecraft", "grass_block"));
            layered.writeBatch(new HeadsStore.StoredBlock(grass,
                    Map.of(new ChunkCoord(0, 0, 0), "h"), Map.of()),
                    List.of(new HeadsStore.StoredSkin("h", "v", "s", null)));

            assertEquals(Set.of(stone(), dirt(), grass), Set.copyOf(layered.listBlocks()));
            assertTrue(runtimeStore.readBlock(grass).isPresent());
            assertTrue(catalog.readBlock(grass).isEmpty());
        }
    }

    @Test
    void allowsNoInstalledCatalog(@TempDir Path dir) {
        try (LayeredHeadsStore layered = new LayeredHeadsStore(sqlite(dir), null)) {
            assertTrue(layered.installedCatalog().isEmpty());
            assertTrue(layered.listBlocks().isEmpty());
        }
    }

    @Test
    void rejectsDifferentCatalogGrid(@TempDir Path dir) {
        StoreTestFixtures.MemoryStore wrongGrid = new StoreTestFixtures.MemoryStore(2, false);
        try (SqliteHeadsStore runtime = sqlite(dir)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LayeredHeadsStore(runtime, wrongGrid));
        }
    }

    private static SqliteHeadsStore sqlite(Path dir) {
        return new SqliteHeadsStore(LOG, dir.resolve("cache/heads.sqlite"),
                4, "1.21.11", "test");
    }

    private static BakeKey stone() {
        return BakeKey.untinted(new BlockKey("minecraft", "stone"));
    }

    private static BakeKey dirt() {
        return BakeKey.untinted(new BlockKey("minecraft", "dirt"));
    }
}
