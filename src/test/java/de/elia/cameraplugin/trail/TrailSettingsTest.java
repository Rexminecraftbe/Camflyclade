package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TrailSettings}: how many flights are kept, where and for how long -
 * {@code cam-trail.flights}, {@code storage} and {@code keep-days}.
 */
class TrailSettingsTest {

    private static ConfigReader reader(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return new ConfigReader(config);
    }

    private static List<String> notes(ConfigReader reader) {
        return reader.getWarnings().stream().map(issue -> issue.format("{path}={value}")).toList();
    }

    @Test
    void withoutTheSectionFiveFlightsAreKeptInFilesForThirtyDays() throws InvalidConfigurationException {
        ConfigReader reader = reader("other: 1\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(StorageMode.FILE, trail.storage());
        assertEquals(5, trail.flights());
        assertEquals(30, trail.keepDays());
        assertEquals(List.of(), notes(reader));
    }

    @Test
    void inFilesFlightsHasNoLimitAndZeroKeepsAll() throws InvalidConfigurationException {
        ConfigReader reader = reader("cam-trail:\n  storage: file\n  flights: 0\n  keep-days: 0\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(0, trail.flights());
        assertEquals(0, trail.keepDays());
        assertEquals(List.of(), notes(reader));
        assertEquals(5000, new TrailSettings(reader("cam-trail:\n  flights: 5000\n")).flights());
    }

    @Test
    void inMemoryTwentyAreTheMostAndZeroIsAsManyAsThat() throws InvalidConfigurationException {
        assertEquals(TrailSettings.MAX_TRAIL_FLIGHTS,
                new TrailSettings(reader("cam-trail:\n  storage: memory\n  flights: 0\n")).flights());

        ConfigReader reader = reader("cam-trail:\n  storage: MEMORY\n  flights: 50\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(StorageMode.MEMORY, trail.storage());
        assertEquals(TrailSettings.MAX_TRAIL_FLIGHTS, trail.flights());
        assertEquals(List.of("cam-trail.flights=50"), notes(reader));
    }

    @Test
    void whatCannotBeIsReplacedWithANote() throws InvalidConfigurationException {
        ConfigReader reader = reader("cam-trail:\n  storage: disk\n  flights: -1\n  keep-days: -7\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(StorageMode.FILE, trail.storage());
        assertEquals(0, trail.flights());
        assertEquals(0, trail.keepDays());
        assertEquals(List.of("cam-trail.storage=disk", "cam-trail.flights=-1", "cam-trail.keep-days=-7"),
                notes(reader));
    }
}
