package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.nightvision.NightVisionMode;
import org.bukkit.WeatherType;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Spot} and {@link SpotLook}: which names a spot may have, what it is
 * found by, and that it comes back from a file the way it went in.
 */
class SpotTest {

    private static final SpotLook SUNSET_RAIN = new SpotLook(12_500L, WeatherType.DOWNFALL, NightVisionMode.DARK);

    private static Spot spot(String name) {
        return new Spot(name, "world", 10.5, 64.0, -20.25, 90.5f, -12.0f, 1_700_000_000_000L, SUNSET_RAIN,
                "filled_map");
    }

    /** The spot written to a file, the file saved as text and loaded again, and the spot read back from it. */
    private static Spot throughAFile(Spot spot) throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        spot.write(file, "spots." + Spot.pathKey(spot.key()));
        YamlConfiguration again = new YamlConfiguration();
        again.loadFromString(file.saveToString());
        return Spot.read(again.getConfigurationSection("spots." + Spot.pathKey(spot.key())));
    }

    @Test
    void nameLosesTheSpacesAtItsEndsAndKeepsOneInBetween() {
        assertEquals("my tower", Spot.validName("  my   tower  "));
        assertEquals("a b", Spot.validName("a\tb"));
    }

    @Test
    void nameMayHaveOneToSixteenCharacters() {
        assertEquals("x", Spot.validName("x"));
        assertEquals("abcdefghijklmnop", Spot.validName("abcdefghijklmnop"));
        assertNull(Spot.validName("abcdefghijklmnopq"));
        assertNull(Spot.validName(""));
        assertNull(Spot.validName("    "));
    }

    @Test
    void signsOutsideOfTheFirstPlaneCountAsOneCharacterEach() {
        String sixteen = "🏰".repeat(16);

        assertEquals(sixteen, Spot.validName(sixteen));
        assertNull(Spot.validName(sixteen + "🏰"));
    }

    @Test
    void spotIsFoundWithoutCapitalsOrExtraSpaces() {
        assertEquals("my tower", Spot.key("My  Tower"));
        assertEquals(Spot.key("TOWER"), spot("tower").key());
    }

    @Test
    void dotAndPercentAreWrittenSoThatThePathStaysOnePart() {
        assertEquals("v1%2E2", Spot.pathKey("v1.2"));
        assertEquals("100%25", Spot.pathKey("100%"));
        assertEquals("a%252Eb", Spot.pathKey("a%2Eb"));
        assertEquals("tower", Spot.pathKey("tower"));
    }

    @Test
    void pathKeyIsReadBackAsTheKeyItWas() {
        for (String key : new String[]{"v1.2", "100%", "a%2Eb", "a%25b", "...", "%", "tower"}) {
            assertEquals(key, Spot.fromPathKey(Spot.pathKey(key)), key);
        }
        assertEquals("a.b", Spot.fromPathKey("a%2eb"));
    }

    @Test
    void spotComesBackFromAFileTheWayItWentIn() throws InvalidConfigurationException {
        Spot tower = spot("Tower");

        assertEquals(tower, throughAFile(tower));
    }

    @Test
    void nameThatYamlWouldTakeForSomethingElseComesBackAsItWas() throws InvalidConfigurationException {
        for (String name : new String[]{"1e3", "007", "true", "no", "null", "1.5", "~", "a: b", "#1", "v1.2"}) {
            assertEquals(name, throughAFile(spot(name)).name(), name);
        }
    }

    @Test
    void spotWithoutANameOrAWorldIsNoSpot() throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                nameless:
                  world: world
                  x: 1.0
                worldless:
                  name: tower
                emptyname:
                  name: ''
                  world: world
                """);

        assertNull(Spot.read(file.getConfigurationSection("nameless")));
        assertNull(Spot.read(file.getConfigurationSection("worldless")));
        assertNull(Spot.read(file.getConfigurationSection("emptyname")));
        assertNull(Spot.read(null));
    }

    @Test
    void spotOfAnOlderVersionHasNoDateNoLookAndNoIcon() throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                old:
                  name: tower
                  world: world
                  x: 1.0
                  y: 2.0
                  z: 3.0
                  yaw: 0.0
                  pitch: 0.0
                """);

        Spot old = Spot.read(file.getConfigurationSection("old"));

        assertEquals(0L, old.created());
        assertSame(SpotLook.NONE, old.look());
        assertNull(old.icon());
    }

    @Test
    void missingLookIsNoneAndBlankIconIsNone() {
        Spot spot = new Spot("tower", "world", 0, 0, 0, 0f, 0f, 0L, null, "  ");

        assertSame(SpotLook.NONE, spot.look());
        assertNull(spot.icon());
    }

    @Test
    void newNameOrIconKeepsEverythingElse() {
        Spot tower = spot("tower");

        assertEquals(new Spot("castle", "world", 10.5, 64.0, -20.25, 90.5f, -12.0f, 1_700_000_000_000L,
                SUNSET_RAIN, "filled_map"), tower.withName("castle"));
        assertEquals(new Spot("tower", "world", 10.5, 64.0, -20.25, 90.5f, -12.0f, 1_700_000_000_000L,
                SUNSET_RAIN, null), tower.withIcon(null));
    }

    @Test
    void timeOfALookLiesWithinADay() {
        assertEquals(0L, new SpotLook(0L, null, null).time());
        assertEquals(23_999L, new SpotLook(23_999L, null, null).time());
        assertNull(new SpotLook(24_000L, null, null).time());
        assertNull(new SpotLook(-1L, null, null).time());
    }

    @Test
    void lookWithNothingOfItsOwnIsEmpty() {
        assertTrue(SpotLook.NONE.isEmpty());
        assertTrue(new SpotLook(24_000L, null, null).isEmpty());
    }

    @Test
    void lookPartThatMakesNoSenseIsLeftOut() throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                spot:
                  time: noon
                  weather: snow
                  night-vision: DARK
                """);

        SpotLook look = SpotLook.read(file.getConfigurationSection("spot"));

        assertEquals(new SpotLook(null, null, NightVisionMode.DARK), look);
    }

    @Test
    void lookWithNothingReadIsNone() throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                spot:
                  weather: snow
                """);

        assertSame(SpotLook.NONE, SpotLook.read(file.getConfigurationSection("spot")));
        assertSame(SpotLook.NONE, SpotLook.read(null));
    }

    @Test
    void wordsOfTheWeatherAndTheNightVisionGoBothWays() {
        for (WeatherType weather : WeatherType.values()) {
            assertEquals(weather, SpotLook.readWeather(SpotLook.weatherWord(weather)));
        }
        for (NightVisionMode mode : NightVisionMode.values()) {
            assertEquals(mode, SpotLook.readNightVision(SpotLook.nightVisionWord(mode)));
        }
        assertEquals(WeatherType.DOWNFALL, SpotLook.readWeather("RAIN"));
        assertEquals(NightVisionMode.ALWAYS, SpotLook.readNightVision("On"));
    }
}
