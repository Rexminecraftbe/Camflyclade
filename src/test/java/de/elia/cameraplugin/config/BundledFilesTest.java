package de.elia.cameraplugin.config;

import de.elia.cameraplugin.menu.AdminButton;
import de.elia.cameraplugin.menu.MenuButton;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The config file and the language file the jar brings along, as
 * {@link ConfigFile} handles them on every start and {@code /cam reload}:
 * {@link ConfigUpdater} has to be able to give each key back to a server
 * owner's file that lacks it, with its comment, and without changing a value.
 */
class BundledFilesTest {

    /** Of the language file every so many keys are taken out, besides the first and last of each section. */
    private static final int LANGUAGE_SAMPLE = 20;

    @ParameterizedTest
    @ValueSource(strings = {BuiltIn.CONFIG, BuiltIn.LANGUAGE})
    void builtInFileIsReadableAndItsLayoutUnderstood(String file) {
        String text = BuiltIn.text(file);

        BuiltIn.yaml(text);
        // Otherwise no file of a server owner would ever be completed.
        assertTrue(YamlOutline.of(lines(text)).isReliable(), "the updater cannot follow the layout of " + file);
    }

    @ParameterizedTest
    @ValueSource(strings = {BuiltIn.CONFIG, BuiltIn.LANGUAGE})
    void builtInFileLacksNothing(String file) {
        String text = BuiltIn.text(file);

        ConfigUpdater.Result result = ConfigUpdater.update(text, text, ownKeys(file), migrations(file));

        assertFalse(result.changed(), "the file in the jar would be changed by its own update: " + result.added());
        assertNull(result.failure());
    }

    @Test
    void switchesOfTheLanguageFileAreTruthValuesOrActionbar() {
        ConfigReader reader = new ConfigReader(BuiltIn.yaml(BuiltIn.text(BuiltIn.LANGUAGE)));

        // What Messages#check reports on every start.
        reader.checkSwitchSection("message-settings", Messages.ACTION_BAR, Set.of("enabled"));

        assertEquals(List.of(), describe(reader.getWarnings()));
    }

