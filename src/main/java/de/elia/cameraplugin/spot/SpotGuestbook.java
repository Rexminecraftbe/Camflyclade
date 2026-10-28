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
import org.bukkit.scheduler.BukkitTask;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The guestbook of the spots, {@code viewpoints.guestbook}: the owner of a
 * spot hears how often it was looked at - jumped to or visited - and liked.
 * Only others count, and only with the views {@link SpotViews} counts: the
 * same player at the same spot is one view in
 * {@code viewpoints.views-cooldown-minutes} at most.
 *
 * <p>Nothing of it is told straight away: it is collected, and told as how
 * many views and likes each of his spots got, so nobody can fill another's
 * chat by jumping to his spots or liking one and taking it back - a like
 * taken back before it is told does not count. How he hears it, each owner
 * sets for himself with the bell on the pages of the spots in the menu, see
 * {@link Mode}: every {@code viewpoints.guestbook-interval-minutes} while he
 * plays, and what came while he was away once he joins; only once he joins;
 * or not at all, and then nothing is collected for him either. Nor is
 * anything collected for, or told to, one a restriction keeps from looking at
 * the public spots or at those shared by name, see {@link #isLocked}: the
 * spots of others are closed to him, and so is what others make of his.</p>
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
            "CamFly: the guestbook of /cam spot - how each owner hears of his spots, if not every few minutes (mode),",
            "and what happened to them since he was last told, by his id and the name of the spot. Deleting it forgets",
            "that news.");

    /**
     * How long after he joins an owner hears what happened while he was
     * offline, in ticks: after the greetings of the server, not lost among
     * them.
     */
    private static final long JOIN_DELAY = 40L;

    /** Ticks in a minute. */
    private static final long MINUTE = 20L * 60L;

    /** How an owner hears of his spots, from the bell in the menu - which goes through them in this order. */
    public enum Mode {
        /** Every {@code viewpoints.guestbook-interval-minutes} while he plays, and when he joins. */
        ON,
        /** Only when he joins: everything since he last heard of it. */
        LOGIN,
        /** Not at all, and nothing is collected for him. */
        OFF
    }

    private final CameraPlugin plugin;
    private final ConsoleLog log;
    private final CamSettings settings;
    private final Messages messages;
    /** How each owner hears of his spots who set the bell to anything but {@link Mode#ON}. */
    private final Map<UUID, Mode> modes = new HashMap<>();
    /** What happened to the spots of each owner since he was last told, by {@link Spot#key()}. */
    private final Map<UUID, Map<String, News>> news = new LinkedHashMap<>();
    /** The owners who just joined: they hear what came while they were away first, see {@link #onJoin}. */
    private final Set<UUID> joining = new HashSet<>();
    /** Tells the owners online what came, every {@code viewpoints.guestbook-interval-minutes}. */
    private BukkitTask updates;

    /** What happened to one spot since its owner was last told. */
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

    /** How the owner hears of his spots: {@link Mode#OFF} for everybody while there is no guestbook. */
    public Mode modeOf(UUID owner) {
        return settings.isGuestbookEnabled() ? modes.getOrDefault(owner, Mode.ON) : Mode.OFF;
    }

    /** Whether the owner hears of his spots at all: the guestbook is there, and he did not switch it off. */
    public boolean isOn(UUID owner) {
        return modeOf(owner) != Mode.OFF;
    }

    /**
     * Whether a restriction keeps the owner from looking at the public spots
     * or at those shared by name, see {@link SpotModeration}: then nothing is
     * collected for him and nothing told, however he set the bell.
     */
    public boolean isLocked(UUID owner) {
        SpotModeration moderation = plugin.getSpotModeration();
        return moderation.keepsFromLooking(owner, SpotBans.Kind.PUBLIC)
                || moderation.keepsFromLooking(owner, SpotBans.Kind.SHARED);
    }

    /**
     * Sets how he hears of his spots, from the bell in the menu, and tells
     * him how it stands now. Switched off, what was collected for him is
     * gone.
     */
    public void set(Player owner, Mode mode) {
        if (!settings.isGuestbookEnabled() || isLocked(owner.getUniqueId())) {
            return;
        }
        UUID id = owner.getUniqueId();
        if (mode == Mode.ON) {
            modes.remove(id);
        } else {
            modes.put(id, mode);
        }
        switch (mode) {
            case ON -> messages.sendConfiguredMessage(owner, "spot-guestbook-on",
                    "{minutes}", String.valueOf(settings.getGuestbookIntervalMinutes()));
            case LOGIN -> messages.sendConfiguredMessage(owner, "spot-guestbook-login");
            case OFF -> {
                news.remove(id);
                messages.sendConfiguredMessage(owner, "spot-guestbook-off");
            }
        }
        save();
    }

    /**
     * A player looked at a spot of the owner, and it counted as a view, see
     * {@link SpotViews#count}. The owner himself is no guest.
     */
    public void viewed(UUID owner, Spot spot, Player viewer) {
        if (viewer.getUniqueId().equals(owner) || !isOn(owner) || isLocked(owner)) {
            return;
        }
        newsOf(owner, spot.key()).views++;
        save();
    }

    /**
     * A player liked a public spot of the owner, or no longer does. A like
     * taken back before the owner is told was never there.
     */
    public void liked(UUID owner, Spot spot, Player liker, boolean likes) {
        if (liker.getUniqueId().equals(owner) || !isOn(owner) || isLocked(owner)) {
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
     * his spots since he last heard of it, see {@link #tellNews}.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!news.containsKey(id)) {
            return;
        }
        joining.add(id);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            joining.remove(id);
            tellNews(player, "spot-guestbook-header");
        }, JOIN_DELAY);
    }

    /**
     * Every {@code viewpoints.guestbook-interval-minutes}: what came for the
     * owners online who hear of it while they play - not for one who just
     * joined, he hears all of it once the greetings are through.
     */
    private void tellUpdates() {
        for (UUID owner : new ArrayList<>(news.keySet())) {
            Player online = Bukkit.getPlayer(owner);
            if (online != null && modeOf(owner) == Mode.ON && !joining.contains(owner)) {
                tellNews(online, "spot-guestbook-update");
            }
        }
    }

    /**
     * Tells the owner what happened to his spots since he was last told,
     * under this header - one line for each of them that is still there -,
     * and then it is no news any more. One locked out of the spots of others
     * is told nothing, and it is gone all the same.
     */
    private void tellNews(Player owner, String header) {
        if (!owner.isOnline() || !isOn(owner.getUniqueId())) {
            return;
        }
        Map<String, News> own = news.remove(owner.getUniqueId());
        if (own == null) {
            return;
        }
        save();
        if (isLocked(owner.getUniqueId())) {
            return;
        }
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
        if (lines.isEmpty() || !messages.isMessageEnabled(header)) {
            return;
        }
        messages.sendConfiguredMessage(owner, header,
                "{minutes}", String.valueOf(settings.getGuestbookIntervalMinutes()));
        if (messages.isMessageEnabled("spot-guestbook-entry")) {
            for (String line : lines) {
                owner.sendMessage(line);
            }
        }
    }

    /**
     * At the start and after a reload: the guestbook of the file, or nothing
     * on {@code storage: memory} - and the clock of the updates, which starts
     * anew.
     */
    public void load() {
        modes.clear();
        news.clear();
        joining.clear();
        if (updates != null) {
            updates.cancel();
            updates = null;
        }
        if (settings.isGuestbookEnabled()) {
            long every = settings.getGuestbookIntervalMinutes() * MINUTE;
            updates = Bukkit.getScheduler().runTaskTimer(plugin, this::tellUpdates, every, every);
        }
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
            Mode mode = modeIn(file, ownerKey);
            if (mode != Mode.ON) {
                modes.put(owner, mode);
            }
            if (mode == Mode.OFF) {
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
     * How this owner hears of his spots, as the file has it: {@code mode},
     * or the {@code off: true} of the time the bell only switched it on and
     * off. An {@code off} written by hand without quotes reads as
     * {@code false}; what it cannot read at all is {@link Mode#ON}.
     */
    private static Mode modeIn(YamlConfiguration file, String ownerKey) {
        if (file.getBoolean(ownerKey + ".off")) {
            return Mode.OFF;
        }
        return switch (file.getString(ownerKey + ".mode", "on").toLowerCase(Locale.ROOT)) {
            case "login" -> Mode.LOGIN;
            case "off", "false" -> Mode.OFF;
            default -> Mode.ON;
        };
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
        for (Map.Entry<UUID, Mode> mode : modes.entrySet()) {
            file.set(mode.getKey() + ".mode", mode.getValue().name().toLowerCase(Locale.ROOT));
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
