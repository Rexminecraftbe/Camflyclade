package de.elia.cameraplugin.body;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Watches the body while camera mode runs, after
 * {@code body.movement-sensitivity}: on level 0 it is held in its spot, on the
 * levels above that camera mode ends as soon as it leaves it.
 */
public final class BodyWatch {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;

    public BodyWatch(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Watches the mannequin while camera mode is running and ends the mode as
     * soon as it leaves its spot. Both body types run through this one check,
     * because both have a mannequin: with body type 1 it is the invisible one
     * standing in the armour stand, with type 2 the visible body itself. It is
     * the entity that takes the hits in either case, so gravity, water and
     * pistons are noticed on the same entity for both types.
     *
     * <p>Deliberately checked by the scheduler and not through an event: the
     * mannequin has no AI, so {@code EntityMoveEvent} does not fire for it, and
     * falling or drifting away would go unnoticed.</p>
     *
     * <p>Drowning, suffocation, fire and lava are not checked here: the
     * mannequin takes that damage itself and
     * {@link de.elia.cameraplugin.mirrordamage.DamageMirror#onBodyDamage} ends
     * camera mode.</p>
     */
    public void startBodyMovementCheck(Player player, LivingEntity mannequin) {
        if (settings.getMovementSensitivity().isFixed()) {
            // Nothing can move the body on this level, so the check would only
            // compare a location with itself every tick.
            return;
        }
        new BukkitRunnable() {
            /**
             * The spot the body is compared against. Taken at the first run and
             * not when it is spawned, so that settling onto the ground right
             * after the spawn does not already count as movement.
             */
            private Location reference;

            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline() || mannequin.isDead()) {
                    this.cancel();
                    return;
                }
                Location current = mannequin.getLocation();
                if (reference == null) {
                    reference = current.clone();
                    return;
                }
                if (hasMoved(reference, current)) {
                    plugin.getMessages().sendConfiguredMessage(player, "body-moved");
                    plugin.exitCameraMode(player);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 20L, 1L);
    }

    /**
     * Puts the body back whenever something moved it, as long as sensitivity
     * level 0 is set.
     *
     * <p>{@code setImmovable} keeps gravity and knockback off the mannequin but
     * does not stop a piston, and an armour stand is pushed by one as well. The
     * level promises that nothing moves the body, so what a piston does is
     * undone here.</p>
     */
    public void startBodyPin(Player player, LivingEntity body, Mannequin hitbox) {
        if (!settings.getMovementSensitivity().isFixed()) {
            return;
        }
        final Location anchor = body.getLocation().clone();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline() || body.isDead()) {
                    this.cancel();
                    return;
                }
                pinToSpot(body, anchor);
                if (hitbox != null && !hitbox.isDead()) {
                    pinToSpot(hitbox, anchor);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Teleports the entity back to its spot when something pushed it away. */
    private void pinToSpot(Entity entity, Location anchor) {
        Location current = entity.getLocation();
        if (!current.getWorld().equals(anchor.getWorld())
                || current.distanceSquared(anchor) > CamSettings.MIN_MOVE_THRESHOLD * CamSettings.MIN_MOVE_THRESHOLD) {
            entity.teleport(anchor);
            entity.setVelocity(new Vector());
        }
    }

    /** {@code true} when the body left its spot or its world. */
    private boolean hasMoved(Location reference, Location current) {
        if (!current.getWorld().equals(reference.getWorld())) {
            return true;
        }
        return current.distanceSquared(reference) > settings.getMoveThresholdSquared();
    }
}
