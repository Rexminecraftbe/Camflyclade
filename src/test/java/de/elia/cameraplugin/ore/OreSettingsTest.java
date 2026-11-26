package de.elia.cameraplugin.ore;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code hide-ores.max-y} as {@link OreSettings} reads it: a height, or no
 * limit at all.
 */
class OreSettingsTest {

    /** The value of {@code hide-ores.max-y} in this text, as the plugin hands it to {@link OreSettings#maxY}. */
    private static Integer maxY(String value) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("hide-ores:\n  max-y: " + value + "\n");
        return OreSettings.maxY(new ConfigReader(config).getString("hide-ores.max-y", "15"));
    }

    @Test
    void aHeightIsThatHeight() throws InvalidConfigurationException {
        assertEquals(15, maxY("15"));
        assertEquals(-32, maxY("-32"));
    }

    @Test
    void falseIsNoLimit() throws InvalidConfigurationException {
        assertEquals(Integer.MAX_VALUE, maxY("false"));
    }

    @Test
    void offWithoutQuotesIsNoLimitAsWell() throws InvalidConfigurationException {
        // YAML reads it as false - the word the file showed until then, which
        // was turned away and replaced by 15.
        assertEquals(Integer.MAX_VALUE, maxY("off"));
        assertEquals(Integer.MAX_VALUE, maxY("\"off\""));
    }

    @Test
    void anythingElseIsNone() throws InvalidConfigurationException {
        assertNull(maxY("high"));
        assertNull(maxY("15.5"));
        assertNull(maxY("true"));
    }
}
