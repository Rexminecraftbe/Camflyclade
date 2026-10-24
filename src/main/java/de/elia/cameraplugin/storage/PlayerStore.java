package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Bukkit;
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
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

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
            "CamFly: statistics, battery charge and cooldown of this player.",
            "Deleting it starts the player from nothing: no statistics, a full battery, no cooldown.");

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
        return new PlayerRecord(
                file.getString("name"),
                Math.max(0L, file.getLong("stats.sessions")),
                Math.max(0L, file.getLong("stats.total-seconds")),
                Math.max(0L, file.getLong("stats.longest-seconds")),
                Math.max(0.0, file.getDouble("stats.distance-flown")),
                Math.max(0.0, file.getDouble("stats.farthest-from-body")),
                charge == null ? null : Math.max(0.0, charge),
                file.getLong("battery.updated"),
                cooldown);
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
        if (record.getBatteryCharge() != null) {
            file.set("battery.charge", record.getBatteryCharge());
            file.set("battery.updated", record.getBatteryUpdated());
        }
        if (record.getCooldownStarted() != null) {
            file.set("cooldown.started", record.getCooldownStarted());
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
            log.log(Level.WARNING, "The statistics, battery and cooldown of " + record.getName()
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
        log.log(Level.WARNING, "A file with statistics, battery and cooldown could not be read and was put aside,"
                + " the player starts from nothing: " + aside + " (" + reason + ")");
    }
}
