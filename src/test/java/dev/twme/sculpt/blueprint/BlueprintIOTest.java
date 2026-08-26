package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintIOTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsValidatedBlueprint() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        BlueprintData data = blueprint(new byte[]{(byte) 0x80});

        io.writeBlueprint(UUID.fromString("00000000-0000-0000-0000-000000000001"), data, false);
        BlueprintData loaded = io.readBlueprint(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), data.blueprintId(), false);

        assertEquals(data.blueprintId(), loaded.blueprintId());
        assertEquals(data.name(), loaded.name());
        assertNull(loaded.referenceFacing());
    }

    @Test
    void roundTripsBlueprintReferenceFacing() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        BlueprintData base = blueprint(new byte[]{(byte) 0x80});
        BlueprintData data = new BlueprintData(
            base.blueprintId(), base.name(), base.description(), base.createdTimestamp(),
            base.lastModifiedTimestamp(), base.minecraftVersion(), base.blockKey(),
            base.matchedVariantKey(), base.isMixed(), base.maxDepth(), base.gridN(),
            base.octreeData(), base.leafCoordinates(), "NORTH", base.visibility(),
            base.editToken());

        io.writeBlueprint(playerId, data, false);
        BlueprintData loaded = io.readBlueprint(playerId, data.blueprintId(), false);

        assertEquals("NORTH", loaded.referenceFacing());
    }

    @Test
    void refusesToWriteMalformedOctree() {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        assertThrows(IOException.class, () -> io.writeBlueprint(
            UUID.randomUUID(), blueprint(new byte[]{(byte) 0x80, 0}), false));
    }

    @Test
    void refusesOversizedInputBeforeJsonParsing() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        Path file = temporaryDirectory.resolve("oversized.blueprint");
        Files.write(file, new byte[(int) BlueprintValidator.MAX_FILE_BYTES + 1]);

        assertThrows(IOException.class, () -> io.readBlueprintFrom(file));
    }

    @Test
    void roundTripsCuboidBlocksAndDimensions() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        BlueprintBlockData block = new BlueprintBlockData(
            1, 2, 3, "minecraft:stone", null, false,
            0, 1, new byte[]{(byte) 0x80}, null);
        BlueprintData data = new BlueprintData(
            UUID.randomUUID(), "cuboid", null, 1, 1, "1.21.11",
            block.blockKey(), null, false, 0, 1, block.octreeData(), null,
            List.of(block), 2, 3, 4, "WEST", BlueprintData.Visibility.PRIVATE, null);

        io.writeBlueprint(playerId, data, false);
        BlueprintData loaded = io.readBlueprint(playerId, data.blueprintId(), false);

        assertEquals(2, loaded.sizeX());
        assertEquals(3, loaded.sizeY());
        assertEquals(4, loaded.sizeZ());
        assertEquals(1, loaded.blocks().size());
        assertEquals(3, loaded.blocks().get(0).z());
    }

    @Test
    void roundTripsRegularBlockData() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        BlueprintBlockData block = new BlueprintBlockData(
            0, 0, 0, "minecraft:oak_stairs", null, false, 0, 1, null, null,
            BlueprintBlockData.Kind.BLOCK,
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        BlueprintData data = new BlueprintData(
            UUID.randomUUID(), "regular", null, 1, 1, "1.21.11",
            "minecraft:oak_stairs", null, false, 0, 1,
            new byte[]{(byte) 0x80}, null, List.of(block), 1, 1, 1,
            "NORTH", BlueprintData.Visibility.PRIVATE, null);

        io.writeBlueprint(playerId, data, false);
        BlueprintData loaded = io.readBlueprint(playerId, data.blueprintId(), false);

        assertEquals(BlueprintBlockData.Kind.BLOCK, loaded.blocks().get(0).kind());
        assertEquals(block.blockData(), loaded.blocks().get(0).blockData());
        assertNull(loaded.blocks().get(0).octreeData());
    }

    @Test
    void migratesLegacyEditTokenIntoPrivatePublicationIndex() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000006");
        BlueprintData base = blueprint(new byte[]{(byte) 0x80});
        BlueprintData legacy = new BlueprintData(
            base.blueprintId(), "legacy publication", null, 1, 2, "1.21.11",
            base.blockKey(), null, false, 0, 1, base.octreeData(), null,
            BlueprintData.Visibility.PUBLIC, "legacy-secret-key");
        Path source = io.blueprintFilePath(playerId, legacy.blueprintId(), false);
        Files.createDirectories(source.getParent());
        Files.writeString(source, io.gson().toJson(legacy));

        BlueprintData loaded = io.readBlueprint(
            playerId, legacy.blueprintId(), false);

        assertNull(loaded.editToken());
        assertEquals(BlueprintData.Visibility.PRIVATE, loaded.visibility());
        assertFalse(Files.readString(source).contains("legacy-secret-key"));
        BlueprintPublicationStore.Publication publication =
            io.publications().find(playerId, legacy.blueprintId().toString());
        assertNotNull(publication);
        assertEquals("legacy-secret-key", publication.editToken());
        assertEquals("legacy publication", publication.name());

        Path publicationFile = io.publications().path(playerId);
        if (Files.getFileAttributeView(
                publicationFile, PosixFileAttributeView.class) != null) {
            assertEquals(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(publicationFile));
        }
    }

    @Test
    void exportedSerializationNeverContainsLocalEditToken() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        BlueprintData base = blueprint(new byte[]{(byte) 0x80});
        BlueprintData data = new BlueprintData(
            base.blueprintId(), base.name(), null, 1, 1, "1.21.11",
            base.blockKey(), null, false, 0, 1, base.octreeData(), null,
            BlueprintData.Visibility.PRIVATE, "do-not-export");
        Path export = temporaryDirectory.resolve("export.blueprint");

        io.writeBlueprintTo(export, data);

        assertFalse(Files.readString(export).contains("do-not-export"));
        assertNull(io.readBlueprintFrom(export).editToken());
    }

    @Test
    void publicationKeySurvivesLocalBlueprintDeletionAndTracksRename() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000007");
        BlueprintData data = blueprint(new byte[]{(byte) 0x80});
        io.writeBlueprint(playerId, data, false);
        io.publications().save(playerId, new BlueprintPublicationStore.Publication(
            data.blueprintId(), data.blueprintId(), data.name(), "stored-key",
            "https://example.com/api/blueprints", null, 1));

        io.renameBlueprint(playerId, data.blueprintId(), "renamed", false);
        io.publications().rename(playerId, data.blueprintId(), "renamed");
        assertNotNull(io.publications().find(playerId, "renamed"));
        assertTrue(io.deleteBlueprint(playerId, data.blueprintId(), false));

        BlueprintPublicationStore.Publication publication =
            io.publications().find(playerId, data.blueprintId().toString());
        assertNotNull(publication);
        assertEquals("stored-key", publication.editToken());
        assertTrue(io.publications().remove(playerId, data.blueprintId()));
        assertNull(io.publications().find(playerId, data.blueprintId().toString()));
    }

    @Test
    void legacyMigrationDoesNotReplaceANewerStoredPublicationKey() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000008");
        BlueprintData base = blueprint(new byte[]{(byte) 0x80});
        io.publications().save(playerId, new BlueprintPublicationStore.Publication(
            base.blueprintId(), base.blueprintId(), base.name(), "new-key",
            "https://example.com/api/blueprints", null, 10));
        BlueprintData legacy = new BlueprintData(
            base.blueprintId(), base.name(), null, 1, 2, "1.21.11",
            base.blockKey(), null, false, 0, 1, base.octreeData(), null,
            BlueprintData.Visibility.PRIVATE, "old-key");
        Path source = io.blueprintFilePath(playerId, legacy.blueprintId(), false);
        Files.createDirectories(source.getParent());
        Files.writeString(source, io.gson().toJson(legacy));

        io.readBlueprint(playerId, legacy.blueprintId(), false);

        assertEquals("new-key", io.publications()
            .find(playerId, legacy.blueprintId().toString()).editToken());
        assertFalse(Files.readString(source).contains("old-key"));
    }

    @Test
    void migratesLegacyPublicIndexIdentityFieldsWhenRewritten() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        Path indexPath = io.publicIndexPath();
        Files.createDirectories(indexPath.getParent());
        Files.writeString(indexPath, """
            {
              "version": 1,
              "blueprints": [{
                "blueprintId": "550e8400-e29b-41d4-a716-446655440000",
                "name": "legacy",
                "authorUUID": "00000000-0000-4000-8000-000000000001",
                "authorName": "LegacyName",
                "gridN": 2,
                "blockKey": "minecraft:stone"
              }]
            }
            """);

        BlueprintIO.PublicIndex index = io.readPublicIndex();
        BlueprintIO.PublicIndex.PublicEntry entry = index.blueprints.getFirst();
        assertEquals(2, index.version);
        assertEquals("00000000-0000-4000-8000-000000000001", entry.submitterUUID);
        assertEquals("LegacyName", entry.submitterName);

        io.writePublicIndex(index);
        String rewritten = Files.readString(indexPath);
        assertTrue(rewritten.contains("\"submitterName\""));
        assertTrue(rewritten.contains("\"submitterUUID\""));
        assertFalse(rewritten.contains("\"authorName\""));
        assertFalse(rewritten.contains("\"authorUUID\""));
    }

    private static BlueprintData blueprint(byte[] octree) {
        return new BlueprintData(
            UUID.randomUUID(), "round-trip", null, 1, 1, "1.21.11",
            "minecraft:stone", null, false, 0, 1, octree, null,
            BlueprintData.Visibility.PRIVATE, null);
    }
}
