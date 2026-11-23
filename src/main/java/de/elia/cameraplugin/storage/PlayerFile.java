package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.resume.LastFlight;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotLook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * The text of the file of one player, {@code playerdata/stats/<uuid>.yml}:
 * a {@link PlayerRecord} written as YAML, and read back from it. Where the
 * file lies, when it is written and what happens to one that cannot be read
 * is up to {@link PlayerStore}; this only turns the one into the other, so
 * the fast tests check it without a server or a disk.
 */
final class PlayerFile {

    /** Which layout a file has, for a later version that changes it. */
    static final int FORMAT = 1;

    /** What stands at the top of every file, for whoever finds one and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: statistics, battery charge, cooldown, spots of /cam spot, own settings and the end of the last",
            "flight (/cam resume) of this player.",
            "Deleting it starts the player from nothing: no statistics, a full battery, no cooldown, no spots, the",
            "settings of the server.");

    private PlayerFile() {
    }

    /**
     * Reads a record from the text of its file. Numbers that cannot be right
     * - below 0 - are taken as 0, a spot without a name or a world and a
     * setting that makes no sense are left out; the words of the settings
     * are those of a spot, see {@link SpotLook}.
     *
     * @throws InvalidConfigurationException when the text is no YAML
     */
    static PlayerRecord read(String text) throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString(text);
        Double charge = file.isSet("battery.charge") ? file.getDouble("battery.charge") : null;
        Long cooldown = file.isSet("cooldown.started") ? file.getLong("cooldown.started") : null;
        long sessions = Math.max(0L, file.getLong("stats.sessions"));
        double flown = Math.max(0.0, file.getDouble("stats.distance-flown"));
        // A file from before the longest way was counted does not say it.
        // The longest flight is at least as long as the average one - with
        // a single flight it is that one - so that is where it starts, and
        // the next flight that goes farther puts the real one in its place.
        double longestFlown = file.isSet("stats.longest-distance")
                ? Math.max(0.0, file.getDouble("stats.longest-distance"))
                : sessions > 0 ? flown / sessions : 0.0;
        PlayerRecord record = new PlayerRecord(
                file.getString("name"),
                sessions,
                Math.max(0L, file.getLong("stats.total-seconds")),
                Math.max(0L, file.getLong("stats.longest-seconds")),
                flown,
                longestFlown,
                Math.max(0.0, file.getDouble("stats.farthest-from-body")),
                // A file from before this was noted does not say since when
                // its numbers count. With numbers in it, that stays unknown
                // until they are reset; without, the next flight says it.
                Math.max(0L, file.getLong("stats.counted-since")),
                Math.max(0L, file.getLong("stats.last-flight")),
                charge == null ? null : Math.max(0.0, charge),
                file.getLong("battery.updated"),
                cooldown);
        readSpots(file, record);
        record.load(SpotLook.readNightVision(file.getString("settings.night-vision")),
                file.isSet("settings.flight-speed") ? file.getDouble("settings.flight-speed") : null,
                file.isSet("settings.time") ? file.getLong("settings.time") : null,
                SpotLook.readWeather(file.getString("settings.weather")),
                readLastFlight(file.getConfigurationSection("last-flight")));
        return record;
    }

    /** The text of the file of a record, as {@link #read} reads it back. */
    static String text(PlayerRecord record) {
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        file.set("format", FORMAT);
        file.set("name", record.getName());
        file.set("stats.sessions", record.getSessions());
        file.set("stats.total-seconds", record.getTotalSeconds());
        file.set("stats.longest-seconds", record.getLongestSeconds());
        file.set("stats.distance-flown", Math.round(record.getDistanceFlown() * 10.0) / 10.0);
        file.set("stats.longest-distance", Math.round(record.getLongestDistance() * 10.0) / 10.0);
        file.set("stats.farthest-from-body", Math.round(record.getFarthestFromBody() * 10.0) / 10.0);
        if (record.getCountedSince() > 0L) {
            file.set("stats.counted-since", record.getCountedSince());
        }
        if (record.getLastFlightEnded() > 0L) {
            file.set("stats.last-flight", record.getLastFlightEnded());
        }
        if (record.getBatteryCharge() != null) {
            file.set("battery.charge", record.getBatteryCharge());
            file.set("battery.updated", record.getBatteryUpdated());
        }
        if (record.getCooldownStarted() != null) {
            file.set("cooldown.started", record.getCooldownStarted());
        }
        for (Spot spot : record.getSpots().values()) {
            spot.write(file, "spots." + Spot.pathKey(spot.key()));
        }
        if (record.getNightVision() != null) {
            file.set("settings.night-vision", SpotLook.nightVisionWord(record.getNightVision()));
        }
        if (record.getFlightSpeed() != null) {
            file.set("settings.flight-speed", record.getFlightSpeed());
        }
        if (record.getCamTime() != null) {
            file.set("settings.time", record.getCamTime());
        }
        if (record.getCamWeather() != null) {
            file.set("settings.weather", SpotLook.weatherWord(record.getCamWeather()));
        }
        LastFlight flight = record.getLastFlight();
        if (flight != null) {
            file.set("last-flight.world", flight.world());
            file.set("last-flight.x", flight.x());
            file.set("last-flight.y", flight.y());
            file.set("last-flight.z", flight.z());
            file.set("last-flight.yaw", (double) flight.yaw());
            file.set("last-flight.pitch", (double) flight.pitch());
            flight.look().write(file, "last-flight");
        }
        return file.saveToString();
    }

    /** The end of the last flight, or {@code null} for none or one without a world. */
    private static LastFlight readLastFlight(ConfigurationSection section) {
        String world = section == null ? null : section.getString("world");
        if (world == null || world.isEmpty()) {
            return null;
        }
        // A file of an older version may name the visit of /cam visit the
        // flight was on (visit-owner, visit-spot): that is gone, the flight
        // goes on where it ended.
        return new LastFlight(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"), SpotLook.read(section));
    }

    /**
     * Reads the spots of a file, see {@link Spot#read}. One without a name or
     * a world is left out.
     */
    private static void readSpots(YamlConfiguration file, PlayerRecord record) {
        ConfigurationSection section = file.getConfigurationSection("spots");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Spot spot = Spot.read(section.getConfigurationSection(key));
            if (spot != null) {
                record.loadSpot(spot);
            }
        }
    }
}
