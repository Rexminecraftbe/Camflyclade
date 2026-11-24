package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.camfly2.PluginPart;
import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>The text of the file is put together on the main thread, the writing
 * itself is left to the {@link BackgroundWriter}: a slow disk holds up only
 * its thread and not the server. Until the text is on the disk, reading the
 * file gives that text, so a player who leaves and comes straight back finds
 * his record the way he left it.</p>
 *
 * <p>What the text of a file says, and how a record is read from it, is up
 * to {@link PlayerFile}.</p>
 */
public final class PlayerStore implements PluginPart {

    /** The folder of the files, inside the folder of the plugin. */
    private static final String FOLDER = "stats";

    /** The folder of everything the plugin keeps per player. */
    private static final String PLAYER_FOLDER = "playerdata";

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final BackgroundWriter writer;
    private final Map<UUID, PlayerRecord> loaded = new HashMap<>();

    public PlayerStore(JavaPlugin plugin, ConsoleLog log, BackgroundWriter writer) {
        this.plugin = plugin;
        this.log = log;
        this.writer = writer;
    }

    private Path folder() {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FOLDER);
    }

    private Path file(UUID playerId) {
        return folder().resolve(playerId + ".yml");
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
        Path folder = folder();
        // What is still on its way to the disk counts as it will be there,
        // a file that is not there at all yet included.
        Map<Path, String> pendingTexts = writer.pendingIn(folder);
        Set<Path> files = new HashSet<>(pendingTexts.keySet());
        if (Files.isDirectory(folder)) {
            try (Stream<Path> list = Files.list(folder)) {
                list.map(path -> path.toAbsolutePath().normalize()).forEach(files::add);
            } catch (IOException ex) {
                log.log(Level.WARNING, "The files of the players could not be listed for the spots: " + ex);
                return found;
            }
        }
        for (Path path : files) {
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(".yml")) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(fileName.substring(0, fileName.length() - ".yml".length()));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            YamlConfiguration file = new YamlConfiguration();
            try {
                String text = pendingTexts.get(path);
                file.loadFromString(text != null ? text : Files.readString(path, StandardCharsets.UTF_8));
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
        // A text still on its way to the disk is newer than the file.
        String text = writer.pendingText(path);
        if (text == null && !Files.exists(path)) {
            return new PlayerRecord();
        }
        try {
            return PlayerFile.read(text != null ? text : Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException ex) {
            keepBroken(path, ex);
            return new PlayerRecord();
        }
    }

    private void write(UUID playerId, PlayerRecord record) {
        String text = PlayerFile.text(record);
        // Counted as saved as soon as it is handed over: only a change from
        // now on has it written again. Should the disk turn it away, it counts
        // as changed again and goes with the next save, as before.
        record.markSaved();
        writer.write(file(playerId), text,
                "The statistics, battery, cooldown, spots and settings of " + record.getName(),
                record::markDirty);
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

    /** When the plugin is switched off: every record in memory goes to its file, see {@link #saveAll()}. */
    @Override
    public void onDisable() {
        saveAll();
    }
}
