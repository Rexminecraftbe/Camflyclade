package de.elia.cameraplugin.body;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.mob.MobTargeting;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Warns a camera player that something is coming for his body,
 * {@code body-alarm}: a sound and a line in the action bar, while there is
 * still time to fly back before it is hit and camera mode ends.
 *
 * <p>Two things set it off. A mob that is after the body - which only happens
 * while {@code body.mob-target} sends mobs after it; in its mode
 * {@code false} no mob is, and the alarm has nothing to say about them. And,
 * with {@code body-alarm.players}, another player coming closer than
 * {@code player-radius}: not one in camera mode, who cannot touch a body, nor
 * a spectator, nor one the camera player cannot see - a player hidden by a
 * vanish plugin is not given away.</p>
 *
 * <p>Each of them once: as long as the same zombie stays after the body, it
 * does not set off the alarm again. Between two alarms lie at least
 * {@code body-alarm.cooldown} seconds; whatever turns up in that time comes
 * after them, if it is still there.</p>
 */
public final class BodyAlarm {

    /** How often the alarm looks around the body, in ticks. */
    private static final long INTERVAL = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The watch over the body of every camera player, by player id. */
    private final Map<UUID, Watch> watches = new HashMap<>();

    /** What the alarm of one camera player has told him about already. */
    private static final class Watch {
        BukkitRunnable task;
        /** Whatever set off the alarm and is still after the body or near it, by entity id. */
        final Set<UUID> known = new HashSet<>();
        /** Until when the next alarm waits, as {@link System#currentTimeMillis()} counts. */
        long quietUntil;
    }

    public BodyAlarm(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Starts watching the body of a player entering camera mode.
     *
     * @param damageTarget the mannequin that takes the hits, the one mobs go
     *                     for, see {@link de.elia.cameraplugin.session.CameraData#getDamageTarget()}
     */
    public void startFor(Player player, LivingEntity damageTarget) {
        stopFor(player);
        if (!settings.alarmsForMobs() && !settings.alarmsForPlayers()) {
            return;
        }
        UUID id = player.getUniqueId();
        Watch watch = new Watch();
        watch.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !cameraPlayers.contains(id) || !damageTarget.isValid()) {
                    this.cancel();
                    watches.remove(id, watch);
                    return;
                }
                look(player, damageTarget, watch);
            }
        };
        watch.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        watches.put(id, watch);
    }

    public void stopFor(Player player) {
        Watch watch = watches.remove(player.getUniqueId());
        if (watch != null) {
            watch.task.cancel();
        }
    }

    public void onDisable() {
        for (Watch watch : watches.values()) {
            watch.task.cancel();
        }
        watches.clear();
    }

    /** One look around the body: sets off the alarm for whatever is new there. */
    private void look(Player player, LivingEntity body, Watch watch) {
        Map<UUID, Entity> threats = new LinkedHashMap<>();
        if (settings.alarmsForMobs()) {
            // As far as a mob notices anything at all: one that has found
            // the body further away is after it all the same, and a skeleton
            // shoots from fifteen blocks.
            double reach = MobTargeting.MAX_MOB_TARGET_RANGE;
            for (Entity entity : body.getNearbyEntities(reach, reach, reach)) {
                if (entity instanceof Mob mob && isBodyOf(mob.getTarget(), player)) {
                    threats.put(mob.getUniqueId(), mob);
                }
            }
        }
        if (settings.alarmsForPlayers()) {
            double radius = settings.getBodyAlarmPlayerRadius();
            Location at = body.getLocation();
            for (Player other : body.getWorld().getPlayers()) {
                if (other.equals(player) || other.getGameMode() == GameMode.SPECTATOR
                        || cameraPlayers.contains(other.getUniqueId())
                        || !plugin.getPhotoMode().sees(player, other)) {
                    continue;
                }
                if (other.getLocation().distanceSquared(at) <= radius * radius) {
                    threats.put(other.getUniqueId(), other);
                }
            }
        }
        // Whatever has gone is forgotten, so it sets off the alarm again
        // when it comes back.
        watch.known.retainAll(threats.keySet());
        long now = System.currentTimeMillis();
        if (now < watch.quietUntil) {
            return;
        }
        Entity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity threat : threats.values()) {
            if (watch.known.contains(threat.getUniqueId())) {
                continue;
            }
            double distance = threat.getLocation().distanceSquared(body.getLocation());
            if (distance < nearestDistance) {
                nearest = threat;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return;
        }
        // One alarm for all that came at once, naming the closest of them.
        watch.known.addAll(threats.keySet());
        watch.quietUntil = now + settings.getBodyAlarmCooldown() * 1000L;
        sound(player, nearest, Math.sqrt(nearestDistance));
    }

    /** Sets off the alarm, for the mob or the player that came closest. */
    private void sound(Player player, Entity threat, double distance) {
        settings.getBodyAlarmSound().play(player);
        String blocks = String.valueOf(Math.round(distance));
        String text;
        if (threat instanceof Player other) {
            if (!messages.isMessageEnabled("body-alarm-player")) {
                return;
            }
            text = messages.getMessage("body-alarm-player")
                    .replace("{player}", other.getName())
                    .replace("{distance}", blocks);
        } else {
            if (!messages.isMessageEnabled("body-alarm-mob")) {
                return;
            }
            text = messages.getMessage("body-alarm-mob")
                    .replace("{mob}", messages.getMobName((LivingEntity) threat))
                    .replace("{distance}", blocks);
        }
        plugin.getActionBar().warn(player, text);
    }

    /** Whether the entity is the body or the hitbox of this camera player. */
    private boolean isBodyOf(Entity entity, Player player) {
        return entity != null && player.getUniqueId().equals(cameraPlayers.getBodyOrHitboxOwner(entity));
    }
}
