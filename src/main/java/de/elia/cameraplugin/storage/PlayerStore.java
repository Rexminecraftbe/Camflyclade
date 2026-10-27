package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.resume.LastFlight;
import de.elia.cameraplugin.spot.Spot;
import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Keeps the {@link PlayerRecord} of every player in a file of its own,
 * {@code playerdata/stats/<uuid>.yml} in the folder of the plugin, next to
 * the folder {@code crash} of the crash protection.
 *
 * <p>A record is read the first time it is needed and stays in memory while
 * its player is online. It is written when camera mode ends and when he
 * leaves - only then does anything in it change, the battery charging in
 * between is worked out from the time, not counted. Like the files of the
 * crash protection it goes under another name first and is then moved into
 * place, so a crash in the middle of writing leaves no half a file.</p>
 */
public final class PlayerStore {

    /** The folder of the files, inside the folder of the plugin. */
    private static final String FOLDER = "stats";

    /** The folder of everything the plugin keeps per player. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** Which layout a file has, for a later version that changes it. */
    private static final int FORMAT = 1;

    /** What stands at the top of every file, for whoever finds one and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: statistics, battery charge, cooldown, spots of /cam spot, own settings and the end of the last",
            "flight (/cam resume) of this player.",
            "Deleting it starts the player from nothing: no statistics, a full battery, no cooldown, no spots, the",
            "settings of the server.");

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final Map<UUID, PlayerRecord> loaded = new HashMap<>();

    public PlayerStore(JavaPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    private Path file(UUID playerId) {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FOLDER).resolve(playerId + ".yml");
    }

    /** The record of a player, read from his file the first time and kept from then on. */
    public PlayerRecord get(UUID playerId) {
        return loaded.computeIfAbsent(playerId, this::read);
    }

    /**
     * The record of a player who need not be online, for a look at it. Read
     * without being kept when it is not in memory already, so that looking up
     * somebody who left long ago does not hold on to his record.
     */
    public PlayerRecord peek(UUID playerId) {
        PlayerRecord record = loaded.get(playerId);
        return record != null ? record : read(playerId);
    }

    /**
     * The record of a player for a look at it, whether he is online or not:
     * kept from then on while he is, like {@link #get(UUID)}, so that a look
     * once a second does not read his file every time; read without being
     * kept while he is not, like {@link #peek(UUID)}.
     */
    public PlayerRecord look(UUID playerId) {
        return Bukkit.getPlayer(playerId) != null ? get(playerId) : peek(playerId);
    }

    /**
     * Changes the record of a player who need not be online, and writes it
     * straight away: the one in memory while he is online, else the one in
     * his file, read for it and let go of again.
     *
     * @param change what to change, saying whether it changed anything
     * @return what the change said
     */
    public boolean change(UUID playerId, Predicate<PlayerRecord> change) {
        PlayerRecord record = loaded.get(playerId);
        if (record != null) {
            boolean changed = change.test(record);
            save(playerId);
            return changed;
        }
        record = read(playerId);
        boolean changed = change.test(record);
        if (changed) {
            write(playerId, record);
        }
        return changed;
    }

    /** A player and how many spots his file has, see {@link #countSpots()}. */
    public record SpotCount(String name, int spots) {
    }

    /**
     * Who has spots in his file, and how many, by the id of the player - for
     * the admin page of the spots. Reads every file and touches nothing else,
     * so it may run in the background; a file that cannot be read is skipped
     * and left where it is.
     */
    public Map<UUID, SpotCount> countSpots() {
        Map<UUID, SpotCount> found = new HashMap<>();
        Path folder = plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FOLDER);
        if (!Files.isDirectory(folder)) {
            return found;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(path -> path.getFileName().toString().endsWith(".yml")).toList();
        } catch (IOException ex) {
            log.log(Level.WARNING, "The files of the players could not be listed for the spots: " + ex);
            return found;
        }
        for (Path path : files) {
            String fileName = path.getFileName().toString();
            UUID id;
            try {
                id = UUID.fromString(fileName.substring(0, fileName.length() - ".yml".length()));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            YamlConfiguration file = new YamlConfiguration();
            try {
                file.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException | InvalidConfigurationException ex) {
                continue;
            }
            ConfigurationSection spots = file.getConfigurationSection("spots");
            int count = spots == null ? 0 : spots.getKeys(false).size();
            if (count > 0) {
                found.put(id, new SpotCount(file.getString("name", id.toString()), count));
            }
        }
        return found;
    }

