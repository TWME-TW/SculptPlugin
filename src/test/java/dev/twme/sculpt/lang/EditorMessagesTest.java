package dev.twme.sculpt.lang;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.editor.tool.ShapeTool;
import dev.twme.sculpt.editor.ToolId;

/** Every message the editor can show exists in every bundled language. */
class EditorMessagesTest {

    private static final Pattern LITERAL_KEY = Pattern.compile(
        "\"((?:editor|building|command\\.sculpt)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)+)\"(?!\\s*\\+)");

    @Test
    void literalKeysInEditorCodeExist() throws IOException {
        final Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/twme/sculpt/editor"))) {
            for (final Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                final Matcher matcher = LITERAL_KEY.matcher(Files.readString(file));
                while (matcher.find()) keys.add(matcher.group(1));
            }
        }
        keys.remove("building.shape.surface.grid");
        assertAll(keys);
    }

    @Test
    void dynamicKeysExist() {
        final List<String> keys = new ArrayList<>();
        for (final ToolId tool : ToolId.values()) {
            for (final String part : List.of("name", "left", "right")) {
                keys.add("editor.tool." + tool.id() + "." + part);
            }
        }
        for (final FillMode fill : FillMode.values()) keys.add("editor.fill." + fill.id());
        for (final SculptDisplayMode display : SculptDisplayMode.values()) {
            keys.add("editor.display." + display.id());
        }
        for (final ShapeRasterizer.BrushShape shape : ShapeRasterizer.BrushShape.values()) {
            keys.add("editor.brush_shape." + shape.name().toLowerCase(Locale.ROOT));
        }
        for (final ShapeTool.Type type : ShapeTool.Type.values()) {
            keys.add("editor.shape_type." + type.id());
            keys.add("building.shape." + type.id() + ".points");
        }
        keys.add("building.shape.surface.grid");
        for (int line = 1; line <= 8; line++) keys.add("editor.help.line" + line);
        for (final String sub : List.of("edit", "undo", "redo", "blueprint", "admin")) {
            keys.add("command.sculpt.help." + sub);
        }
        assertAll(keys);
    }

    private static void assertAll(final Iterable<String> keys) {
        for (final String language : List.of("en_us", "zh_tw")) {
            final YamlConfiguration config = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/lang/" + language + ".yml").toFile());
            for (final String key : keys) {
                assertTrue(LanguageManager.lookupString(config, key) != null,
                    language + " is missing " + key);
            }
        }
    }
}
