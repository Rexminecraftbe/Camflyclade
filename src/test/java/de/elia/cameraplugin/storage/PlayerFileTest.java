package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.resume.LastFlight;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotLook;
import org.bukkit.WeatherType;
import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerFile}: what the file of a player says, and that a record comes
 * back from it the way it went in - also from files of older versions and
 * files somebody edited by hand.
 */
class PlayerFileTest {

    private static final double DELTA = 1e-9;

    private static PlayerRecord again(PlayerRecord record) throws InvalidConfigurationException {
        return PlayerFile.read(PlayerFile.text(record));
    }

    private static Spot spot(String name) {
        return new Spot(name, "world", 1.5, 70.0, -3.0, 45.0f, 10.0f, 1_700_000_000_000L,
                new SpotLook(6_000L, WeatherType.CLEAR, null), null);
    }

    @Test
    void emptyRecordComesBackEmpty() throws InvalidConfigurationException {
        PlayerRecord record = again(new PlayerRecord());

        assertEquals(0L, record.getSessions());
        assertNull(record.getBatteryCharge());
        assertNull(record.getCooldownStarted());
        assertTrue(record.getSpots().isEmpty());
        assertNull(record.getNightVision());
        assertNull(record.getFlightSpeed());
        assertNull(record.getCamTime());
        assertNull(record.getCamWeather());
        assertNull(record.getLastFlight());
    }

    @Test
    void everythingComesBackTheWayItWentIn() throws InvalidConfigurationException {
        PlayerRecord record = new PlayerRecord("Alex", 12L, 3_600L, 900L, 1_234.5, 300.25, 88.0,
                1_600_000_000_000L, 1_700_000_000_000L, 42.5, 1_700_000_100_000L, 1_700_000_200_000L);
        record.putSpot("Alex", spot("Tower"));
        record.putSpot("Alex", spot("Bridge"));
        record.setNightVision(NightVisionMode.DARK);
        record.setFlightSpeed(1.75);
        record.setCamTime(18_000L);
        record.setCamWeather(WeatherType.DOWNFALL);
        LastFlight flight = new LastFlight("world_nether", 5.0, 40.0, 6.0, 180.0f, -30.0f,
                new SpotLook(null, WeatherType.CLEAR, NightVisionMode.ALWAYS));
        record.setLastFlight(flight);

        PlayerRecord read = again(record);

        assertEquals("Alex", read.getName());
        assertEquals(12L, read.getSessions());
        assertEquals(3_600L, read.getTotalSeconds());
        assertEquals(900L, read.getLongestSeconds());
        assertEquals(1_234.5, read.getDistanceFlown(), DELTA);
        // Distances are kept to a tenth of a block.
        assertEquals(300.3, read.getLongestDistance(), DELTA);
        assertEquals(88.0, read.getFarthestFromBody(), DELTA);
        assertEquals(1_600_000_000_000L, read.getCountedSince());
        assertEquals(1_700_000_000_000L, read.getLastFlightEnded());
        assertEquals(42.5, read.getBatteryCharge(), DELTA);
        assertEquals(1_700_000_100_000L, read.getBatteryUpdated());
        assertEquals(1_700_000_200_000L, read.getCooldownStarted());
        assertEquals(List.of(spot("Tower"), spot("Bridge")), List.copyOf(read.getSpots().values()));
        assertEquals(NightVisionMode.DARK, read.getNightVision());
        assertEquals(1.75, read.getFlightSpeed(), DELTA);
        assertEquals(18_000L, read.getCamTime());
        assertEquals(WeatherType.DOWNFALL, read.getCamWeather());
        assertEquals(flight, read.getLastFlight());
    }

    @Test
    void recordReadFromItsFileHasNothingToWrite() throws InvalidConfigurationException {
        PlayerRecord record = new PlayerRecord();
        record.putSpot("Alex", spot("Tower"));
        record.setNightVision(NightVisionMode.OFF);

        assertFalse(again(record).isDirty());
    }

