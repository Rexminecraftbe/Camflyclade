package de.elia.cameraplugin.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ConfigReader}: what it reads, what it replaces, and which notes it leaves. */
class ConfigReaderTest {

    private enum Color { RED, GREEN }

    private static ConfigReader reader(String yaml) {
        return new ConfigReader(BuiltIn.yaml(yaml));
    }

    /** The notes as {@code key path=value}, to compare in one go. */
    private static List<String> notes(ConfigReader reader) {
        return reader.getWarnings().stream()
                .map(issue -> issue.format(issue.getMessageKey() + " {path}={value}"))
                .toList();
    }

    @Test
    void missingValueKeepsItsDefaultWithoutANote() {
        ConfigReader reader = reader("other: 1\n");

        assertTrue(reader.getBoolean("flag", true));
        assertEquals(5, reader.getInt("count", 5, 1, 10));
        assertEquals(2.5, reader.getDouble("range", 2.5, 0.0));
        assertEquals("dark", reader.getChoice("mode", "dark", "dark", "true"));
        assertEquals(List.of("lobby"), reader.getStringList("worlds", List.of("lobby")));
        assertEquals(Color.RED, reader.getEnum("color", Color.class, Color.RED));
        assertEquals(List.of(), notes(reader));
    }

    @Test
    void valueOfTheWrongTypeIsReportedAndReplaced() {
        ConfigReader reader = reader("flag: sometimes\n");

        assertTrue(reader.getBoolean("flag", true));

        ConfigIssue note = reader.getWarnings().getFirst();
        assertEquals("config-expected-boolean", note.getMessageKey());
        assertEquals("flag: sometimes, so true", note.format("{path}: {value}, so {used}"));
    }

    @Test
    void decimalIsNotCutDownToAWholeNumber() {
        ConfigReader reader = reader("count: 2.5\n");

        assertEquals(3, reader.getInt("count", 3));
        assertEquals(List.of("config-expected-whole-number count=2.5"), notes(reader));
    }

    @Test
    void numberBeyondTheTypeIsNotWrappedAround() {
        ConfigReader reader = reader("count: 3000000000\n");

        assertEquals(3, reader.getInt("count", 3));
        assertEquals(List.of("config-out-of-range count=3000000000"), notes(reader));
    }

    @Test
    void numbersAreKeptWithinTheirLimits() {
        ConfigReader reader = reader("""
                low: -5
                high: 500
                far: 1000.5
                fine: 50
                """);

        assertEquals(0, reader.getInt("low", 1, 0));
        assertEquals(100, reader.getInt("high", 1, 0, 100));
        assertEquals(256.0, reader.getDouble("far", 1.0, 0.0, 256.0));
        assertEquals(50, reader.getInt("fine", 1, 0, 100));
        assertEquals(List.of("config-too-small low=-5", "config-too-large high=500", "config-too-large far=1000.5"),
                notes(reader));
    }

    @Test
    void notANumberAndEndlessAreNoNumbers() {
        ConfigReader reader = reader("""
                nan: .nan
                endless: .inf
                """);

        assertEquals(1.0, reader.getDouble("nan", 1.0, 0.0, 10.0));
        assertEquals(1.0, reader.getDouble("endless", 1.0));
        assertEquals(List.of("config-expected-number nan=NaN", "config-out-of-range endless=Infinity"), notes(reader));
    }

    @Test
    void textTakesNumbersAndTruthValuesButNoSection() {
        ConfigReader reader = reader("""
                number: 12
                truth: true
                section:
                  x: 1
                """);

        assertEquals("12", reader.getString("number", "x"));
        assertEquals("true", reader.getString("truth", "x"));
        assertEquals("x", reader.getString("section", "x"));
        assertEquals(1, reader.getWarnings().size());
        assertEquals("config-expected-text", reader.getWarnings().getFirst().getMessageKey());
    }

    @Test
    void choiceIgnoresCaseAndReplacesAnUnknownOne() {
        ConfigReader reader = reader("""
                known: FIXED
                unknown: sometimes
                """);

        assertEquals("FIXED", reader.getChoice("known", "dynamic", "fixed", "dynamic"));
        assertEquals("dynamic", reader.getChoice("unknown", "dynamic", "fixed", "dynamic"));

        ConfigIssue note = reader.getWarnings().getFirst();
        assertEquals(1, reader.getWarnings().size());
        assertEquals("unknown: sometimes, allowed fixed, dynamic, so dynamic",
                note.format("{path}: {value}, allowed {allowed}, so {used}"));
    }

    @Test
    void enumIgnoresCaseAndNamesWhatIsAllowed() {
        ConfigReader reader = reader("""
                known: green
                unknown: blue
                """);

        assertEquals(Color.GREEN, reader.getEnum("known", Color.class, Color.RED));
        assertEquals(Color.RED, reader.getEnum("unknown", Color.class, Color.RED));
        assertEquals("RED, GREEN", reader.getWarnings().getFirst().format("{allowed}"));
    }

