package de.elia.cameraplugin.ban;

import de.elia.cameraplugin.log.ConsoleLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Who may not use camera mode, {@code /cam ban}: single players, and
 * everybody at once. Kept in {@code bans.yml} in the folder of the plugin, so
 * that a ban outlasts {@code /cam reload} and a restart.
 *
 * <p>A ban for a while carries the moment it ends. One that has ended is no
 * ban any more from then on and is left out the next time the file is
 * written; nothing has to run for it to end.</p>
 *
 * <p>Like the files of the crash protection, the file goes under another
 * name first and is then moved into place, so a crash in the middle of
 * writing leaves no half a file.</p>
 */
public final class BanList {

    /** The file, inside the folder of the plugin. */
    private static final String FILE = "bans.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: who may not use cam mode, see /cam ban, /cam unban and /cam banlist.",
            "until: when the ban ends (milliseconds since 1970), 0 = for good.",
            "Changes made here by hand count after /cam reload.");

    /**
     * One ban.
     *
     * @param name   the name of the player, as he was called when he was banned;
     *               {@code null} for the ban of everybody
     * @param until  when it ends, as {@link System#currentTimeMillis()} counts,
     *               0 for good
     * @param reason why, or an empty text
     * @param by     who banned him
     */
    public record Ban(String name, long until, String reason, String by) {

        /** Whether the ban still holds at {@code now}. */
        boolean holds(long now) {
            return until <= 0L || until > now;
        }

        /** Whether it holds for good, rather than for a while. */
        public boolean forGood() {
            return until <= 0L;
        }

        /** How long it still holds at {@code now}, in whole seconds rounded up. */
        public long secondsLeft(long now) {
            return Math.max(0L, (until - now + 999L) / 1000L);
        }
    }

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    /** The banned players by their id, in the order they were banned. */
    private final Map<UUID, Ban> players = new LinkedHashMap<>();
    /** The ban of everybody, or {@code null} while there is none. */
    private Ban everybody;

    public BanList(JavaPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(FILE);
    }

    /** The ban of a player, or {@code null} while he is not banned. */
    public Ban get(UUID playerId) {
        Ban ban = players.get(playerId);
        return ban != null && ban.holds(System.currentTimeMillis()) ? ban : null;
    }

    /** The ban of everybody, or {@code null} while there is none. */
    public Ban everybody() {
        return everybody != null && everybody.holds(System.currentTimeMillis()) ? everybody : null;
    }

    /** Every player who is banned right now, by his id, in the order they were banned. */
    public Map<UUID, Ban> players() {
        long now = System.currentTimeMillis();
        Map<UUID, Ban> holding = new LinkedHashMap<>();
        for (Map.Entry<UUID, Ban> entry : players.entrySet()) {
            if (entry.getValue().holds(now)) {
                holding.put(entry.getKey(), entry.getValue());
            }
        }
        return holding;
    }

    /** Bans a player, in place of a ban he may have already. */
    public void ban(UUID playerId, Ban ban) {
        players.remove(playerId);
        players.put(playerId, ban);
        save();
    }

    /** Bans everybody, in place of such a ban there may be already. */
    public void banEverybody(Ban ban) {
        everybody = ban;
        save();
    }

    /**
     * Lifts the ban of a player.
     *
     * @return whether he was banned
     */
    public boolean unban(UUID playerId) {
        boolean was = get(playerId) != null;
        players.remove(playerId);
        save();
        return was;
    }

    /**
     * Lifts the ban of everybody. The bans of single players stay.
     *
     * @return whether there was one
     */
    public boolean unbanEverybody() {
        boolean was = everybody() != null;
        everybody = null;
        save();
        return was;
    }

    /**
     * Reads the file, at the start and after {@code /cam reload}. No file
     * means nobody is banned. A file that cannot be read is moved aside, so
     * that nothing in it is lost, and nobody is banned until it is repaired.
     */
    public void load() {
        players.clear();
        everybody = null;
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException ex) {
            keepBroken(path, ex);
            return;
        }
        everybody = read(file.getConfigurationSection("everybody"));
        ConfigurationSection section = file.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Ban ban = read(section.getConfigurationSection(key));
            UUID id = parseId(key);
            if (id == null || ban == null) {
                log.log(Level.WARNING, "An entry of " + FILE + " is no ban and is left out: players." + key);
                continue;
            }
            players.put(id, ban);
        }
    }

    private static Ban read(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        return new Ban(section.getString("name"), section.getLong("until"),
                section.getString("reason", ""), section.getString("by", ""));
    }

    private static UUID parseId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Writes the file with every ban that still holds. */
    private void save() {
        long now = System.currentTimeMillis();
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        if (everybody != null && everybody.holds(now)) {
            write(file.createSection("everybody"), everybody);
        }
        ConfigurationSection section = file.createSection("players");
        for (Map.Entry<UUID, Ban> entry : players.entrySet()) {
            if (entry.getValue().holds(now)) {
                write(section.createSection(entry.getKey().toString()), entry.getValue());
            }
        }
        Path target = file();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, file.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "The bans of cam mode could not be written: " + ex);
        }
    }

    private static void write(ConfigurationSection section, Ban ban) {
        if (ban.name() != null) {
            section.set("name", ban.name());
        }
        section.set("until", ban.until());
        section.set("reason", ban.reason());
        section.set("by", ban.by());
    }

    /** Moves a file that could not be read out of the way, under a name that says so. */
    private void keepBroken(Path path, Exception reason) {
        Path aside = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            aside = path;
        }
        log.log(Level.WARNING, "The bans of cam mode could not be read and were put aside, nobody is banned"
                + " until they are repaired: " + aside + " (" + reason + ")");
    }
}
