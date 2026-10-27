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
 * What single players, or everybody at once, may not do with spots, set on
 * the admin page of the spots, see {@link SpotModeration}: for each kind of
 * spots - his own, the public ones, those shared by name - no new ones, or
 * none at all, not even to look at - for good or for a while, with a reason.
 * Kept in
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
            "CamFly: what single players may not do with spots, set on the admin page of the spots. For each",
            "kind - private: his own, public: the public ones, shared: those shared by name - no-new: he may",
            "make no new ones, no-look: nor look at them; left out: nothing keeps him. everybody: the same for",
            "every player but those with camplugin.spotadmin.bypass.",
            "until: when it ends (milliseconds since 1970), 0 = for good. Changes made here by hand count after",
            "/cam reload.");

    /** The kinds of spots a restriction goes by, with the word of the file and the texts. */
    public enum Kind {
        /**
         * His own: saving new ones; looking at them is jumping to them -
         * the public ones among them as well.
         */
        PRIVATE("private"),
        /** The public ones: making his public; looking at them is jumping to or visiting those of others. */
        PUBLIC("public"),
        /** Those shared by name: sharing his; looking at them is jumping to or visiting those shared with him. */
        SHARED("shared");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /** How far a restriction keeps him from one kind of spots, by the word of the file. */
    public enum Stage {
        /** Not at all; never written to the file. */
        FREE("free"),
        /** He may make no new ones. */
        NO_NEW("no-new"),
        /** He may make no new ones, nor look at them. */
        NO_LOOK("no-look");

        public final String key;

        Stage(String key) {
            this.key = key;
        }

        /** The next one, after {@link #NO_LOOK} {@link #FREE} again; with {@code back}, the one before. */
        public Stage next(boolean back) {
            Stage[] all = values();
            return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
        }

        static Stage of(String key) {
            for (Stage stage : values()) {
                if (stage.key.equalsIgnoreCase(key)) {
                    return stage;
                }
            }
            return FREE;
        }
    }

    /**
     * What one player may not do, and for how long.
     *
     * @param name    the name of the player, as he was called then; {@code null} for everybody
     * @param own     how far it keeps him from his own spots
     * @param open    how far it keeps him from the public ones
     * @param shared  how far it keeps him from those shared by name
     * @param until   when it ends, as {@link System#currentTimeMillis()} counts, 0 for good
     * @param reason  why, or an empty text
     * @param by      who set it
     */
    public record Ban(String name, Stage own, Stage open, Stage shared, long until, String reason, String by) {

        /** How far it keeps him from this kind of spots. */
        public Stage stage(Kind kind) {
            return switch (kind) {
                case PRIVATE -> own;
                case PUBLIC -> open;
                case SHARED -> shared;
            };
        }

        /** Whether it keeps him from anything at all. */
        public boolean any() {
            return own != Stage.FREE || open != Stage.FREE || shared != Stage.FREE;
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
     * Lets everybody do everything with spots again. What keeps single
     * players stays.
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
        return new Ban(name, stage(entry, Kind.PRIVATE, "create"), stage(entry, Kind.PUBLIC, "public"),
                stage(entry, Kind.SHARED, "share"), entry.getLong("until"), entry.getString("reason", ""),
                entry.getString("by", ""));
    }

    /**
     * How far an entry keeps him from a kind of spots. An entry of the
     * version before has a switch for each thing instead - {@code create},
     * {@code public}, {@code share}: no new ones; {@code view}: no looking at
     * any spots -, and keeps him as far as it did.
     *
     * @param before the switch of the version before for new ones of this kind
     */
    private static Stage stage(ConfigurationSection entry, Kind kind, String before) {
        if (entry.getBoolean("view")) {
            return Stage.NO_LOOK;
        }
        Stage stage = Stage.of(entry.getString(kind.key));
        return stage == Stage.FREE && entry.getBoolean(before) ? Stage.NO_NEW : stage;
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
            log.log(Level.WARNING, "The restrictions of the spots could not be written: " + ex);
        }
    }

    private static void write(ConfigurationSection out, Ban ban) {
        if (ban.name() != null) {
            out.set("name", ban.name());
        }
        for (Kind kind : Kind.values()) {
            if (ban.stage(kind) != Stage.FREE) {
                out.set(kind.key, ban.stage(kind).key);
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
        log.log(Level.WARNING, "The restrictions of the spots could not be read and were put aside, nobody is"
                + " restricted until they are repaired: " + aside + " (" + reason + ")");
    }
}