    @Test
    void listLeavesOutWhatIsNoText() {
        ConfigReader reader = reader("""
                list:
                  - lobby
                  - 3
                  - {x: 1}
                single: lobby
                """);

        assertEquals(List.of("lobby", "3"), reader.getStringList("list", List.of()));
        assertEquals(List.of("default"), reader.getStringList("single", List.of("default")));
        assertEquals(List.of("config-unknown-entry list={x=1}", "config-expected-list single=lobby"), notes(reader));
    }

    @Test
    void amountsAreReadInTheOrderOfTheFile() {
        ConfigReader reader = reader("""
                items:
                  ender_eye: 2
                  blaze_rod: 99
                  stick: some
                """);

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("ender_eye", 2);
        expected.put("blaze_rod", 64);
        expected.put("stick", 1);
        assertEquals(new ArrayList<>(expected.entrySet()),
                new ArrayList<>(reader.getAmounts("items", 1, 64).entrySet()));
        assertEquals(List.of("config-too-large items.blaze_rod=99", "config-expected-number items.stick=some"),
                notes(reader));
    }

    @Test
    void costsTakeTheWordForEverything() {
        ConfigReader reader = reader("""
                costs:
                  stone: 5
                  bedrock: stop
                  glass: -1
                """);

        Map<String, Double> costs = reader.getCosts("costs", "stop", "a number or stop", "block-cost");

        assertEquals(Map.of("stone", 5.0, "bedrock", Double.POSITIVE_INFINITY), costs);
        assertEquals(List.of("config-unknown-value costs.glass=-1"), notes(reader));
    }

    @Test
    void namesLeaveOutWhatIsNoText() {
        ConfigReader reader = reader("""
                names:
                  coal_ore: stone
                  iron_ore: 5
                """);

        assertEquals(Map.of("coal_ore", "stone"), reader.getNames("names"));
        assertEquals(List.of("config-unknown-entry names.iron_ore=5"), notes(reader));
    }

    @Test
    void renamedKeyIsReadUnderItsOldNameOnlyWhileTheNewOneIsMissing() {
        assertEquals("tour", reader("tour:\n  a: 1\n").renamed("follow", "tour"));
        assertEquals("follow", reader("tour:\n  a: 1\nfollow:\n  a: 2\n").renamed("follow", "tour"));
        assertEquals("follow", reader("other: 1\n").renamed("follow", "tour"));
    }

    @Test
    void hasOnlyWhatTheFileSays() {
        YamlConfiguration config = BuiltIn.yaml("a:\n  b: 1\n");
        config.setDefaults(BuiltIn.yaml("a:\n  c: 2\n"));
        ConfigReader reader = new ConfigReader(config);

        assertTrue(reader.has("a.b"));
        assertFalse(reader.has("a.c"));
    }

    @Test
    void unknownKeysAreReportedOncePerSection() {
        YamlConfiguration config = BuiltIn.yaml("""
                a:
                  b: 1
                  typo: 3
                c: 2
                extra:
                  x: 1
                  y: 2
                own:
                  anything: 1
                """);
        config.setDefaults(BuiltIn.yaml("""
                a:
                  b: 1
                c: 2
                """));
        ConfigReader reader = new ConfigReader(config);

        reader.warnUnknownKeys(Set.of("own"));

        assertEquals(List.of("config-unknown-key a.typo={value}", "config-unknown-key extra={value}"), notes(reader));
    }

    @Test
    void withoutTheBuiltInFileNoKeyIsUnknown() {
        ConfigReader reader = reader("anything: 1\n");

        reader.warnUnknownKeys(Set.of());

        assertEquals(List.of(), notes(reader));
    }

    @Test
    void switchesAreTruthValuesOrTheWord() {
        ConfigReader reader = reader("""
                message-settings:
                  enabled: actionbar
                  hello: actionbar
                  bye: false
                  oops: maybe
                """);

        reader.checkSwitchSection("message-settings", "actionbar", Set.of("enabled"));

        assertEquals(List.of("config-expected-boolean message-settings.enabled=actionbar",
                "config-unknown-value message-settings.oops=maybe"), notes(reader));
    }

    @Test
    void everyNoteHasItsTextInTheLanguageFile() throws IllegalAccessException {
        YamlConfiguration language = BuiltIn.yaml(BuiltIn.text(BuiltIn.LANGUAGE));
        List<String> keys = new ArrayList<>();
        for (Field field : ConfigReader.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                keys.add((String) field.get(null));
            }
        }

        assertTrue(keys.size() >= 10, "the keys of the notes were not found: " + keys);
        for (String key : keys) {
            assertTrue(language.isString("messages." + key), "lang/en.yml has no messages." + key);
        }
    }
}
