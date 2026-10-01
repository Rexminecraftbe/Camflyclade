package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The name over the invisible camera player, {@code camera-mode.name-visible}
 * and {@code camera-mode.name}.
 *
 * <p>The client draws no name tag over an invisible player, so whoever sees
 * the camera player only by his outline would not know who he is. The name is
 * a text display of its own, see {@link NameDisplay}, put back over his head
 * every tick.</p>
 *
 * <p>It does not ride on him as a passenger, although that would carry it
 * along without any work. Spigot turns down every {@code player.teleport()}
 * of a player who carries a passenger, and camera mode ends with exactly such
 * a teleport, back to the body - he would stay where he is. The border, the
 * happy ghast and the way back from a forbidden world move the camera the
 * same way. Paper does take a passenger along within one world, but drops it
 * at a portal: the name would be left standing there.</p>
 */
public final class CamNameTag {

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /**
     * The same mark the name over the body carries, so
     * {@link de.elia.cameraplugin.body.BodySpawner#removeLeftoverEntities()}
     * finds both.
     */
    private final NamespacedKey nameKey;
    private final Map<UUID, TextDisplay> names = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public CamNameTag(JavaPlugin plugin, CamSettings settings, Messages messages, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.cameraPlayers = cameraPlayers;
        this.nameKey = new NamespacedKey(plugin, "cam_name");
    }

    /**
     * Whether a camera player gets a name at all.
     *
     * <p>Only in mode ALL and only while he is made invisible. Without the
     * invisibility his own name tag is there. Mode NONE hides him from
     * everybody, and in mode CAM the only ones who see him are camera players
     * themselves - they share the team {@code cam_no_push} with him and see
     * him through the invisibility, name tag included.</p>
     */
    private boolean carriesName() {
        return settings.isPlayerNameVisible()
                && settings.getPlayerVisibilityMode() == VisibilityMode.ALL
                && settings.allowsInvisibilityPotion();
    }

    public void startFor(Player player) {
        stopFor(player);
        if (!carriesName()) {
            return;
        }
        TextDisplay name = spawnName(player);
        if (name == null) {
            return;
        }
        names.put(player.getUniqueId(), name);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    stopFor(player);
                    return;
                }
                TextDisplay current = followPlayer(player);
                if (current == null) {
                    stopFor(player);
                    return;
                }
                showToViewers(player, current);
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
        tasks.put(player.getUniqueId(), task);
    }

    /**
     * Puts up the name, hidden from everybody: {@link #showToViewers} lets the
     * right ones see it, starting with the next tick.
     */
    private TextDisplay spawnName(Player player) {
        String format = messages.getMessage("player.name-format").replace("{player}", player.getName());
        return NameDisplay.spawn(nameLocation(player), format, settings.getPlayerNameStyle(), nameKey,
                display -> display.setVisibleByDefault(false));
    }

    /**
     * Where the name of the camera player stands: over his head, where the game
     * would put his name tag. Measured from his height of the moment, which
     * shrinks while he swims or crawls.
     */
    private Location nameLocation(Player player) {
        return player.getLocation().add(0.0, player.getHeight() + NameDisplay.ABOVE_HEAD, 0.0);
    }

    /**
     * Puts the name back over the player whenever the two have come apart.
     *
     * <p>Into another world it is not carried but put up anew: the player went
     * through a portal, and the name is simply set down where he came out.</p>
     *
     * @return the name over him, or {@code null} when it could not be put up
     */
    private TextDisplay followPlayer(Player player) {
        TextDisplay name = names.get(player.getUniqueId());
        Location target = nameLocation(player);
        if (name == null || !name.isValid() || !name.getWorld().equals(target.getWorld())) {
            if (name != null) {
                name.remove();
            }
            name = spawnName(player);
            if (name == null) {
                names.remove(player.getUniqueId());
                return null;
            }
            names.put(player.getUniqueId(), name);
            return name;
        }
        if (name.getLocation().distanceSquared(target) > CamSettings.MIN_MOVE_THRESHOLD * CamSettings.MIN_MOVE_THRESHOLD) {
            name.teleport(target);
        }
        return name;
    }

    /** Shows the name to everybody who should see it and hides it from the rest. */
    private void showToViewers(Player owner, TextDisplay name) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean shown = showsNameTo(owner, viewer);
            if (shown == viewer.canSee(name)) {
                continue;
            }
            if (shown) {
                viewer.showEntity(plugin, name);
            } else {
                viewer.hideEntity(plugin, name);
            }
        }
    }

    /**
     * Whether this viewer is shown the name. Not the camera player himself,
     * who has no name tag over his own head either. Not the other camera
     * players: they are in his team and see his own name tag. Not spectators,
     * who see through every invisibility and get the name tag as well. And
     * nobody he is hidden from.
     */
    private boolean showsNameTo(Player owner, Player viewer) {
        return !viewer.equals(owner)
                && !cameraPlayers.contains(viewer.getUniqueId())
                && viewer.getGameMode() != GameMode.SPECTATOR
                && viewer.canSee(owner);
    }

    /** Takes the name away, at the end of camera mode. */
    public void stopFor(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        TextDisplay name = names.remove(player.getUniqueId());
        if (name != null) {
            name.remove();
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (TextDisplay name : names.values()) {
            name.remove();
        }
        names.clear();
    }
}
