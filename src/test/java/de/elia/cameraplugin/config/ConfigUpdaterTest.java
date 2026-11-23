package de.elia.cameraplugin.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigUpdater} on small files: what it adds, where, and what it
 * leaves alone. The files of the plugin themselves are in
 * {@link BundledFilesTest}.
 */
class ConfigUpdaterTest {

    private static final String BUILT_IN = """
            config-version: 2

            # The first section
            first:
              # A switch
              enabled: true

              # How far
              distance: 100

            # Names the file picks itself
            items:
              ender_eye: 1

            # Worlds without camera mode
            worlds:
              - lobby
            """;

    /** {@link #BUILT_IN} without {@code first.distance} and its comment. */
    private static final String WITHOUT_DISTANCE = BUILT_IN.replace("""

              # How far
              distance: 100
            """, "");

    private static ConfigUpdater.Result update(String file) {
        return ConfigUpdater.update(file, BUILT_IN, Set.of(), List.of());
    }

    @Test
    void fileThatLacksNothingStaysAsItIs() {
        ConfigUpdater.Result result = update(BUILT_IN);

        assertFalse(result.changed());
        assertNull(result.failure());
    }

    @Test
    void missingKeyComesBackWithItsCommentAtItsPlace() {
        ConfigUpdater.Result result = update(WITHOUT_DISTANCE);

        assertEquals(BUILT_IN, result.text());
        assertEquals(List.of("first.distance"), result.added());
        assertNull(result.failure());
    }

    @Test
    void valuesAndCommentsOfTheOwnerStay() {
        String file = WITHOUT_DISTANCE
                .replace("enabled: true", "enabled: false   # off on our server")
                .replace("  - lobby", "  - lobby\n  - event");

        ConfigUpdater.Result result = update(file);

        String expected = BUILT_IN
                .replace("enabled: true", "enabled: false   # off on our server")
                .replace("  - lobby", "  - lobby\n  - event");
        assertEquals(expected, result.text());
    }

    @Test
    void missingSectionIsAddedWhole() {
        String file = BUILT_IN.replace("""
                # The first section
                first:
                  # A switch
                  enabled: true

                  # How far
                  distance: 100

                """, "");

        ConfigUpdater.Result result = update(file);

        assertEquals(BUILT_IN, result.text());
        assertEquals(List.of("first"), result.added());
    }

    @Test
    void sectionWhoseKeysTheFileChoosesGetsNothingAdded() {
        String file = BUILT_IN.replace("ender_eye: 1", "blaze_rod: 2");

        assertFalse(ConfigUpdater.update(file, BUILT_IN, Set.of("items"), List.of()).changed());
        // Without being told, the updater would fill the section up.
        assertEquals(List.of("items.ender_eye"), update(file).added());
    }

    @Test
    void nothingIsAddedBelowAKeyThatHasAValueOfItsOwn() {
        String file = BUILT_IN.replace("""
                first:
                  # A switch
                  enabled: true

                  # How far
                  distance: 100
                """, "first: none\n");

        assertFalse(update(file).changed());
    }

    @Test
    void olderVersionIsRaised() {
        ConfigUpdater.Result result = update(BUILT_IN.replace("config-version: 2", "config-version: 1"));

        assertEquals(BUILT_IN, result.text());
        assertEquals(List.of(), result.added());
    }

    @Test
    void fileWithoutVersionGetsTheVersion() {
        ConfigUpdater.Result result = update(BUILT_IN.replace("config-version: 2\n\n", ""));

        assertEquals(List.of("config-version"), result.added());
        assertEquals(2, BuiltIn.yaml(result.text()).getInt("config-version"));
    }

    @Test
    void lineEndingsAndByteOrderMarkStay() {
        String file = "﻿" + WITHOUT_DISTANCE.replace("\n", "\r\n");

        ConfigUpdater.Result result = update(file);

        assertEquals("﻿" + BUILT_IN.replace("\n", "\r\n"), result.text());
    }

