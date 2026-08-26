package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesExistingPathAndTracksBlueprintAssociation() throws Exception {
        BlueprintIO io = new BlueprintIO(temporaryDirectory);
        FolderManager folders = new FolderManager(io, 3);
        UUID player = UUID.randomUUID();
        UUID blueprint = UUID.randomUUID();

        FolderManager.Folder first = folders.createFolder(player, "builds/castles");
        FolderManager.Folder second = folders.createFolder(player, "builds/castles");

        assertEquals(first.hash, second.hash);
        assertEquals(1, folders.listFolders(player, "builds", false).size());

        folders.assignBlueprint(player, blueprint, "builds/castles", false);
        assertTrue(folders.isBlueprintInFolder(player, blueprint, "builds/castles", false));
        assertFalse(folders.isBlueprintInFolder(player, blueprint, null, false));

        folders.removeBlueprint(player, blueprint, false);
        assertTrue(folders.isBlueprintInFolder(player, blueprint, null, false));
    }

    @Test
    void rejectsTraversalAndConfiguredDepthOverflow() {
        FolderManager folders = new FolderManager(new BlueprintIO(temporaryDirectory), 2);
        UUID player = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> folders.createFolder(player, "../outside"));
        assertThrows(IllegalArgumentException.class,
            () -> folders.createFolder(player, "one/two/three"));
        assertThrows(IllegalArgumentException.class,
            () -> folders.createFolder(player, "one//two"));
    }

    @Test
    void updatedDepthLimitAppliesWithoutRecreatingManager() throws Exception {
        FolderManager folders = new FolderManager(new BlueprintIO(temporaryDirectory), 1);
        UUID player = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
            () -> folders.createFolder(player, "one/two"));

        folders.setMaxDepth(2);

        assertEquals("two", folders.createFolder(player, "one/two").name);
    }
}