    /** Writes the record of a player, if anything in it has changed. */
    public void save(UUID playerId) {
        PlayerRecord record = loaded.get(playerId);
        if (record != null && record.isDirty()) {
            write(playerId, record);
        }
    }

    /** Writes the record of a player who leaves and lets go of it. */
    public void unload(UUID playerId) {
        save(playerId);
        loaded.remove(playerId);
    }

    /** Writes every record that has changed, when the plugin is switched off. */
    public void saveAll() {
        for (UUID playerId : new ArrayList<>(loaded.keySet())) {
            save(playerId);
        }
    }

    /**
     * Reads a record from its file. No file means a player who has not been
     * in camera mode yet, and he gets an empty record. A file that cannot be
     * read is moved aside, so that nothing in it is lost, and he starts from
     * an empty one as well.
     */
    private PlayerRecord read(UUID playerId) {
        Path path = file(playerId);
        if (!Files.exists(path)) {
            return new PlayerRecord();
        }
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException ex) {
            keepBroken(path, ex);
            return new PlayerRecord();
        }
        Double charge = file.isSet("battery.charge") ? file.getDouble("battery.charge") : null;
        Long cooldown = file.isSet("cooldown.started") ? file.getLong("cooldown.started") : null;
        PlayerRecord record = new PlayerRecord(
                file.getString("name"),
                Math.max(0L, file.getLong("stats.sessions")),
                Math.max(0L, file.getLong("stats.total-seconds")),
                Math.max(0L, file.getLong("stats.longest-seconds")),
                Math.max(0.0, file.getDouble("stats.distance-flown")),
                Math.max(0.0, file.getDouble("stats.farthest-from-body")),
                Math.max(0L, file.getLong("stats.last-flight")),
                charge == null ? null : Math.max(0.0, charge),
                file.getLong("battery.updated"),
                cooldown);
        readSpots(file, record);
        record.load(readNightVision(file.getString("settings.night-vision")),
                file.isSet("settings.flight-speed") ? file.getDouble("settings.flight-speed") : null,
                file.isSet("settings.time") ? file.getLong("settings.time") : null,
                readWeather(file.getString("settings.weather")),
                readLastFlight(file.getConfigurationSection("last-flight")));
        return record;
    }

    /** What {@code settings.night-vision} says: on, off or dark, {@code null} for anything else. */
    private static NightVisionMode readNightVision(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "on" -> NightVisionMode.ALWAYS;
            case "off" -> NightVisionMode.OFF;
            case "dark" -> NightVisionMode.DARK;
            default -> null;
        };
    }

    /** How {@code settings.night-vision} writes a mode. */
    private static String nightVisionWord(NightVisionMode mode) {
        return switch (mode) {
            case ALWAYS -> "on";
            case OFF -> "off";
            case DARK -> "dark";
        };
    }

    /** What {@code settings.weather} says: clear or rain, {@code null} for anything else. */
    private static WeatherType readWeather(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "clear" -> WeatherType.CLEAR;
            case "rain" -> WeatherType.DOWNFALL;
            default -> null;
        };
    }

    /** The end of the last flight, or {@code null} for none or one without a world. */
    private static LastFlight readLastFlight(ConfigurationSection section) {
        String world = section == null ? null : section.getString("world");
        if (world == null || world.isEmpty()) {
            return null;
        }
        String owner = section.getString("visit-owner");
        UUID visitOwner = null;
        if (owner != null) {
            try {
                visitOwner = UUID.fromString(owner);
            } catch (IllegalArgumentException ex) {
                // A visit nobody can be found for is no visit.
            }
        }
        return new LastFlight(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"),
                visitOwner, section.getString("visit-spot"));
    }

    /**
     * Reads the spots of a file. Each goes by the name written inside it and
     * not by the key above it, which YAML may have turned into something else
     * - a name like {@code 1e3} reads back as a number. One without a name or
     * a world is left out.
     */
    private static void readSpots(YamlConfiguration file, PlayerRecord record) {
        ConfigurationSection section = file.getConfigurationSection("spots");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection spot = section.getConfigurationSection(key);
            String name = spot == null ? null : spot.getString("name");
            String world = spot == null ? null : spot.getString("world");
            if (name == null || name.isEmpty() || world == null) {
                continue;
            }
            record.loadSpot(new Spot(name, world, spot.getDouble("x"), spot.getDouble("y"), spot.getDouble("z"),
                    (float) spot.getDouble("yaw"), (float) spot.getDouble("pitch"), spot.getLong("created")));
        }
    }

    private void write(UUID playerId, PlayerRecord record) {
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        file.set("format", FORMAT);
        file.set("name", record.getName());
        file.set("stats.sessions", record.getSessions());
        file.set("stats.total-seconds", record.getTotalSeconds());
        file.set("stats.longest-seconds", record.getLongestSeconds());
        file.set("stats.distance-flown", Math.round(record.getDistanceFlown() * 10.0) / 10.0);
        file.set("stats.farthest-from-body", Math.round(record.getFarthestFromBody() * 10.0) / 10.0);
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
            String path = "spots." + spot.key();
            file.set(path + ".name", spot.name());
            file.set(path + ".world", spot.world());
            file.set(path + ".x", spot.x());
            file.set(path + ".y", spot.y());
            file.set(path + ".z", spot.z());
            file.set(path + ".yaw", (double) spot.yaw());
            file.set(path + ".pitch", (double) spot.pitch());
            if (spot.created() > 0L) {
                file.set(path + ".created", spot.created());
            }
        }
        if (record.getNightVision() != null) {
            file.set("settings.night-vision", nightVisionWord(record.getNightVision()));
        }
        if (record.getFlightSpeed() != null) {
            file.set("settings.flight-speed", record.getFlightSpeed());
        }
        if (record.getCamTime() != null) {
            file.set("settings.time", record.getCamTime());
        }
        if (record.getCamWeather() != null) {
            file.set("settings.weather", record.getCamWeather() == WeatherType.CLEAR ? "clear" : "rain");
        }
        LastFlight flight = record.getLastFlight();
        if (flight != null) {
            file.set("last-flight.world", flight.world());
            file.set("last-flight.x", flight.x());
            file.set("last-flight.y", flight.y());
            file.set("last-flight.z", flight.z());
            file.set("last-flight.yaw", (double) flight.yaw());
            file.set("last-flight.pitch", (double) flight.pitch());
            if (flight.wasVisit()) {
                file.set("last-flight.visit-owner", flight.visitOwner().toString());
                file.set("last-flight.visit-spot", flight.visitSpot());
            }
        }
        Path target = file(playerId);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, file.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            record.markSaved();
        } catch (IOException ex) {
            log.log(Level.WARNING, "The statistics, battery, cooldown, spots and settings of " + record.getName()
                    + " could not be written: " + ex);
        }
    }

    /** Moves a file that could not be read out of the way, under a name that says so. */
    private void keepBroken(Path path, Exception reason) {
        Path aside = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            aside = path;
        }
        log.log(Level.WARNING, "A file with statistics, battery, cooldown, spots and settings could not be read and"
                + " was put aside, the player starts from nothing: " + aside + " (" + reason + ")");
    }
}
