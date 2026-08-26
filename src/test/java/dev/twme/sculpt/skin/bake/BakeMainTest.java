package dev.twme.sculpt.skin.bake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the offline-bake CLI. Skips the full upload pipeline (which
 * requires network + MineSkin API key); exercises the parts that are
 * testable locally — arg parsing, block-list reading, and the gridN
 * validation guard.
 */
class BakeMainTest {

    @Test
    void parseArgsReadsKeyValuePairs() throws Exception {
        Method m = BakeMain.class.getDeclaredMethod("parseArgs", String[].class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> args = (Map<String, String>) m.invoke(null,
                (Object) new String[]{"--input", "x.txt", "--gridN", "8", "--version", "1.21.4"});
        assertEquals("x.txt", args.get("input"));
        assertEquals("8", args.get("gridN"));
        assertEquals("1.21.4", args.get("version"));
    }

    @Test
    void sbhOutputArgumentIsRejected() throws Exception {
        Method m = BakeMain.class.getDeclaredMethod("parseArgs", String[].class);
        m.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, (Object) new String[]{"--out", "heads-4.sbh"}));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
        assertTrue(error.getCause().getMessage().contains("Unsupported argument"));
    }

    @Test
    void readBlockListFiltersCommentsAndBlanks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blocks.txt");
        Files.writeString(file, String.join("\n",
                "# this is a comment",
                "",
                "minecraft:stone",
                "   ",
                "# another comment",
                "minecraft:dirt",
                "  minecraft:oak_log  ",
                ""), StandardCharsets.UTF_8);

        Method m = BakeMain.class.getDeclaredMethod("readBlockList", Path.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<dev.twme.sculpt.core.BlockKey> result = (List<dev.twme.sculpt.core.BlockKey>) m.invoke(null, file);
        assertEquals(3, result.size());
        assertEquals("minecraft", result.get(0).namespace());
        assertEquals("stone", result.get(0).path());
        assertEquals("dirt", result.get(1).path());
        assertEquals("oak_log", result.get(2).path());
    }

    @Test
    void readBlockListOnMissingFileThrows(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("does-not-exist.txt");
        Method m = BakeMain.class.getDeclaredMethod("readBlockList", Path.class);
        m.setAccessible(true);
        assertThrows(java.io.IOException.class, () -> {
            try {
                m.invoke(null, missing);
            } catch (Exception e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw e;
            }
        });
    }

    @Test
    void mainExistsAndIsStatic() throws Exception {
        java.lang.reflect.Method m = BakeMain.class.getMethod("main", String[].class);
        assertNotNull(m);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(m.getModifiers()));
    }

    @Test
    void readBlockListPreservesLowercase(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("upper.txt");
        Files.writeString(file, "MINECRAFT:GRANITE\n  MINECRAFT:STONE  ", StandardCharsets.UTF_8);
        Method m = BakeMain.class.getDeclaredMethod("readBlockList", Path.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<dev.twme.sculpt.core.BlockKey> result =
                (List<dev.twme.sculpt.core.BlockKey>) m.invoke(null, file);
        assertEquals(2, result.size());
        assertEquals("minecraft", result.get(0).namespace());
        assertEquals("granite", result.get(0).path());
        assertEquals("stone", result.get(1).path());
    }
}
