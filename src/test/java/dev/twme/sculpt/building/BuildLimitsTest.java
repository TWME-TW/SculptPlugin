package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BuildLimitsTest {

    @Test
    void bundledConfigurationMatchesDefaults() {
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(
            new File("src/main/resources/config.yml"));
        assertEquals(BuildLimits.defaults(), BuildLimits.from(config));
    }

    @Test
    void outOfRangeValuesAreClamped() {
        final YamlConfiguration config = new YamlConfiguration();
        config.set("building.maxBlocks", -5);
        config.set("building.maxThickness", 9999);
        config.set("building.brush.maxRadius", -1);
        config.set("building.history.maxEntries", 0);

        final BuildLimits limits = BuildLimits.from(config);
        assertEquals(1, limits.maxBlocks());
        assertEquals(256, limits.maxThickness());
        assertEquals(0, limits.maxBrushRadius());
        assertEquals(1, limits.historyMaxEntries());
    }
}
