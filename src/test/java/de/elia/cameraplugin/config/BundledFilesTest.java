package de.elia.cameraplugin.config;

import de.elia.cameraplugin.menu.AdminButton;
import de.elia.cameraplugin.menu.MenuButton;
import de.elia.cameraplugin.sound.SoundSettings;
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

    /** The config file as the jar had it before it was tidied up: what every server has until its first start with this one. */
    private static final String BEFORE_TIDY_UP = "config-before-tidy-up.yml";

    @Test
    void fileFromBeforeTheTidyUpSaysWhatTheNewOneSays() {
        String text = BuiltIn.text(BuiltIn.CONFIG);

        ConfigUpdater.Result result = ConfigUpdater.update(BuiltIn.text(BEFORE_TIDY_UP), text,
                CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG);

        assertNull(result.failure());
        // The four switches of the items of the leader were all off, which the one that took their place is anyway.
        assertEquals(List.of(SettingValues.STORAGE, "follow.mirror-item-details"), result.added());
        assertTrue(result.removed().containsAll(List.of("crash-protection", "action-bar.off-duration",
                "viewpoints.create-page", "viewpoints.options.enabled", "portals.forget-radius",
                "cam-area.warning-cooldown", "time-limit.storage", "viewpoints.storage")), result.removed().toString());
        assertEquals("cooldown.enabled", result.renamed().get("time-limit.cooldowns-enabled"));
        assertEquals("camera-mode.player-visibility", result.renamed().get("camera-mode.player_visibility_mode"));
        assertEquals(List.of("2", "always"), result.values().get("cam-area.level"));
        // Every default of before is the default of now, under its new name.
        YamlConfiguration expected = BuiltIn.yaml(text);
        YamlConfiguration actual = BuiltIn.yaml(result.text());
        assertEquals(expected.getKeys(true), actual.getKeys(true));
        for (String path : expected.getKeys(true)) {
            if (!expected.isConfigurationSection(path)) {
                assertEquals(expected.get(path), actual.get(path), path);
            }
        }
        // Done once: the next start finds nothing to do.
        assertFalse(ConfigUpdater.update(result.text(), text, CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG)
                .changed());
    }

    @Test
    void languageFileFromBeforeTheTidyUpLosesTheTextsOfTheOldWaysOfTheMenu() {
        String text = BuiltIn.text(BuiltIn.LANGUAGE);
        String before = text
                .replace("  menu-spot-actions-options: ", "  menu-spot-actions: \"old\"\n  menu-spot-actions-options: ")
                .replace("  menu-spot-name: true", "  menu-spot-name: true\n  menu-spot-name-public: true");

        ConfigUpdater.Result result = ConfigUpdater.update(before, text, Set.of(), ConfigMigrations.LANGUAGE);

        assertNull(result.failure());
        assertEquals(List.of("messages.menu-spot-actions", "message-settings.menu-spot-name-public"), result.removed());
        assertEquals(text, result.text());
    }

    @Test
    void fileFromBeforeTheTidyUpKeepsWhatTheOwnerSet() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        String before = BuiltIn.text(BEFORE_TIDY_UP)
                .replace("  player_visibility_mode: true", "  player_visibility_mode: cam")
                .replace("  allow_lava_flight: true", "  allow_lava_flight: false")
                .replace("  name-mode: 1", "  name-mode: 2")
                .replace("  movement-sensitivity: 1", "  movement-sensitivity: 0")
                .replace("  display: distance", "  display: false")
                .replace("  distance-warning-cooldown: 3", "  distance-warning-cooldown: 7")
                .replace("  cooldowns-enabled: false", "  cooldowns-enabled: true")
                .replace("  cooldown-seconds: 120", "  cooldown-seconds: 30")
                .replace("  bossbar-color: BLUE", "  bossbar-color: RED")
                .replace("  level: 2", "  level: 1")
                // viewpoints.storage and time-limit.storage, each by the line above it
                .replace("#            Aufrufe\n  storage: file", "#            Aufrufe\n  storage: memory")
                .replace("sind alle vorbei\n  storage: file", "sind alle vorbei\n  storage: memory");
        assertEquals(2, before.split("storage: memory", -1).length - 1, "the test no longer finds its lines");

        ConfigUpdater.Result result = ConfigUpdater.update(before, text, CamSettings.UNLISTED_KEYS,
                ConfigMigrations.CONFIG);

        assertNull(result.failure());
        YamlConfiguration actual = BuiltIn.yaml(result.text());
        assertEquals("cam", actual.getString("camera-mode.player-visibility"));
        assertFalse(actual.getBoolean("camera-mode.allow-lava-flight"));
        assertEquals("ride", actual.getString("camera-mode.name-mode"));
        assertEquals("fixed", actual.getString("body.movement-sensitivity"));
        assertEquals("start-only", actual.getString("action-bar.display"));
        assertEquals(7, actual.getInt("camera-mode.warning-cooldown"));
        assertTrue(actual.getBoolean("cooldown.enabled"));
        assertEquals(30, actual.getInt("cooldown.seconds"));
        assertEquals("RED", actual.getString("time-limit.bossbar-color"));
        assertEquals("RED", actual.getString("cooldown.bossbar-color"));
        assertEquals("start", actual.getString("cam-area.level"));
        // A storage of their own that says otherwise stays, and is read there.
        assertEquals("file", actual.getString("storage"));
        assertEquals("memory", actual.getString("viewpoints.storage"));
        assertEquals("memory", actual.getString("cooldown.storage"));
        assertFalse(actual.contains("time-limit.storage"));
        assertFalse(actual.contains("battery.storage"));
    }

    /**
     * The config file as the jar had it before the second tidying up, which
     * merged switches and fixed fine tuning in the code: what every server
     * has until its first start with this one.
     */
    private static final String BEFORE_FEWER_SWITCHES = "config-before-fewer-switches.yml";

    @Test
    void fileFromBeforeFewerSwitchesSaysWhatTheNewOneSays() {
        String text = BuiltIn.text(BuiltIn.CONFIG);

        ConfigUpdater.Result result = ConfigUpdater.update(BuiltIn.text(BEFORE_FEWER_SWITCHES), text,
                CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG);

        assertNull(result.failure());
        assertEquals(List.of("follow.mirror-item-details"), result.added());
        assertTrue(result.removed().containsAll(List.of("exit-click-guard.seconds", "web-map.bluemap",
                "web-map.dynmap", "web-map.squaremap", "bedrock.map-icons", "body.mob-target-heads", "menu.ban-shift-step",
                "signal.levels", "camera-mode.max-distance-enabled", "camera-mode.name.color", "body.name.color",
                "sounds.start.enabled", "sounds.follow-reach.enabled", "follow.mirror-count", "follow.mirror-name")),
                result.removed().toString());
        assertEquals(Map.of(), result.values());
        assertEquals(Map.of(), result.renamed());
        // Every default of before is the default of now.
        YamlConfiguration expected = BuiltIn.yaml(text);
        YamlConfiguration actual = BuiltIn.yaml(result.text());
        assertEquals(expected.getKeys(true), actual.getKeys(true));
        for (String path : expected.getKeys(true)) {
            if (!expected.isConfigurationSection(path)) {
                assertEquals(expected.get(path), actual.get(path), path);
            }
        }
        // Done once: the next start finds nothing to do.
        assertFalse(ConfigUpdater.update(result.text(), text, CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG)
                .changed());
    }

    @Test
    void fileFromBeforeFewerSwitchesKeepsWhatTheOwnerSet() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        String before = BuiltIn.text(BEFORE_FEWER_SWITCHES)
                .replace("  max-distance-enabled: true", "  max-distance-enabled: false")
                .replace("  max-distance-mode: fixed", "  max-distance-mode: signal")
                .replace("    color: yellow", "    color: red")
                .replace("    enabled: true\n    sound: block.beacon.activate",
                        "    enabled: false\n    sound: block.beacon.activate")
                .replace("    enabled: true\n    sound: block.note_block.chime\n    volume: 1.0",
                        "    enabled: false\n    sound: block.note_block.chime\n    volume: 0.5")
                .replace("  mirror-count: false", "  mirror-count: true")
                .replace("  show-max-seconds: 300", "  show-max-seconds: 120")
                .replace("exit-click-guard:\n  enabled: true", "exit-click-guard:\n  enabled: false");
        assertTrue(before.contains("color: red") && before.contains("volume: 0.5")
                && before.contains("exit-click-guard:\n  enabled: false"), "the test no longer finds its lines");

        ConfigUpdater.Result result = ConfigUpdater.update(before, text, CamSettings.UNLISTED_KEYS,
                ConfigMigrations.CONFIG);

        assertNull(result.failure());
        YamlConfiguration actual = BuiltIn.yaml(result.text());
        // Switched off, max-distance is the value false of the mode now, whatever mode it had.
        assertEquals("false", actual.getString("camera-mode.max-distance-mode"));
        assertFalse(actual.contains("camera-mode.max-distance-enabled"));
        assertEquals(List.of("signal", "false"), result.values().get("camera-mode.max-distance-mode"));
        // A colour other than the default stays, and is read as before; the default one goes.
        assertEquals("red", actual.getString("body.name.color"));
        assertFalse(actual.contains("camera-mode.name.color"));
        // A sound switched off is one with volume 0.
        assertEquals(0.0, actual.getDouble("sounds.start.volume"));
        assertEquals(0.0, actual.getDouble("sounds.nudge.volume"));
        assertFalse(actual.contains("sounds.start.enabled"));
        assertEquals(1.0, actual.getDouble("sounds.end.volume"));
        // One of the four details shown is not all four: the one switch stays off.
        assertFalse(actual.getBoolean("follow.mirror-item-details"));
        assertFalse(actual.contains("follow.mirror-count"));
        // What can still be set stays as it was set; of exit-click-guard only the time goes, fixed now.
        assertEquals(120, actual.getInt("cam-trail.show-max-seconds"));
        assertFalse(actual.getBoolean("exit-click-guard.enabled", true));
        assertFalse(actual.contains("exit-click-guard.seconds"));
        assertFalse(ConfigUpdater.update(result.text(), text, CamSettings.UNLISTED_KEYS, ConfigMigrations.CONFIG)
                .changed());
    }

    @Test
    void allFourDetailsOfTheItemsShownAreTheOneSwitchOn() {
        String text = BuiltIn.text(BuiltIn.CONFIG);
        String before = BuiltIn.text(BEFORE_FEWER_SWITCHES)
                .replace("  mirror-count: false", "  mirror-count: true")
                .replace("  mirror-enchantments: false", "  mirror-enchantments: true")
                .replace("  mirror-durability: false", "  mirror-durability: true")
                .replace("  mirror-name: false", "  mirror-name: true");

        ConfigUpdater.Result result = ConfigUpdater.update(before, text, CamSettings.UNLISTED_KEYS,
                ConfigMigrations.CONFIG);

        assertNull(result.failure());
        YamlConfiguration actual = BuiltIn.yaml(result.text());
        assertTrue(actual.getBoolean("follow.mirror-item-details"));
        for (String old : List.of("mirror-count", "mirror-enchantments", "mirror-durability", "mirror-name")) {
            assertFalse(actual.contains("follow." + old), old);
        }
    }

    @Test
    void languageFileLosesTheNoteOfTheSignalLevels() {
        String text = BuiltIn.text(BuiltIn.LANGUAGE);
        String before = text.replace("  config-out-of-range: ",
                "  config-not-descending: \"&cThe values of {path} have to go down\"\n  config-out-of-range: ");

        ConfigUpdater.Result result = ConfigUpdater.update(before, text, Set.of(), ConfigMigrations.LANGUAGE);

        assertNull(result.failure());
        assertEquals(List.of("messages.config-not-descending"), result.removed());
        assertEquals(text, result.text());
    }

    @Test
    void everySoundOfTheConfigFileIsKnown() {
        YamlConfiguration config = BuiltIn.yaml(BuiltIn.text(BuiltIn.CONFIG));

        // The update takes the old switch of each of them out, and only of these.
        assertEquals(new java.util.LinkedHashSet<>(SoundSettings.SOUNDS),
                config.getConfigurationSection(SoundSettings.SECTION).getKeys(false));
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
        return file.equals(BuiltIn.CONFIG) ? ConfigMigrations.CONFIG : ConfigMigrations.LANGUAGE;
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    }

    private static List<String> describe(List<ConfigIssue> issues) {
        return issues.stream().map(issue -> issue.format(issue.getMessageKey() + " {path}={value}")).toList();
    }
}
