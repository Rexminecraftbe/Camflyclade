package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.storage.StorageMode;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Every spot that is shared, {@code viewpoints.sharing}: by whom, whether it
 * is public, with whom - accepted, or offered and waiting -, who likes it,
 * and a copy of the spot itself.
 *
 * <p>The copy is kept here and not looked up in the file of its owner: a
 * player finds what is shared with him while its owner is offline, and the
 * spots of the owner are read from his file only while he is online. When he
 * moves or deletes the spot, {@link CamSpots} tells this class.</p>
 *
 * <p>Where the shares are kept follows {@code viewpoints.storage}: in one file
 * for everybody, {@code playerdata/shared-spots.yml}, written after every
 * change and read at the start and with {@code /cam reload}; or in memory
 * only, which {@code /cam reload} and a restart forget, like the spots
 * themselves.</p>
 */
public final class SharedSpots {

    /** The folder of everything the plugin keeps per player, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The file of the shares, in that folder. */
    private static final String FILE = "shared-spots.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: the spots of /cam spot that players make public or share with others (/cam spot share), by",
            "the id of their owner, with who accepted them, who has yet to and who likes them. Deleting it takes",
            "every share back; the spots themselves stay with their owners.");

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final CamSettings settings;
    /** The shares of each owner, by {@link Spot#key()}, in the order he shared them. */
    private final Map<UUID, Map<String, Share>> shares = new LinkedHashMap<>();

    public SharedSpots(JavaPlugin plugin, ConsoleLog log, CamSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.settings = settings;
    }

    private boolean inFile() {
        return settings.getViewpointsStorage() == StorageMode.FILE;
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FILE);
    }

    /** The share of one of his spots, or {@code null} while it is shared with nobody. */
    public Share get(UUID owner, String key) {
        Map<String, Share> own = shares.get(owner);
        return own == null ? null : own.get(key);
    }

    /**
     * What this player may use of what others share: the public spots and
     * those he accepted, by the name of the owner and then of the spot.
     */
    public List<Share> sharedWith(UUID viewer) {
        return collect(share -> share.isFor(viewer));
    }

    /** What others offered him and he has not accepted yet, by owner and then spot. */
    public List<Share> offeredTo(UUID viewer) {
        return collect(share -> share.isOfferedTo(viewer));
    }

    /** What others share with him by name and he accepted, not the public spots, by owner and then spot. */
    public List<Share> acceptedBy(UUID viewer) {
        return collect(share -> share.isAcceptedBy(viewer));
    }

    /** What one player shares, public or by name, in the order he shared it. */
    public List<Share> of(UUID owner) {
        Map<String, Share> own = shares.get(owner);
        return own == null ? List.of() : new ArrayList<>(own.values());
    }

    /** Every public spot, his own among them, by owner and then spot. */
    public List<Share> publicSpots() {
        return collect(Share::isEveryone);
    }

    private List<Share> collect(Predicate<Share> which) {
        List<Share> found = new ArrayList<>();
        for (Map<String, Share> own : shares.values()) {
            for (Share share : own.values()) {
                if (which.test(share)) {
                    found.add(share);
                }
            }
        }
        found.sort(Comparator.comparing((Share share) -> share.getOwnerName().toLowerCase(Locale.ROOT))
                .thenComparing(share -> share.getSpot().key()));
        return found;
    }

    /**
     * A spot shared with this player, by {@code Owner:spot}, capitals not
     * counting, or {@code null} when there is none of that name.
     */
    public Share find(UUID viewer, String label) {
        return find(sharedWith(viewer), label);
    }

    /** A spot offered to this player and not accepted yet, by {@code Owner:spot}, or {@code null}. */
    public Share findOffer(UUID viewer, String label) {
        return find(offeredTo(viewer), label);
    }

    /** A public spot, by {@code Owner:spot}, or {@code null}. */
    public Share findPublic(String label) {
        return find(publicSpots(), label);
    }

    private static Share find(List<Share> among, String label) {
        int colon = label.indexOf(':');
        if (colon <= 0 || colon == label.length() - 1) {
            return null;
        }
        String owner = label.substring(0, colon);
        String key = Spot.key(label.substring(colon + 1));
        for (Share share : among) {
            if (share.getOwnerName().equalsIgnoreCase(owner) && share.getSpot().key().equals(key)) {
                return share;
            }
        }
        return null;
    }

    /**
     * Makes one of his spots public, or offers it to a player, who has it
     * once he accepts it, see {@link #accept}.
     *
     * @param with     whom, or {@code null} for everybody
     * @param withName his name
     * @return whether it is new: not public before, or neither offered to
     *         him nor accepted by him
     */
    public boolean share(UUID owner, String ownerName, Spot spot, UUID with, String withName) {
        Share share = shares.computeIfAbsent(owner, id -> new LinkedHashMap<>())
                .computeIfAbsent(spot.key(), key -> new Share(owner, ownerName, spot));
        share.setOwnerName(ownerName);
        share.setSpot(spot);
        boolean added;
        if (with == null) {
            added = !share.isEveryone();
            share.setEveryone(true);
        } else if (share.isAcceptedBy(with)) {
            added = false;
        } else {
            added = !share.isOfferedTo(with);
            share.addPending(with, withName);
        }
        save();
        return added;
    }

    /** He accepts a spot offered to him: from now on it is shared with him. */
    public void accept(Share share, UUID viewer, String viewerName) {
        share.addPlayer(viewer, viewerName);
        save();
    }

    /**
     * He turns down a spot offered to him, or no longer wants one he accepted.
     *
     * @return whether it was offered to him or his
     */
    public boolean decline(Share share, UUID viewer) {
        boolean removed = share.removePlayer(viewer);
        if (share.isEmpty()) {
            drop(share.getOwner(), share.getSpot().key());
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * He likes a public spot, or no longer does.
     *
     * @return whether he likes it now
     */
    public boolean toggleLike(Share share, UUID viewer) {
        boolean likes = !share.removeLike(viewer);
        if (likes) {
            share.addLike(viewer);
        } else if (share.isEmpty()) {
            drop(share.getOwner(), share.getSpot().key());
        }
        save();
        return likes;
    }

    /**
     * Takes a share back, from a player or from everybody. Shared with
     * nobody any more, the share is gone.
     *
     * @param with whom - accepted or still offered -, or {@code null} for
     *             everybody: the players it is shared with by name keep it
     * @return whether it was shared with him
     */
    public boolean unshare(UUID owner, String key, UUID with) {
        Share share = get(owner, key);
        if (share == null) {
            return false;
        }
        boolean removed;
        if (with == null) {
            removed = share.isEveryone();
            share.setEveryone(false);
        } else {
            removed = share.removePlayer(with);
        }
        if (share.isEmpty()) {
            drop(owner, key);
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Takes a spot down, from the admin page of the spots: no longer public,
     * shared with nobody and offered to nobody. Its likes stay, as when its
     * owner makes it private himself.
     *
     * @return whether it was shared at all
     */
    public boolean takeBack(UUID owner, String key) {
        Share share = get(owner, key);
        if (share == null || !share.isEveryone() && share.getPlayers().isEmpty() && share.getPending().isEmpty()) {
            return false;
        }
        share.setEveryone(false);
        share.clearPlayers();
        if (share.isEmpty()) {
            drop(owner, key);
        }
        save();
        return true;
    }

    /** Follows a spot its owner has moved, or given a name of other capitals. */
    public void moved(UUID owner, String ownerName, Spot spot) {
        Share share = get(owner, spot.key());
        if (share == null) {
            return;
        }
        share.setOwnerName(ownerName);
        share.setSpot(spot);
        save();
    }

    /** A spot its owner has deleted is shared with nobody any more. */
    public void deleted(UUID owner, String key) {
        if (get(owner, key) != null) {
            drop(owner, key);
            save();
        }
    }

    private void drop(UUID owner, String key) {
        Map<String, Share> own = shares.get(owner);
        own.remove(key);
        if (own.isEmpty()) {
            shares.remove(owner);
        }
    }

    /**
     * At the start and after a reload: the shares of the file, or none on
     * {@code storage: memory}.
     */
    public void load() {
        shares.clear();
        if (!inFile() || !Files.exists(file())) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(Files.readString(file(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException ex) {
            keepBroken(ex);
            return;
        }
        for (String ownerKey : file.getKeys(false)) {
            UUID owner = parseId(ownerKey);
            ConfigurationSection section = file.getConfigurationSection(ownerKey);
            ConfigurationSection spots = section == null ? null : section.getConfigurationSection("spots");
            if (owner == null || spots == null) {
                continue;
            }
            String ownerName = section.getString("name", ownerKey);
            for (String key : spots.getKeys(false)) {
                readShare(owner, ownerName, spots.getConfigurationSection(key));
            }
        }
    }

    /**
     * Reads one share. Like the spots of {@code PlayerStore}, it goes by the
     * name written inside it and not by the key above it. One without a name,
     * a world or anybody it is shared with is left out.
     */
    private void readShare(UUID owner, String ownerName, ConfigurationSection entry) {
        String name = entry == null ? null : entry.getString("name");
        String world = entry == null ? null : entry.getString("world");
        if (name == null || name.isEmpty() || world == null) {
            return;
        }
        Spot spot = new Spot(name, world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                (float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"), entry.getLong("created"));
        Share share = new Share(owner, ownerName, spot);
        share.setEveryone(entry.getBoolean("everyone"));
        ConfigurationSection pending = entry.getConfigurationSection("pending");
        if (pending != null) {
            for (String id : pending.getKeys(false)) {
                UUID player = parseId(id);
                if (player != null) {
                    share.addPending(player, pending.getString(id, id));
                }
            }
        }
        // After the offers: one that is both, from a file written by hand,
        // counts as accepted.
        ConfigurationSection players = entry.getConfigurationSection("players");
        if (players != null) {
            for (String id : players.getKeys(false)) {
                UUID player = parseId(id);
                if (player != null) {
                    share.addPlayer(player, players.getString(id, id));
                }
            }
        }
        for (String id : entry.getStringList("likes")) {
            UUID player = parseId(id);
            if (player != null) {
                share.addLike(player);
            }
        }
        if (!share.isEmpty()) {
            shares.computeIfAbsent(owner, key -> new LinkedHashMap<>()).put(spot.key(), share);
        }
    }

    private static UUID parseId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Writes every share, on {@code storage: file}. Like the files of the
     * players it goes under another name first and is then moved into place.
     */
    private void save() {
        if (!inFile()) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        for (Map.Entry<UUID, Map<String, Share>> own : shares.entrySet()) {
            String base = own.getKey().toString();
            for (Share share : own.getValue().values()) {
                file.set(base + ".name", share.getOwnerName());
                Spot spot = share.getSpot();
                String path = base + ".spots." + spot.key();
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
                file.set(path + ".everyone", share.isEveryone());
                for (Map.Entry<UUID, String> player : share.getPlayers().entrySet()) {
                    file.set(path + ".players." + player.getKey(), player.getValue());
                }
                for (Map.Entry<UUID, String> player : share.getPending().entrySet()) {
                    file.set(path + ".pending." + player.getKey(), player.getValue());
                }
                if (share.likes() > 0) {
                    List<String> likes = new ArrayList<>();
                    for (UUID player : share.likers()) {
                        likes.add(player.toString());
                    }
                    file.set(path + ".likes", likes);
                }
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
            log.log(Level.WARNING, "The shared spots could not be written: " + ex);
        }
    }

    /** Moves a file that could not be read out of the way, under a name that says so. */
    private void keepBroken(Exception reason) {
        Path path = file();
        Path aside = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            aside = path;
        }
        log.log(Level.WARNING, "The file of the shared spots could not be read and was put aside, nothing is shared: "
                + aside + " (" + reason + ")");
    }
}
