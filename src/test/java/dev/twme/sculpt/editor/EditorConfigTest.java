package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EditorConfigTest {

    @Test
    void bundledConfigurationMatchesDefaults() {
        assertEquals(EditorConfig.defaults(), EditorConfig.from(
            YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"))));
    }

    @Test
    void valuesAreClamped() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("editor.reach", 500);
        config.set("editor.previewBudget", 1);
        config.set("editor.animations", false);
        config.set("editor.hudIntervalTicks", 0);

        final EditorConfig editor = EditorConfig.from(config);
        assertEquals(32.0, editor.reach());
        assertEquals(64, editor.previewBudget());
        assertFalse(editor.animations());
        assertEquals(1, editor.hudIntervalTicks());
    }
}
