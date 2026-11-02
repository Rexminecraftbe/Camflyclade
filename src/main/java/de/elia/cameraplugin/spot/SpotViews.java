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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * How often each spot was looked at, its views: every jump to it and every
 * visit, by anybody - its owner as well. The same player at the same spot
 * counts once in {@code viewpoints.views-cooldown-minutes} at most, so that
 * jumping back and forth does not drive the number up.
 *
 * <p>The views are kept by owner and spot, not with the spot: a spot of an
 * owner who is offline is looked at as well, and his own file is read only
 * while he is online. A spot that is moved keeps them, one that is deleted
 * loses them.</p>
 *
 * <p>Where they are kept follows {@code viewpoints.storage}, like
 * {@link SharedSpots}: in one file for everybody,
 * {@code playerdata/spot-views.yml}, written after every view that counts;
 * or in memory only, which {@code /cam reload} and a restart forget.</p>
 */
public final class SpotViews {

    /** The folder of everything the plugin keeps per player, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The file of the views, in that folder. */
    private static final String FILE = "spot-views.yml";

    /** What stands at the top of the file, for whoever finds it and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: how often each spot of /cam spot was looked at - every jump to it and every visit -, by",
            "the id of its owner and the name of the spot. Deleting it starts every spot at 0 again.");

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final CamSettings settings;
    /** The views of the spots of each owner, by {@link Spot#key()}. */
    private final Map<UUID, Map<String, Long>> views = new LinkedHashMap<>();
    /** When a player last counted at a spot, by owner, spot and player; never written. */
    private final Map<String, Long> lastCounted = new HashMap<>();

    public SpotViews(JavaPlugin plugin, ConsoleLog log, CamSettings settings) {
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

    /** How often one of his spots was looked at. */
    public long of(UUID owner, String key) {
        Map<String, Long> own = views.get(owner);
        return own == null ? 0L : own.getOrDefault(key, 0L);
    }

    /**
     * A player looked at a spot: jumped there or visited it. It counts unless
     * he did the same less than {@code viewpoints.views-cooldown-minutes}
     * ago.
     *
     * @return whether it counted
     */
    public boolean count(UUID owner, String key, UUID viewer) {
        long now = System.currentTimeMillis();
        long again = TimeUnit.MINUTES.toMillis(settings.getViewsCooldownMinutes());
        String who = owner + "/" + key + "/" + viewer;
        Long last = lastCounted.get(who);
        if (last != null && now - last < again) {
            return false;
        }
        lastCounted.values().removeIf(at -> now - at >= again);
        if (again > 0L) {
            lastCounted.put(who, now);
        }
        views.computeIfAbsent(owner, id -> new LinkedHashMap<>()).merge(key, 1L, Long::sum);
        save();
        return true;
    }

    /** A spot its owner has deleted starts at 0 again, should he save one of the same name. */
    public void deleted(UUID owner, String key) {
        Map<String, Long> own = views.get(owner);
        if (own == null || own.remove(key) == null) {
            return;
        }
        if (own.isEmpty()) {
            views.remove(owner);
        }
        String prefix = owner + "/" + key + "/";
        lastCounted.keySet().removeIf(who -> who.startsWith(prefix));
        save();
    }

    /** At the start and after a reload: the views of the file, or none on {@code storage: memory}. */
    public void load() {
        views.clear();
        lastCounted.clear();
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
            ConfigurationSection spots = file.getConfigurationSection(ownerKey);
            if (spots == null) {
                continue;
            }
            for (String key : spots.getKeys(false)) {
                long count = spots.getLong(key);
                if (count > 0L) {
                    views.computeIfAbsent(owner, id -> new LinkedHashMap<>()).put(Spot.key(key), count);
                }
            }
        }
    }

    /**
     * Writes every view, on {@code storage: file}. Like the shared spots it
     * goes under another name first and is then moved into place.
     */
    private void save() {
        if (!inFile()) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        for (Map.Entry<UUID, Map<String, Long>> own : views.entrySet()) {
            for (Map.Entry<String, Long> spot : own.getValue().entrySet()) {
                file.set(own.getKey() + "." + spot.getKey(), spot.getValue());
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
            log.log(Level.WARNING, "The views of the spots could not be written: " + ex);
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
        log.log(Level.WARNING, "The file of the views of the spots could not be read and was put aside, every spot"
                + " starts at 0: " + aside + " (" + reason + ")");
    }
}