    @TestFactory
    Stream<DynamicTest> everyKeyOfTheConfigFileComesBack() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        YamlOutline outline = YamlOutline.of(lines(text));
        YamlConfiguration expected = BuiltIn.yaml(text);
        return keys(outline).stream()
                .filter(node -> !inOwnKeys(node.parent.path, CamSettings.UNLISTED_KEYS))
                // An older file without it gets fixed, see showModeOfAnOlderFileStaysFixed.
                .filter(node -> !node.path.equals("cam-trail.show-mode"))
                .map(node -> DynamicTest.dynamicTest(node.path,
                        () -> assertComesBack(BuiltIn.CONFIG, text, expected, outline, node)));
    }

    @TestFactory
    Stream<DynamicTest> keysOfTheLanguageFileComeBack() {
        String text = BuiltIn.text(BuiltIn.LANGUAGE);
        YamlOutline outline = YamlOutline.of(lines(text));
        YamlConfiguration expected = BuiltIn.yaml(text);
        List<YamlOutline.Node> all = keys(outline);
        List<YamlOutline.Node> sample = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            YamlOutline.Node node = all.get(i);
            List<YamlOutline.Node> siblings = node.parent.children;
            if (node.parent == outline.root() || i % LANGUAGE_SAMPLE == 0
                    || node == siblings.getFirst() || node == siblings.getLast()) {
                sample.add(node);
            }
        }
        return sample.stream().map(node -> DynamicTest.dynamicTest(node.path,
                () -> assertComesBack(BuiltIn.LANGUAGE, text, expected, outline, node)));
    }

    @Test
    void showModeOfAnOlderFileStaysFixed() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        List<String> lines = lines(text);
        YamlOutline outline = YamlOutline.of(lines);
        YamlOutline.Node showMode = outline.node("cam-trail.show-mode");
        assertTrue(outline.node("cam-trail.show-seconds") != null);

        ConfigUpdater.Result result = ConfigUpdater.update(without(lines, outline, showMode), text,
                CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG);

        assertEquals(List.of("cam-trail.show-mode"), result.added());
        assertEquals("fixed", BuiltIn.yaml(result.text()).getString("cam-trail.show-mode"));
    }

    @Test
    void fileFromBeforeFollowGetsItsKeysUnderTheNewNames() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        List<String> lines = lines(text);
        // The new path of each key and its name in a file from the time of /cam tour.
        Map<String, String> oldNames = new LinkedHashMap<>();
        oldNames.put(CamSettings.FOLLOW_SECTION, CamSettings.OLD_FOLLOW_SECTION);
        oldNames.put("sounds.follow-invite", "tour-invite");
        oldNames.put("sounds.follow-reach", "tour-reach");
        for (MenuButton button : MenuButton.values()) {
            if (button.oldKey() != null) {
                oldNames.put("menu.buttons." + button.key(), button.oldKey());
            }
        }
        for (AdminButton button : AdminButton.values()) {
            if (button.oldKey() != null) {
                oldNames.put("menu.admin-buttons." + button.key(), button.oldKey());
            }
        }
        for (Map.Entry<String, String> name : oldNames.entrySet()) {
            YamlOutline.Node node = YamlOutline.of(lines).node(name.getKey());
            assertTrue(node != null, "not in the config file: " + name.getKey());
            String line = lines.get(node.line);
            lines.set(node.line, line.substring(0, node.keyStart) + name.getValue() + line.substring(node.keyEnd));
        }

        ConfigUpdater.Result result = ConfigUpdater.update(String.join("\n", lines), text,
                CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG);

        assertNull(result.failure());
        assertEquals(List.of(), result.added());
        assertEquals(oldNames.size(), result.renamed().size());
        for (Map.Entry<String, String> name : oldNames.entrySet()) {
            String newPath = name.getKey();
            int dot = newPath.lastIndexOf('.');
            String oldPath = dot < 0 ? name.getValue() : newPath.substring(0, dot + 1) + name.getValue();
            assertEquals(newPath, result.renamed().get(oldPath));
        }
        assertEquals(text, result.text());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Takes a key out of the built-in file - with its comment, the way a
     * server owner deletes it - and checks that the update puts it back: the
     * same values everywhere, and the key with its comment as in the jar.
     */
    private static void assertComesBack(String file, String text, YamlConfiguration expected,
                                        YamlOutline outline, YamlOutline.Node node) {
        List<String> lines = lines(text);
        ConfigUpdater.Result result = ConfigUpdater.update(without(lines, outline, node), text,
                ownKeys(file), migrations(file));

        assertNull(result.failure(), "the update failed");
        assertTrue(result.changed(), "the key was not given back");
        assertEquals(List.of(node.path), result.added());

        YamlConfiguration actual = BuiltIn.yaml(result.text());
        assertEquals(expected.getKeys(true), actual.getKeys(true));
        for (String path : expected.getKeys(true)) {
            if (!expected.isConfigurationSection(path) && !Objects.equals(expected.get(path), actual.get(path))) {
                fail("after the update " + path + " is " + actual.get(path) + " instead of " + expected.get(path));
            }
        }

        YamlOutline after = YamlOutline.of(lines(result.text()));
        YamlOutline.Node back = after.node(node.path);
        assertEquals(lines.subList(outline.commentStart(node), node.end + 1),
                after.lines().subList(after.commentStart(back), back.end + 1),
                "the key is not back as it stands in the jar, with its comment");
    }

    /** The text without a key, its comment and everything below it. */
    private static String without(List<String> lines, YamlOutline outline, YamlOutline.Node node) {
        List<String> cut = new ArrayList<>(lines);
        cut.subList(outline.commentStart(node), node.end + 1).clear();
        return String.join("\n", cut);
    }

    /** Every key of the file, each before the keys below it. */
    private static List<YamlOutline.Node> keys(YamlOutline outline) {
        List<YamlOutline.Node> keys = new ArrayList<>();
        collect(outline.root(), keys);
        return keys;
    }

    private static void collect(YamlOutline.Node node, List<YamlOutline.Node> into) {
        for (YamlOutline.Node child : node.children) {
            into.add(child);
            collect(child, into);
        }
    }

    private static boolean inOwnKeys(String path, Set<String> ownKeys) {
        for (String own : ownKeys) {
            if (path.equals(own) || path.startsWith(own + ".")) {
                return true;
            }
        }
        return false;
    }

    /** As {@link ConfigFile} completes the file. */
    private static Set<String> ownKeys(String file) {
        return file.equals(BuiltIn.CONFIG) ? CamSettings.UNLISTED_KEYS : Set.of();
    }

    /** As {@link ConfigFile} completes the file. */
    private static List<ConfigUpdater.Migration> migrations(String file) {
        return file.equals(BuiltIn.CONFIG) ? ConfigMigrations.CONFIG : List.of();
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    }

    private static List<String> describe(List<ConfigIssue> issues) {
        return issues.stream().map(issue -> issue.format(issue.getMessageKey() + " {path}={value}")).toList();
    }
}
