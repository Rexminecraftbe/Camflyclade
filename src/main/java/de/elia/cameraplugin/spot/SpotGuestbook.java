package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The guestbook of the spots, {@code viewpoints.guestbook}: the owner of a
 * spot hears who looked at it - jumped there or visited it - and who liked
 * it. Only others count, and only with the views {@link SpotViews} counts: the
 * same player at the same spot is heard of once in
 * {@code viewpoints.views-cooldown-minutes} at most.
 *
 * <p>An owner who is online hears it straight away, with the name of whoever
 * it was. One who is offline finds it when he comes back: how many views and
 * likes each of his spots got since he was last here, see
 * {@link #onJoin}. A like taken back before then does not count.</p>
 *
 * <p>Each owner switches it off and on again for himself, with the bell on the
 * pages of the spots in the menu. Switched off, nothing is kept for him
 * either: switched on again, it starts from nothing.</p>
 *
 * <p>Where it is kept follows {@code viewpoints.storage}, like
 * {@link SpotViews}: in one file for everybody,
 * {@code playerdata/spot-guestbook.yml}, written after every change; or in
 * memory only, which {@code /cam reload} and a restart forget.</p>
 */
public final class SpotGuestbook implements Listener {

    /** The folder of everything the plugin keeps per player, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The file of the guestbook, in that folder. */
    private static final String FILE = "spot-guestbook.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: the guestbook of /cam spot - who switched it off, and what happened to the spots of each owner",
            "while he was offline, by his id and the name of the spot. Deleting it forgets that news.");

    /**
     * How long after he joins an owner hears what happened while he was
     * offline, in ticks: after the greetings of the server, not lost among
     * them.
     */
    private static final long JOIN_DELAY = 40L;

    private final CameraPlugin plugin;
    private final ConsoleLog log;
    private final CamSettings settings;
    private final Messages messages;
    /** The owners who switched the guestbook off. */
    private final Set<UUID> off = new HashSet<>();
    /** What happened to the spots of each owner while he was offline, by {@link Spot#key()}. */
    private final Map<UUID, Map<String, News>> news = new LinkedHashMap<>();

    /** What happened to one spot while its owner was offline. */
    private static final class News {
        long views;
        /** Who liked it since, and still does. */
        final Set<UUID> likes = new LinkedHashSet<>();

        boolean isEmpty() {
            return views == 0L && likes.isEmpty();
        }
    }

    public SpotGuestbook(CameraPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    private boolean inFile() {
        return settings.getViewpointsStorage() == StorageMode.FILE;
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FILE);
    }

    /** Whether the owner hears about his spots: the guestbook is there, and he did not switch it off. */
    public boolean isOn(UUID owner) {
        return settings.isGuestbookEnabled() && !off.contains(owner);
    }

    /** Switches it off for him, or on again, from the bell in the menu. He is told how it stands now. */
    public void toggle(Player owner) {
        if (!settings.isGuestbookEnabled()) {
            return;
        }
        UUID id = owner.getUniqueId();
        if (off.remove(id)) {
            messages.sendConfiguredMessage(owner, "spot-guestbook-on");
        } else {
            off.add(id);
            news.remove(id);
            messages.sendConfiguredMessage(owner, "spot-guestbook-off");
        }
        save();
    }

    /**
     * A player looked at a spot of the owner, and it counted as a view, see
     * {@link SpotViews#count}. The owner himself is no guest.
     */
    public void viewed(UUID owner, Spot spot, Player viewer) {
        if (viewer.getUniqueId().equals(owner) || !isOn(owner)) {
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            messages.sendConfiguredMessage(online, "spot-guestbook-viewed", "{player}", viewer.getName(),
                    "{spot}", spot.name());
            return;
        }
        newsOf(owner, spot.key()).views++;
        save();
    }

    /**
     * A player liked a public spot of the owner, or no longer does. An owner
     * offline does not hear of a like taken back before he returns.
     */
    public void liked(UUID owner, Spot spot, Player liker, boolean likes) {
        if (liker.getUniqueId().equals(owner) || !isOn(owner)) {
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            if (likes) {
                messages.sendConfiguredMessage(online, "spot-guestbook-liked", "{player}", liker.getName(),
                        "{spot}", spot.name());
            }
            return;
        }
        if (likes) {
            newsOf(owner, spot.key()).likes.add(liker.getUniqueId());
        } else {
            Map<String, News> own = news.get(owner);
            News of = own == null ? null : own.get(spot.key());
            if (of == null || !of.likes.remove(liker.getUniqueId())) {
                return;
            }
            forgetIfEmpty(owner, spot.key());
        }
        save();
    }

    /** A spot its owner or an admin deleted: what happened to it is no news any more. */
    public void deleted(UUID owner, String key) {
        Map<String, News> own = news.get(owner);
        if (own == null || own.remove(key) == null) {
            return;
        }
        if (own.isEmpty()) {
            news.remove(owner);
        }
        save();
    }

    private News newsOf(UUID owner, String key) {
        return news.computeIfAbsent(owner, id -> new LinkedHashMap<>()).computeIfAbsent(key, k -> new News());
    }

    private void forgetIfEmpty(UUID owner, String key) {
        Map<String, News> own = news.get(owner);
        if (own == null) {
            return;
        }
        News of = own.get(key);
        if (of != null && of.isEmpty()) {
            own.remove(key);
        }
        if (own.isEmpty()) {
            news.remove(owner);
        }
    }

    /**
     * Back on the server: once the greetings are through, what happened to
     * his spots while he was offline - one line for each of them that is
     * still there -, and then it is no news any more.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!news.containsKey(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> tellNews(player), JOIN_DELAY);
    }

    private void tellNews(Player owner) {
        if (!owner.isOnline() || !isOn(owner.getUniqueId())) {
            return;
        }
        Map<String, News> own = news.remove(owner.getUniqueId());
        if (own == null) {
            return;
        }
        save();
        Map<String, Spot> spots = new HashMap<>();
        for (Spot spot : plugin.getSpots().own(owner)) {
            spots.put(spot.key(), spot);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, News> entry : own.entrySet()) {
            Spot spot = spots.get(entry.getKey());
            if (spot == null || entry.getValue().isEmpty()) {
                continue;
            }
            lines.add(messages.getMessage("spot-guestbook-entry").replace("{spot}", spot.name())
                    .replace("{views}", String.valueOf(entry.getValue().views))
                    .replace("{likes}", String.valueOf(entry.getValue().likes.size())));
        }
        if (lines.isEmpty() || !messages.isMessageEnabled("spot-guestbook-header")) {
            return;
        }
        messages.sendConfiguredMessage(owner, "spot-guestbook-header");
        if (messages.isMessageEnabled("spot-guestbook-entry")) {
            for (String line : lines) {
                owner.sendMessage(line);
            }
        }
    }

    /** At the start and after a reload: the guestbook of the file, or nothing on {@code storage: memory}. */
    public void load() {
        off.clear();
        news.clear();
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
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (file.getBoolean(ownerKey + ".off")) {
                off.add(owner);
                continue;
            }
            ConfigurationSection spots = file.getConfigurationSection(ownerKey + ".news");
            if (spots == null) {
                continue;
            }
            for (String key : spots.getKeys(false)) {
                News of = new News();
                of.views = Math.max(0L, spots.getLong(key + ".views"));
                for (String liker : spots.getStringList(key + ".likes")) {
                    try {
                        of.likes.add(UUID.fromString(liker));
                    } catch (IllegalArgumentException ex) {
                        // A line somebody broke by hand: that one like is lost.
                    }
                }
                if (!of.isEmpty()) {
                    news.computeIfAbsent(owner, id -> new LinkedHashMap<>()).put(Spot.key(key), of);
                }
            }
        }
    }

    /**
     * Writes all of it, on {@code storage: file}. Like the views it goes
     * under another name first and is then moved into place.
     */
    private void save() {
        if (!inFile()) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        for (UUID owner : off) {
            file.set(owner + ".off", true);
        }
        for (Map.Entry<UUID, Map<String, News>> own : news.entrySet()) {
            for (Map.Entry<String, News> spot : own.getValue().entrySet()) {
                String path = own.getKey() + ".news." + spot.getKey();
                file.set(path + ".views", spot.getValue().views);
                List<String> likes = new ArrayList<>();
                for (UUID liker : spot.getValue().likes) {
                    likes.add(liker.toString());
                }
                file.set(path + ".likes", likes);
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
            log.log(Level.WARNING, "The guestbook of the spots could not be written: " + ex);
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
        log.log(Level.WARNING, "The file of the guestbook of the spots could not be read and was put aside, it"
                + " starts empty: " + aside + " (" + reason + ")");
    }
}
