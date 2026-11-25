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
 * {@code cam-trail.flights}, {@code storage} and {@code keep-days} -, and how
 * long their line is shown, {@code show-mode}, {@code show-seconds} and the
 * four times of {@code dynamic}.
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
    void showSecondsIsForFixedAndDynamicIsTheDefault() throws InvalidConfigurationException {
        TrailSettings standard = new TrailSettings(reader("other: 1\n"));
        assertEquals(TrailShowMode.DYNAMIC, standard.showMode());
        assertEquals(30, standard.showSeconds());

        ConfigReader reader = reader("cam-trail:\n  show-mode: fixed\n  show-seconds: 900\n");
        TrailSettings fixed = new TrailSettings(reader);
        assertEquals(TrailShowMode.FIXED, fixed.showMode());
        assertEquals(600, fixed.showSeconds());
        assertEquals(List.of("cam-trail.show-seconds=900"), notes(reader));
    }

    @Test
    void dynamicShowsTenSecondsUpToFiftyBlocksAndFiveMinutesFromFifteenHundredOutOfTheBox()
            throws InvalidConfigurationException {
        ConfigReader reader = reader("other: 1\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(50, trail.showMinBlocks());
        assertEquals(10, trail.showMinSeconds());
        assertEquals(1500, trail.showMaxBlocks());
        assertEquals(300, trail.showMaxSeconds());
        assertEquals(List.of(), notes(reader));
    }

    @Test
    void theTimesOfDynamicAreSetInTheFile() throws InvalidConfigurationException {
        ConfigReader reader = reader("cam-trail:\n  show-min-blocks: 0\n  show-min-seconds: 4\n"
                + "  show-max-blocks: 1000\n  show-max-seconds: 9\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(0, trail.showMinBlocks());
        assertEquals(4, trail.showMinSeconds());
        assertEquals(1000, trail.showMaxBlocks());
        assertEquals(9, trail.showMaxSeconds());
        assertEquals(List.of(), notes(reader));
    }

    @Test
    void theLongestIsNeverShorterThanTheShortest() throws InvalidConfigurationException {
        ConfigReader reader = reader("cam-trail:\n  show-min-blocks: 100\n  show-min-seconds: 8\n"
                + "  show-max-blocks: 10\n  show-max-seconds: 3\n");
        TrailSettings trail = new TrailSettings(reader);

        assertEquals(101, trail.showMaxBlocks());
        assertEquals(8, trail.showMaxSeconds());
        assertEquals(List.of("cam-trail.show-max-blocks=10", "cam-trail.show-max-seconds=3"), notes(reader));
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
