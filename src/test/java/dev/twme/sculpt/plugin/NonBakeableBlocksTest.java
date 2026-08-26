package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NonBakeableBlocksTest {

    @Test
    void legacyListRemovesSlabsButKeepsOtherUnsupportedShapes(
            @TempDir final Path dataDirectory) throws IOException {
        final Path list = dataDirectory.resolve("non-bakeable-blocks.txt");
        Files.write(list, List.of(
            "minecraft:oak_slab",
            "minecraft:oak_stairs",
            "minecraft:stone"));

        final NonBakeableBlocks blocks = new NonBakeableBlocks(
            dataDirectory, Logger.getAnonymousLogger());

        assertFalse(blocks.isNonBakeable(Material.OAK_SLAB));
        assertTrue(blocks.isNonBakeable(Material.OAK_STAIRS));
        assertTrue(blocks.isNonBakeable(Material.STONE));
        final String migrated = Files.readString(list);
        assertTrue(migrated.startsWith("# sculpt-list-version: 2"));
        assertFalse(migrated.contains("minecraft:oak_slab"));
    }

    @Test
    void administratorCanDisableASlabAfterTheOneTimeMigration(
            @TempDir final Path dataDirectory) throws IOException {
        final Path list = dataDirectory.resolve("non-bakeable-blocks.txt");
        Files.writeString(list, "minecraft:oak_slab\n");
        new NonBakeableBlocks(dataDirectory, Logger.getAnonymousLogger());
        Files.writeString(list, "minecraft:oak_slab\n",
            StandardOpenOption.APPEND);

        final NonBakeableBlocks reloaded = new NonBakeableBlocks(
            dataDirectory, Logger.getAnonymousLogger());

        assertTrue(reloaded.isNonBakeable(Material.OAK_SLAB));
    }

    @Test
    void bundledListNoLongerRejectsVanillaSlabs() throws IOException {
        final List<String> bundled = Files.readAllLines(
            Path.of("src/main/resources/non-bakeable-blocks.txt"));

        assertTrue(bundled.stream().noneMatch(line -> line.endsWith("_slab")));
        assertTrue(bundled.stream().anyMatch(line -> line.endsWith("_stairs")));
    }
}
