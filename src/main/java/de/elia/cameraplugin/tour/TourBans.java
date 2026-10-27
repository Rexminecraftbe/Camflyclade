package de.elia.cameraplugin.tour;

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
 * Who may not lead tours or take part in them - single players, or
 * everybody at once -, set on the admin page of the tours, see
 * {@link TourModeration}: for good or for a while, with a reason. Kept in
 * {@code playerdata/tour-bans.yml}: a restriction outlasts
 * {@code /cam reload} and a restart, like a ban of camera mode.
 *
 * <p>One for a while carries the moment it ends. Ended, it is no restriction
 * any more and is left out the next time the file is written; nothing has to
 * run for it to end.</p>
 */
public final class TourBans {

    /** The folder of everything the plugin keeps about players, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The file, inside {@link #PLAYER_FOLDER}. */
    private static final String FILE = "tour-bans.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: who may not lead tours or take part in them, set on the admin page of the tours.",
            "lead: true - he may not show others round; join: true - he may not come along on a tour.",
            "everybody: the same for every player but those with camplugin.touradmin.bypass.",
            "until: when it ends (milliseconds since 1970), 0 = for good. Changes made here by hand count after",
            "/cam reload.");

    /** What a restriction can keep him from, by the word of the file and the texts. */
    public enum What {
        /** Showing others round: inviting players to his tour. */
        LEAD("lead"),
        /** Coming along on a tour: accepting an invitation. */
        JOIN("join");

        public final String key;

        What(String key) {
            this.key = key;
        }
    }

    /**
     * What one player, or everybody, may not do, and for how long.
     *
     * @param name   the name of the player, as he was called then; {@code null} for everybody
     * @param lead   whether it keeps him from leading tours
     * @param join   whether it keeps him from taking part in them
     * @param until  when it ends, as {@link System#currentTimeMillis()} counts, 0 for good
     * @param reason why, or an empty text
     * @param by     who set it
     */
    public record Ban(String name, boolean lead, boolean join, long until, String reason, String by) {

        /** Whether it keeps him from this. */
        public boolean keeps(What what) {
            return what == What.LEAD ? lead : join;
        }

        /** Whether it keeps him from anything at all. */
        public boolean any() {
            return lead || join;
        }

        /** Whether it still holds at {@code now}, and keeps him from anything at all. */
        boolean holds(long now) {
            return any() && (until <= 0L || until > now);
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
    /** The restricted players by their id, in the order they were restricted. */
    private final Map<UUID, Ban> players = new LinkedHashMap<>();
    /** The restriction of everybody, or {@code null} while there is none. */
    private Ban everybody;

    public TourBans(JavaPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FILE);
    }

    /** What a player may not do, or {@code null} while nothing keeps him. */
    public Ban get(UUID playerId) {
        Ban ban = players.get(playerId);
        return ban != null && ban.holds(System.currentTimeMillis()) ? ban : null;
    }

    /** What everybody may not do, or {@code null} while nothing keeps everybody. */
    public Ban everybody() {
        return everybody != null && everybody.holds(System.currentTimeMillis()) ? everybody : null;
    }

    /** Every player kept from something right now, by his id. */
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

    /** Keeps a player from what the ban says, in place of what kept him before. */
    public void ban(UUID playerId, Ban ban) {
        players.remove(playerId);
        players.put(playerId, ban);
        save();
    }

    /** Keeps everybody from what the ban says, in place of what kept everybody before. */
    public void banEverybody(Ban ban) {
        everybody = ban;
        save();
    }

    /**
     * Lets a player lead tours and take part in them again.
     *
     * @return whether anything kept him
     */
    public boolean unban(UUID playerId) {
        boolean was = get(playerId) != null;
        players.remove(playerId);
        save();
        return was;
    }

    /**
     * Lets everybody lead tours and take part in them again. What keeps
     * single players stays.
     *
     * @return whether anything kept everybody
     */
    public boolean unbanEverybody() {
        boolean was = everybody() != null;
        everybody = null;
        save();
        return was;
    }

    /**
     * Reads the file, at the start and after {@code /cam reload}. No file
     * means nobody is kept from anything. A file that cannot be read is moved
     * aside, so that nothing in it is lost.
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
        ConfigurationSection all = file.getConfigurationSection("everybody");
        everybody = all == null ? null : read(all, null);
        ConfigurationSection section = file.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                id = null;
            }
            if (id == null || entry == null) {
                log.log(Level.WARNING, "An entry of " + FILE + " is no restriction and is left out: players." + key);
                continue;
            }
            players.put(id, read(entry, entry.getString("name")));
        }
    }

    private static Ban read(ConfigurationSection entry, String name) {
        return new Ban(name, entry.getBoolean(What.LEAD.key), entry.getBoolean(What.JOIN.key),
                entry.getLong("until"), entry.getString("reason", ""), entry.getString("by", ""));
    }

    /** Writes the file with everything that still holds. */
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
            log.log(Level.WARNING, "The restrictions of the tours could not be written: " + ex);
        }
    }

    private static void write(ConfigurationSection out, Ban ban) {
        if (ban.name() != null) {
            out.set("name", ban.name());
        }
        for (What what : What.values()) {
            if (ban.keeps(what)) {
                out.set(what.key, true);
            }
        }
        out.set("until", ban.until());
        out.set("reason", ban.reason());
        out.set("by", ban.by());
    }

    /** Moves a file that could not be read out of the way, under a name that says so. */
    private void keepBroken(Path path, Exception reason) {
        Path aside = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            aside = path;
        }
        log.log(Level.WARNING, "The restrictions of the tours could not be read and were put aside, nobody is"
                + " restricted until they are repaired: " + aside + " (" + reason + ")");
    }
}