    @Test
    void commentLeftBehindByADeletedKeyIsUsedAgain() {
        // Only the line of the key was deleted, its comment is still there.
        String file = BUILT_IN.replace("  distance: 100\n", "");

        ConfigUpdater.Result result = update(file);

        assertEquals(BUILT_IN, result.text());
    }

    @Test
    void unreadableFileIsLeftAlone() {
        ConfigUpdater.Result result = update("first: [not closed\n");

        assertFalse(result.changed());
        assertNull(result.failure());
    }

    @Test
    void layoutItCannotFollowIsReportedOnlyWhenSomethingIsMissing() {
        // An anchor is valid YAML, but nothing the updater can add to safely.
        String lacksTheWorlds = """
                config-version: 2
                first: &defaults
                  enabled: true
                  distance: 100
                items:
                  ender_eye: 1
                """;
        String complete = lacksTheWorlds + "worlds:\n  - lobby\n";

        ConfigUpdater.Result lacking = update(lacksTheWorlds);
        assertFalse(lacking.changed());
        assertEquals(ConfigUpdater.Result.LAYOUT, lacking.failure());

        ConfigUpdater.Result fine = update(complete);
        assertFalse(fine.changed());
        assertNull(fine.failure());
    }

    @Test
    void renameKeepsValueCommentAndPlace() {
        String builtIn = """
                section:
                  # How far the camera flies
                  range: 100
                """;
        String file = """
                section:
                  # How far the camera flies
                  distance: 50
                """;
        List<ConfigUpdater.Migration> rename = List.of(new ConfigUpdater.Migration(
                ConfigUpdater.Migration.ALWAYS, updater -> updater.rename("section.distance", "range")));

        ConfigUpdater.Result result = ConfigUpdater.update(file, builtIn, Set.of(), rename);

        assertEquals(file.replace("distance: 50", "range: 50"), result.text());
        assertEquals(Map.of("section.distance", "section.range"), result.renamed());
        assertEquals(List.of(), result.added());
    }

    @Test
    void renameLeavesAKeyAloneThatIsThereUnderItsNewNameAlready() {
        String builtIn = "section:\n  range: 100\n";
        String file = "section:\n  range: 70\n  distance: 50\n";
        List<ConfigUpdater.Migration> rename = List.of(new ConfigUpdater.Migration(
                ConfigUpdater.Migration.ALWAYS, updater -> updater.rename("section.distance", "range")));

        ConfigUpdater.Result result = ConfigUpdater.update(file, builtIn, Set.of(), rename);

        assertFalse(result.changed());
        assertEquals(Map.of(), result.renamed());
    }

    @Test
    void migrationRunsOnlyOnFilesBelowItsVersion() {
        List<ConfigUpdater.Migration> keepOld = List.of(new ConfigUpdater.Migration(
                2, updater -> updater.add("first.distance", "7")));

        String older = WITHOUT_DISTANCE.replace("config-version: 2", "config-version: 1");
        ConfigUpdater.Result fromOlder = ConfigUpdater.update(older, BUILT_IN, Set.of(), keepOld);
        assertEquals(BUILT_IN.replace("distance: 100", "distance: 7"), fromOlder.text());

        ConfigUpdater.Result fromCurrent = ConfigUpdater.update(WITHOUT_DISTANCE, BUILT_IN, Set.of(), keepOld);
        assertEquals(BUILT_IN, fromCurrent.text());
    }

    @Test
    void addOfAMigrationLeavesAKeyThatIsThere() {
        List<ConfigUpdater.Migration> keepOld = List.of(new ConfigUpdater.Migration(
                ConfigUpdater.Migration.ALWAYS, updater -> updater.add("first.distance", "7")));

        assertFalse(ConfigUpdater.update(BUILT_IN, BUILT_IN, Set.of(), keepOld).changed());
        assertTrue(ConfigUpdater.update(WITHOUT_DISTANCE, BUILT_IN, Set.of(), keepOld).text()
                .contains("distance: 7"));
    }
}
