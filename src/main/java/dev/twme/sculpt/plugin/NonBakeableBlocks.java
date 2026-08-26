package dev.twme.sculpt.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;

/**
 * Loads and checks the non-bakeable block list from the plugin data folder.
 *
 * <p>On first access, the bundled resource {@code /non-bakeable-blocks.txt}
 * is copied to {@code plugins/Sculpt/non-bakeable-blocks.txt}. Subsequent
 * loads always read from that disk copy, allowing server admins to
 * customise the list.
 *
 * <p>Blocks in this list cannot be rendered as small cubes by Sculpt
 * (stairs, doors, unsupported transparent blocks, etc.). The plugin silently
 * skips them during Sculpt mode edits and shows an ActionBar warning
 * when in SculptMode. Slabs were removed in list version 2 after their
 * {@code type=double} models became supported.
 */
public final class NonBakeableBlocks {

    private static final String FILE_NAME = "non-bakeable-blocks.txt";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final String VERSION_PREFIX = "# sculpt-list-version: ";
    private static final int CURRENT_LIST_VERSION = 2;

    private final Set<String> nonBakeable = new HashSet<>();
    private boolean loaded = false;

    /**
     * @param dataDir the plugin's data folder ({@code plugins/Sculpt/})
     * @param logger  the plugin logger
     */
    public NonBakeableBlocks(final Path dataDir, final Logger logger) {
        final Path diskFile = dataDir.resolve(FILE_NAME);

        // Copy from JAR resource to disk if not already present
        if (!Files.isRegularFile(diskFile)) {
            try (final InputStream in = getClass().getResourceAsStream(RESOURCE_PATH)) {
                if (in == null) {
                    logger.warning("[Sculpt] non-bakeable block list not found in JAR ("
                            + RESOURCE_PATH + ") — all blocks will be treated as bakeable");
                    return;
                }
                Files.createDirectories(dataDir);
                Files.copy(in, diskFile, StandardCopyOption.REPLACE_EXISTING);
                logger.info("[Sculpt] copied " + FILE_NAME + " to " + diskFile);
            } catch (final IOException e) {
                logger.log(Level.WARNING,
                        "[Sculpt] failed to copy " + FILE_NAME + " to data folder", e);
                return;
            }
        }

        // Load from disk. Version 2 removes the formerly generated slab
        // entries once; administrators may add one back afterwards to disable
        // that material explicitly.
        try {
            final List<String> lines = migrateLegacySlabs(
                diskFile, Files.readAllLines(diskFile, StandardCharsets.UTF_8),
                logger);
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    nonBakeable.add(line);
                }
            }
            loaded = true;
            logger.info("[Sculpt] loaded " + nonBakeable.size()
                    + " non-bakeable block IDs from " + diskFile);
        } catch (final IOException e) {
            logger.log(Level.WARNING,
                    "[Sculpt] failed to read " + diskFile, e);
        }
    }

    private static List<String> migrateLegacySlabs(
            final Path diskFile,
            final List<String> source,
            final Logger logger) {
        int version = 0;
        for (final String line : source) {
            final String trimmed = line.trim();
            if (!trimmed.startsWith(VERSION_PREFIX)) continue;
            try {
                version = Integer.parseInt(
                    trimmed.substring(VERSION_PREFIX.length()).trim());
            } catch (final NumberFormatException ignored) {
                version = 0;
            }
            break;
        }
        if (version >= CURRENT_LIST_VERSION) return source;

        final List<String> migrated = new ArrayList<>(source.size() + 1);
        migrated.add(VERSION_PREFIX + CURRENT_LIST_VERSION);
        int removed = 0;
        for (final String line : source) {
            final String trimmed = line.trim();
            if (trimmed.startsWith(VERSION_PREFIX)) continue;
            if (isSlabId(trimmed)) {
                removed++;
                continue;
            }
            migrated.add(line);
        }

        try {
            replaceAtomically(diskFile, migrated);
            logger.info("[Sculpt] migrated " + FILE_NAME + " to version "
                + CURRENT_LIST_VERSION + "; removed " + removed
                + " now-supported slab entries");
        } catch (final IOException migrationFailure) {
            // The in-memory migrated list still enables slabs for this run.
            logger.log(Level.WARNING,
                "[Sculpt] failed to persist " + FILE_NAME
                    + " slab migration; it will be retried on reload",
                migrationFailure);
        }
        return migrated;
    }

    private static boolean isSlabId(final String value) {
        if (value.isEmpty() || value.startsWith("#")) return false;
        final int namespace = value.indexOf(':');
        final String path = namespace < 0 ? value : value.substring(namespace + 1);
        return path.endsWith("_slab");
    }

    private static void replaceAtomically(
            final Path destination,
            final List<String> lines) throws IOException {
        final Path temporary = Files.createTempFile(
            destination.getParent(), FILE_NAME + ".", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * @return true if the given {@link Material} is known to be non-bakeable
     *         (cannot be rendered as small cubes).
     */
    public boolean isNonBakeable(final Material material) {
        if (!loaded) return false;
        return nonBakeable.contains(material.getKey().toString());
    }
}
