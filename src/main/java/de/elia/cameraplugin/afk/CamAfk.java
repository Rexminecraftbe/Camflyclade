package de.elia.cameraplugin.afk;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ends camera mode once the camera has stood still for a while,
 * {@code afk-end}: nobody leaves a body standing around for hours, and no
 * battery runs empty while nobody flies.
 *
 * <p>Still means the player does nothing at all: he does not fly, does not
 * look around and does not turn the mouse wheel. A step the border turns
 * away counts as doing something - he is at the keys, only the camera cannot
 * go there. Shortly before the end {@code end-warning} speaks up, the way it
 * does before a time limit or a battery runs out.</p>
 */
public final class CamAfk implements Listener {

    /** Ticks between two looks at how long a camera has stood still. */
    private static final long INTERVAL = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** When each camera player last did something, as {@link System#currentTimeMillis()} counts. */
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public CamAfk(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /** Starts the clock for a player who has just entered camera mode. */
    public void startFor(Player player) {
        stopFor(player);
        if (!settings.isAfkEndEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        lastActive.put(id, System.currentTimeMillis());
        long limit = settings.getAfkEndSeconds() * 1000L;
        BukkitRunnable task = new BukkitRunnable() {
            /** The seconds left at the look before, for {@code end-warning}. */
            private double before = limit / 1000.0;

            @Override
            public void run() {
                Long active = lastActive.get(id);
                if (!player.isOnline() || !plugin.isInCameraMode(player) || active == null) {
                    stopFor(player);
                    return;
                }
                double left = (limit - (System.currentTimeMillis() - active)) / 1000.0;
                plugin.getEndWarning().check(player, before, left, "end-warning-afk");
                before = left;
                if (left <= 0.0) {
                    plugin.exitCameraMode(player, EndReason.AFK);
                    messages.sendMessage(player, "afk-ended",
                            "{time}", CamTimeLimit.formatDuration(settings.getAfkEndSeconds()));
                }
            }
        };
        task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        tasks.put(id, task);
    }

    /** Stops the clock of a player leaving camera mode. */
    public void stopFor(Player player) {
        UUID id = player.getUniqueId();
        lastActive.remove(id);
        BukkitRunnable task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * A step or a look around. Also one that something turned away, the
     * border say: the player was at the keys all the same.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()
                && from.getYaw() == to.getYaw() && from.getPitch() == to.getPitch())) {
            return;
        }
        noteActive(event.getPlayer());
    }

    /** A turn of the mouse wheel, which changes the speed and is cancelled for that. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerItemHeldEvent event) {
        noteActive(event.getPlayer());
    }

    private void noteActive(Player player) {
        lastActive.computeIfPresent(player.getUniqueId(), (id, last) -> System.currentTimeMillis());
    }

    /** Stops every clock, when the plugin is switched off. */
    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        lastActive.clear();
    }
}