    @Test
    void spotWithADotInItsNameKeepsIt() throws InvalidConfigurationException {
        PlayerRecord record = new PlayerRecord();
        record.putSpot("Alex", spot("v1.2"));
        record.putSpot("Alex", spot("100%"));

        PlayerRecord read = again(record);

        assertEquals("v1.2", read.getSpots().get("v1.2").name());
        assertEquals("100%", read.getSpots().get("100%").name());
    }

    @Test
    void fileStartsWithWhatItIs() {
        String text = PlayerFile.text(new PlayerRecord());

        assertTrue(text.startsWith("# CamFly: statistics"), text);
        assertTrue(text.contains("format: " + PlayerFile.FORMAT), text);
    }

    @Test
    void longestWayOfAnOlderFileStartsFromTheAverageFlight() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                stats:
                  sessions: 4
                  distance-flown: 100.0
                """);

        assertEquals(25.0, record.getLongestDistance(), DELTA);
        assertEquals(0L, record.getCountedSince());
    }

    @Test
    void numbersBelowZeroAreTakenAsZero() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                stats:
                  sessions: -3
                  total-seconds: -10
                  distance-flown: -5.5
                  farthest-from-body: -1.0
                battery:
                  charge: -20.0
                  updated: 5
                """);

        assertEquals(0L, record.getSessions());
        assertEquals(0L, record.getTotalSeconds());
        assertEquals(0.0, record.getDistanceFlown(), DELTA);
        assertEquals(0.0, record.getFarthestFromBody(), DELTA);
        assertEquals(0.0, record.getBatteryCharge(), DELTA);
    }

    @Test
    void nightVisionOnAndOffComeBack() throws InvalidConfigurationException {
        // YAML would take a bare on or off for true or false: the file has to put them in quotes.
        for (NightVisionMode mode : NightVisionMode.values()) {
            PlayerRecord record = new PlayerRecord();
            record.setNightVision(mode);

            assertEquals(mode, again(record).getNightVision(), PlayerFile.text(record));
        }
    }

    @Test
    void settingsAreReadWhateverTheirCapitals() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                settings:
                  night-vision: 'ON'
                  weather: Rain
                """);

        assertEquals(NightVisionMode.ALWAYS, record.getNightVision());
        assertEquals(WeatherType.DOWNFALL, record.getCamWeather());
        assertEquals(NightVisionMode.DARK, PlayerFile.read("settings:\n  night-vision: Dark\n").getNightVision());
    }

    @Test
    void settingThatMakesNoSenseIsLeftOut() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                settings:
                  night-vision: sometimes
                  weather: snow
                """);

        assertNull(record.getNightVision());
        assertNull(record.getCamWeather());
    }

    @Test
    void spotWithoutAWorldAndFlightWithoutAWorldAreLeftOut() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                spots:
                  tower:
                    name: tower
                    x: 1.0
                  bridge:
                    name: bridge
                    world: world
                last-flight:
                  world: ''
                  x: 1.0
                """);

        assertEquals(List.of("bridge"), List.copyOf(record.getSpots().keySet()));
        assertNull(record.getLastFlight());
    }

    @Test
    void lastFlightOfAnOlderVersionWithAVisitGoesOnWhereItEnded() throws InvalidConfigurationException {
        PlayerRecord record = PlayerFile.read("""
                last-flight:
                  world: world
                  x: 1.0
                  y: 2.0
                  z: 3.0
                  yaw: 90.0
                  pitch: 0.0
                  visit-owner: 3f1d0e6a-0000-0000-0000-000000000000
                  visit-spot: tower
                """);

        assertEquals(new LastFlight("world", 1.0, 2.0, 3.0, 90.0f, 0.0f, SpotLook.NONE), record.getLastFlight());
    }

    @Test
    void textThatIsNoYamlIsTurnedDown() {
        assertThrows(InvalidConfigurationException.class, () -> PlayerFile.read("stats: [sessions"));
    }
}
