package dev.twme.sculpt.skin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinDiskCacheTest {

    private static final Logger LOG = Logger.getLogger("SkinDiskCacheTest");

    @Test
    void freshInstanceWithNoFileIsEmpty() {
        SkinDiskCache cache = new SkinDiskCache(LOG, Path.of("does-not-exist.json"));
        assertEquals(0, cache.size());
        assertFalse(cache.contains("anything"));
    }

    @Test
    void putThenFindRoundtrip(@TempDir Path dir) {
        Path file = dir.resolve("skins.json");
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        cache.put("hash-a", "value-a", "sig-a", "uuid-a");
        cache.put("hash-b", "value-b", "sig-b", null);   // nullable uuid
        assertEquals(2, cache.size());

        SkinDiskCache.Entry a = cache.find("hash-a");
        assertNotNull(a);
        assertEquals("value-a", a.value());
        assertEquals("sig-a",   a.signature());
        assertEquals("uuid-a",  a.uuid());

        SkinDiskCache.Entry b = cache.find("hash-b");
        assertNotNull(b);
        assertEquals("value-b", b.value());
        assertNull(b.uuid(), "uuid should be null when not provided");
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        // Simulate plugin restart: write with one instance, read with
        // another against the same file. The "in-memory map" is rebuilt
        // from disk on construction.
        Path file = dir.resolve("skins.json");
        SkinDiskCache writer = new SkinDiskCache(LOG, file);
        writer.put("hash-1", "v1", "s1", "u1");
        writer.put("hash-2", "v2", "s2", "u2");

        // New instance against the same file.
        SkinDiskCache reader = new SkinDiskCache(LOG, file);
        assertEquals(2, reader.size());
        assertEquals("v1", reader.find("hash-1").value());
        assertEquals("v2", reader.find("hash-2").value());
    }

    @Test
    void clearRemovesAllEntries(@TempDir Path dir) {
        Path file = dir.resolve("skins.json");
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        cache.put("a", "v", "s", "u");
        cache.put("b", "v", "s", "u");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertFalse(cache.contains("a"));
    }

    @Test
    void overwritesExistingEntry(@TempDir Path dir) {
        Path file = dir.resolve("skins.json");
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        cache.put("hash", "v1", "s1", "u1");
        cache.put("hash", "v2", "s2", "u2");
        assertEquals(1, cache.size(), "overwrite must not create a new entry");
        assertEquals("v2", cache.find("hash").value());
    }

    @Test
    void loadWarnsButDoesNotCrashOnMalformedFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("skins.json");
        Files.writeString(file, "{ this is not valid json", StandardCharsets.UTF_8);
        // Should log a warning and treat the cache as empty.
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        assertEquals(0, cache.size(),
                "malformed cache file should be treated as empty, not throw");
    }

    @Test
    void loadIgnoresSkinsKeyIfMissing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("skins.json");
        Files.writeString(file, "{\"otherKey\":{}}", StandardCharsets.UTF_8);
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        assertEquals(0, cache.size());
    }

    @Test
    void hashPngIsDeterministic() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        String h1 = SkinDiskCache.hashPng(data);
        String h2 = SkinDiskCache.hashPng(data);
        assertEquals(h1, h2);
        assertEquals(64, h1.length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void hashPngChangesWithContent() {
        String a = SkinDiskCache.hashPng(new byte[]{1, 2, 3});
        String b = SkinDiskCache.hashPng(new byte[]{1, 2, 4});
        assertFalse(a.equals(b));
    }

    @Test
    void fileIsWrittenAtomicallyWithTempAndRename(@TempDir Path dir) throws IOException {
        // After put() completes, the destination file must be present and
        // a valid JSON. No leftover *.tmp files in the directory.
        Path file = dir.resolve("skins.json");
        SkinDiskCache cache = new SkinDiskCache(LOG, file);
        cache.put("a", "v", "s", "u");

        assertTrue(Files.isRegularFile(file));
        try (var stream = Files.list(dir)) {
            long tmpLeftover = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tmpLeftover, "no .tmp files should remain after a put()");
        }
    }
}
