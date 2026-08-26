package dev.twme.sculpt.skin.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;

class SbReaderTest {

    @Test
    void readsAdministratorCatalogFromFileAndBytes(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("heads-2.sbh");
        BakeKey key = new BakeKey(new BlockKey("minecraft", "oak_leaves"), 0xFF7FBF2E);
        HeadsStore.StoredSkin skin = StoreTestFixtures.skin(1);
        HeadsStore.StoredBlock block = StoreTestFixtures.fullBlock(2, key, skin.hash(),
                Map.of("persistent=true", new VariantRotation(90, 180)));
        StoreTestFixtures.writeSbh(input, 2, List.of(block), List.of(skin));

        try (SbReader reader = SbReader.fromFile(input)) {
            assertEquals(2, reader.gridN());
            assertEquals("test-catalog", reader.producer());
            assertEquals(List.of(key), List.copyOf(reader.listBlocks()));
            assertEquals(8, reader.chunkSkinIndices(key).length);
            assertEquals(90, reader.variantsFor(key).get("persistent=true").xDeg());
            SbReader.SkinPayload payload = reader.findSkin(skin.hash()).orElseThrow();
            assertEquals(skin.value(), payload.value());
            assertEquals(skin.signature(), payload.signature());
            assertEquals(skin.mineskinUuid(), payload.mineskinUuid());
        }

        try (SbReader reader = SbReader.fromBytes(Files.readAllBytes(input))) {
            assertTrue(reader.findSkin(skin.hash()).isPresent());
        }
    }
}
