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

    // ------------------------------------------------- taking out and moving

    private static ConfigUpdater.Result migrate(String file, String builtIn,
                                                java.util.function.Consumer<ConfigUpdater> change) {
        return ConfigUpdater.update(file, builtIn, Set.of(),
                List.of(new ConfigUpdater.Migration(ConfigUpdater.Migration.ALWAYS, change)));
    }

    @Test
    void removeTakesTheKeyWithItsCommentAndLeavesOneGap() {
        String file = BUILT_IN.replace("""
                  enabled: true

                """, """
                  enabled: true

                  # How often, in ticks
                  interval: 10

                """);

        ConfigUpdater.Result result = migrate(file, BUILT_IN, updater -> updater.remove("first.interval"));

        assertEquals(BUILT_IN, result.text());
        assertEquals(List.of("first.interval"), result.removed());
        assertEquals(List.of(), result.added());
        assertNull(result.failure());
    }

    @Test
    void removeLeavesACommentThatSpeaksOfTheKeysBelowAsWell() {
        String builtIn = """
                messages:
                  # The page of the spots: -actions under each spot
                  spot-actions-options: "options"
                  spot-name: "name"
                """;
        String file = """
                messages:
                  # The page of the spots: -actions under each spot
                  spot-actions: "old"
                  spot-actions-bedrock: "old too"
                  spot-actions-options: "options"
                  spot-name: "name"
                """;

        ConfigUpdater.Result result = migrate(file, builtIn, updater -> {
            updater.remove("messages.spot-actions");
            updater.remove("messages.spot-actions-bedrock");
        });

        assertEquals(builtIn, result.text());
        assertEquals(List.of("messages.spot-actions", "messages.spot-actions-bedrock"), result.removed());
    }

    @Test
    void removeOfTheFirstKeyOfASectionLeavesNoGapAtItsStart() {
        String builtIn = "section:\n  # Kept\n  kept: 1\n";
        String file = "section:\n  # Gone\n  gone: 2\n\n  # Kept\n  kept: 1\n";

        ConfigUpdater.Result result = migrate(file, builtIn, updater -> updater.remove("section.gone"));

        assertEquals(builtIn, result.text());
    }

    @Test
    void removeOfTheLastKeyTakesTheSectionWithIt() {
        String file = BUILT_IN.replace("""
                # Worlds without camera mode""", """
                # Gone for good
                gone:
                  enabled: true

                # Worlds without camera mode""");

        ConfigUpdater.Result result = migrate(file, BUILT_IN, updater -> updater.remove("gone.enabled"));

        assertEquals(BUILT_IN, result.text());
        assertEquals(List.of("gone.enabled"), result.removed());
    }

    @Test
    void moveWritesTheKeyWithItsNewCommentAndTheOldValue() {
        String builtIn = """
                section:
                  # Who sees him: cam, true or false
                  player-visibility: true

                  # Next
                  next: 1
                """;
        String file = """
                section:
                  # Who sees him (old words)
                  player_visibility_mode: cam   # ours

                  # Next
                  next: 1
                """;

        ConfigUpdater.Result result = migrate(file, builtIn,
                updater -> updater.move("section.player_visibility_mode", "section.player-visibility"));

        assertEquals(builtIn.replace("player-visibility: true", "player-visibility: cam   # ours"), result.text());
        assertEquals(Map.of("section.player_visibility_mode", "section.player-visibility"), result.renamed());
        assertEquals(List.of(), result.added());
        assertEquals(List.of(), result.removed());
        assertNull(result.failure());
    }

    @Test
    void moveIntoASectionTheFileLacksWritesTheSectionAndCopyFillsIt() {
        String builtIn = """
                limit:
                  # How long
                  seconds: 300
                  color: BLUE

                # The wait
                wait:
                  enabled: false
                  seconds: 120
                  color: BLUE
                """;
        String file = """
                limit:
                  # Wait after the end
                  wait-enabled: true
                  # How long
                  seconds: 300
                  # How long to wait
                  wait-seconds: 30
                  color: RED
                """;

        ConfigUpdater.Result result = migrate(file, builtIn, updater -> {
            updater.move("limit.wait-enabled", "wait.enabled");
            updater.move("limit.wait-seconds", "wait.seconds");
            updater.copy("limit.color", "wait.color");
        });

        assertNull(result.failure());
        assertEquals(builtIn.replace("enabled: false", "enabled: true").replace("seconds: 120", "seconds: 30")
                .replace("limit:\n  # How long\n  seconds: 300\n  color: BLUE", "limit:\n  # How long\n  seconds: 300\n  color: RED")
                .replace("  enabled: true\n  seconds: 30\n  color: BLUE", "  enabled: true\n  seconds: 30\n  color: RED"),
                result.text());
        assertEquals(Map.of("limit.wait-enabled", "wait.enabled", "limit.wait-seconds", "wait.seconds"),
                result.renamed());
        // Written because of the moves, not because the file lacked it.
        assertEquals(List.of(), result.added());
    }

    @Test
    void copyLeavesASectionAloneThatWasThereBefore() {
        String builtIn = "limit:\n  color: BLUE\nwait:\n  color: BLUE\n";
        String file = "limit:\n  color: RED\nwait:\n  color: GREEN\n";

        assertFalse(migrate(file, builtIn, updater -> updater.copy("limit.color", "wait.color")).changed());
    }

    @Test
    void moveOnlyTakesTheOldKeyOutWhereTheNewOneIsThereAlready() {
        String builtIn = "section:\n  range: 100\n";
        String file = "section:\n  range: 70\n  distance: 50\n";

        ConfigUpdater.Result result = migrate(file, builtIn, updater -> updater.move("section.distance", "section.range"));

        assertEquals(builtIn.replace("100", "70"), result.text());
        assertEquals(List.of("section.distance"), result.removed());
        assertEquals(Map.of(), result.renamed());
    }

    @Test
    void renameValueWritesTheNewValueWithTheNewComment() {
        String builtIn = """
                section:
                  # How strict: false, start or always
                  level: always
                """;
        String file = """
                section:
                  # How strict: 0, 1 or 2
                  level: 1
                """;
        Map<String, String> words = Map.of("0", "false", "1", "start", "2", "always");

        ConfigUpdater.Result result = migrate(file, builtIn, updater -> updater.renameValue("section.level", words::get));

        assertEquals(builtIn.replace("level: always", "level: start"), result.text());
        assertEquals(Map.of("section.level", List.of("1", "start")), result.values());
        assertFalse(migrate(builtIn, builtIn, updater -> updater.renameValue("section.level", words::get)).changed());
    }

    @Test
    void moveOutsideKeepsAKeyTheBuiltInFileDoesNotHave() {
        String builtIn = "limit:\n  seconds: 300\n\nwait:\n  enabled: false\n";
        String file = "limit:\n  seconds: 300\n  storage: memory\n\nwait:\n  enabled: true\n";

        ConfigUpdater.Result result = migrate(file, builtIn,
                updater -> updater.moveOutside("limit.storage", "wait.storage"));

        assertEquals("limit:\n  seconds: 300\n\nwait:\n  enabled: true\n  storage: memory\n", result.text());
        assertEquals(Map.of("limit.storage", "wait.storage"), result.renamed());
    }

    @Test
    void moveOutsideLeavesTheKeyWhereThereIsNoSectionToTakeIt() {
        String builtIn = "limit:\n  seconds: 300\n";
        String file = "limit:\n  seconds: 300\n  storage: memory\n";

        assertFalse(migrate(file, builtIn, updater -> updater.moveOutside("limit.storage", "nowhere.storage"))
                .changed());
        assertFalse(migrate(file, builtIn, updater -> updater.moveOutside("limit.storage", "limit.kept"))
                .changed());
    }

    @Test
    void valueReadsWhatYamlReads() {
        String file = "section:\n  quoted: \"memory\"   # a note\n  number: 2\n  list:\n    - a\n";
        String[] read = new String[3];

        migrate(file, file, updater -> {
            read[0] = updater.value("section.quoted");
            read[1] = updater.value("section.number");
            read[2] = updater.value("section.list");
        });

        assertEquals("memory", read[0]);
        assertEquals("2", read[1]);
        assertNull(read[2]);
    }
}
