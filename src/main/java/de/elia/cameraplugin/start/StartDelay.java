package de.elia.cameraplugin.start;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wait before camera mode starts, {@code start-delay}: after {@code /cam}
 * the player stands still for a few seconds, the way many servers make a
 * teleport wait. Whoever steps away, takes damage or types {@code /cam} once
 * more calls the start off - nobody slips into camera mode from the middle of
 * a fight or a fall.
 *
 * <p>Looking around is allowed, only where he stands counts. The action bar
 * counts the seconds down, each with the sound of {@code sounds.start-delay}.
 * Whether he may start at all is checked before the wait and once more at its
 * end, see {@link CamStarter}; the cost is only taken then.</p>
 */
public final class StartDelay implements Listener {

    /**
     * How far the player may drift while he waits, in blocks. Anything more
     * is a step: standing still, the server keeps him exactly in place.
     */
    private static final double MAX_DRIFT = 0.1;

    /** Ticks in a second, the steps the action bar counts down in. */
    private static final long SECOND = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The wait of each player who is waiting. */
    private final Map<UUID, BukkitRunnable> waiting = new HashMap<>();
    /** Who has the countdown standing in his action bar, to be cleared when the wait ends. */
    private final Set<UUID> countdownShown = new HashSet<>();

    public StartDelay(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /**
     * Lets the player wait {@code start-delay.seconds}, then runs the start.
     *
     * @param start what starts camera mode once the wait is over; it checks
     *              everything once more, as things may have changed meanwhile
     */
    public void begin(Player player, Runnable start) {
        callOff(player, null);
        UUID id = player.getUniqueId();
        Location spot = player.getLocation();
        int seconds = settings.getStartDelaySeconds();
        long total = seconds * SECOND;
        BukkitRunnable task = new BukkitRunnable() {
            private long ticks;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    callOff(player, null);
                    return;
                }
                if (stepped(spot, player.getLocation())) {
                    callOff(player, "start-delay-moved");
                    return;
                }
                if (ticks >= total) {
                    callOff(player, null);
                    if (!plugin.isInCameraMode(player)) {
                        start.run();
                    }
                    return;
                }
                if (ticks % SECOND == 0) {
                    countDown(player, seconds - ticks / SECOND);
                }
                ticks++;
            }
        };
        waiting.put(id, task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Calls off the wait of a player, if he is waiting.
     *
     * @param key the text that tells him why, or {@code null} to say nothing
     * @return whether he was waiting
     */
    public boolean callOff(Player player, String key) {
        UUID id = player.getUniqueId();
        BukkitRunnable task = waiting.remove(id);
        if (task == null) {
            return false;
        }
        task.cancel();
        // The client would leave the last second standing for a while,
        // next to the message that says the start is off.
        if (countdownShown.remove(id) && player.isOnline()) {
            plugin.getActionBar().clear(player);
        }
        if (key != null) {
            messages.sendMessage(player, key);
        }
        return true;
    }

    /**
     * Calls off every wait, telling each player why: after a reload, which
     * may have changed what the start asks for.
     */
    public void callOffAll(String key) {
        for (UUID id : new ArrayList<>(waiting.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                callOff(player, key);
            } else {
                waiting.remove(id).cancel();
                countdownShown.remove(id);
            }
        }
    }

    /** A hit of any kind calls the start off, a harmless one included. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            callOff(player, "start-delay-damaged");
        }
    }

    /** The seconds left, in the action bar and with the sound of {@code sounds.start-delay}. */
    private void countDown(Player player, long left) {
        settings.getStartDelaySound().play(player);
        if (!messages.isMessageEnabled("start-delay-countdown")) {
            return;
        }
        String text = messages.getMessage("start-delay-countdown")
                .replace("{time}", CamTimeLimit.formatDuration(left));
        if (!text.isEmpty()) {
            plugin.getActionBar().flash(player, text);
            countdownShown.add(player.getUniqueId());
        }
    }

    /** Whether the player has left the spot he started waiting on, by a step or by a teleport. */
    private static boolean stepped(Location spot, Location now) {
        return now.getWorld() == null || !now.getWorld().equals(spot.getWorld())
                || now.distanceSquared(spot) > MAX_DRIFT * MAX_DRIFT;
    }

    /** Stops every wait, when the plugin is switched off. */
    public void onDisable() {
        for (BukkitRunnable task : waiting.values()) {
            task.cancel();
        }
        waiting.clear();
        countdownShown.clear();
    }
}
