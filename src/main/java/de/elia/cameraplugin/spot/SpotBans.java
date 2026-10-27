package de.elia.cameraplugin.spot;

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
 * What single players may not do with spots, set on the admin page of the
 * spots, see {@link SpotModeration}: save new ones, make them public, share
 * them with others, look at spots at all - for good or for a while, with a
 * reason. Kept in
 * {@code playerdata/spot-bans.yml}, whatever {@code viewpoints.storage}
 * says: a restriction outlasts {@code /cam reload} and a restart, like a
 * ban of camera mode.
 *
 * <p>One for a while carries the moment it ends. Ended, it is no restriction
 * any more and is left out the next time the file is written; nothing has to
 * run for it to end.</p>
 */
public final class SpotBans {

    /** The folder of everything the plugin keeps about players, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The file, inside {@link #PLAYER_FOLDER}. */
    private static final String FILE = "spot-bans.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: what single players may not do with spots - create: save new ones, public: make them",
            "public, share: share them with others, view: jump to spots or visit them -, set on the admin page",
            "of the spots.",
            "until: when it ends (milliseconds since 1970), 0 = for good. Changes made here by hand count after",
            "/cam reload.");

    /**
     * What one player may not do, and for how long.
     *
     * @param name    the name of the player, as he was called then
     * @param create  whether he may not save new spots
     * @param publish whether he may not make spots public
     * @param share   whether he may not share spots with others
     * @param view    whether he may not look at spots: jump to them, his own as well, or visit them
     * @param until   when it ends, as {@link System#currentTimeMillis()} counts, 0 for good
     * @param reason  why, or an empty text
     * @param by      who set it
     */
    public record Ban(String name, boolean create, boolean publish, boolean share, boolean view, long until,
                      String reason, String by) {

        /** Whether it still holds at {@code now}, and keeps him from anything at all. */
        boolean holds(long now) {
            return (create || publish || share || view) && (until <= 0L || until > now);
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

    public SpotBans(JavaPlugin plugin, ConsoleLog log) {
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

    /**
     * Lets a player do everything with spots again.
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
     * Reads the file, at the start and after {@code /cam reload}. No file
     * means nobody is kept from anything. A file that cannot be read is moved
     * aside, so that nothing in it is lost.
     */
    public void load() {
        players.clear();
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
            players.put(id, new Ban(entry.getString("name"), entry.getBoolean("create"),
                    entry.getBoolean("public"), entry.getBoolean("share"), entry.getBoolean("view"),
                    entry.getLong("until"), entry.getString("reason", ""), entry.getString("by", "")));
        }
    }

    /** Writes the file with everything that still holds. */
    private void save() {
        long now = System.currentTimeMillis();
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        ConfigurationSection section = file.createSection("players");
        for (Map.Entry<UUID, Ban> entry : players.entrySet()) {
            Ban ban = entry.getValue();
            if (!ban.holds(now)) {
                continue;
            }
            ConfigurationSection out = section.createSection(entry.getKey().toString());
            if (ban.name() != null) {
                out.set("name", ban.name());
            }
            out.set("create", ban.create());
            out.set("public", ban.publish());
            out.set("share", ban.share());
            out.set("view", ban.view());
            out.set("until", ban.until());
            out.set("reason", ban.reason());
            out.set("by", ban.by());
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
            log.log(Level.WARNING, "The restrictions of the spots could not be written: " + ex);
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
        log.log(Level.WARNING, "The restrictions of the spots could not be read and were put aside, nobody is"
                + " restricted until they are repaired: " + aside + " (" + reason + ")");
    }
}
