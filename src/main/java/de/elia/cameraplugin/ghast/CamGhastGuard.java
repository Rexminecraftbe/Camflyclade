package de.elia.cameraplugin.ghast;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the camera player off happy ghasts.
 *
 * <p>A happy ghast stops in mid air as long as somebody is standing on its back
 * or hovering just above it - that is what makes it boardable. The camera player
 * is invisible and is meant to leave the world the way he found it, so a ghast
 * hanging in the sky because an unseen camera came to rest on it is exactly the
 * kind of mark camera mode must not leave: the players below watch their mount
 * stop for a reason none of them can see.</p>
 *
 * <p>He is therefore lifted clear of the ghast and put back into flight. The
 * ghast has nobody on it any more and flies on, and the camera keeps the spot it
 * was looking from - only far enough above the ghast that the game stops
 * counting it as somebody waiting to board.</p>
 *
 * <p>The other two ways onto a ghast are shut elsewhere: a happy ghast is a
 * vehicle, so riding one runs into {@code onVehicleEnter} and
 * {@code onCameraMount}, and the right click that boards it into the
 * interaction lock of camera mode. What is left here is the one that needs
 * neither - simply standing on it.</p>
 */
public class CamGhastGuard {
    /**
     * How far above a happy ghast a player still holds it still, in blocks.
     *
     * <p>The ghast is given ten ticks of standing still every tick it finds a
     * player less than two blocks above it, so two blocks is the reach that has
     * to be left free. The check below is deliberately a little wider than the
     * game's own: standing still belongs to the ghast, and being wrong the other
     * way round would leave it hanging in the air.</p>
     */
    private static final double STILL_REACH = 2.0;
    /** How far past the sides of the ghast the same still applies. */
    private static final double SIDE_REACH = 1.0;
    /** How far below its back a camera still counts as standing on it. */
    private static final double BELOW_REACH = 0.5;
    /** Put between camera and reach, so a tick of drift does not undo the lift. */
    private static final double CLEARANCE = 0.5;
    /** The box around the camera that is searched for ghasts, in blocks. */
    private static final double SEARCH = 8.0;
    /**
     * Ticks between two looks. Well inside the ten the ghast holds still for, so
     * it is back under way before anybody riding it notices the stop.
     */
    private static final long INTERVAL = 2L;

    private final JavaPlugin plugin;
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public CamGhastGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts watching this camera player. */
    public void startFor(Player player) {
        if (tasks.containsKey(player.getUniqueId())) {
            return;
        }
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopFor(player);
                    return;
                }
                HappyGhast ghast = ghastHeldStill(player);
                if (ghast != null) {
                    liftOff(player, ghast);
                }
            }
        };
        task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        tasks.put(player.getUniqueId(), task);
    }

    /** Stops watching him, when camera mode ends. */
    public void stopFor(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    /**
     * The happy ghast this camera is holding still, or {@code null} when he is
     * on none.
     *
     * <p>Where several are stacked the highest one is answered: lifting the
     * camera clear of that one clears it of all of them at once, instead of
     * carrying it up one ghast per look.</p>
     */
    private HappyGhast ghastHeldStill(Player player) {
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        HappyGhast highest = null;
        for (Entity entity : world.getNearbyEntities(at, SEARCH, SEARCH, SEARCH,
                nearby -> nearby instanceof HappyGhast)) {
            HappyGhast ghast = (HappyGhast) entity;
            if (!holdsStill(ghast, at)) {
                continue;
            }
            if (highest == null
                    || ghast.getBoundingBox().getMaxY() > highest.getBoundingBox().getMaxY()) {
                highest = ghast;
            }
        }
        return highest;
    }

    /** Whether a camera at this spot keeps that ghast from flying on. */
    private boolean holdsStill(HappyGhast ghast, Location camera) {
        BoundingBox box = ghast.getBoundingBox();
        double feet = camera.getY();
        if (feet < box.getMaxY() - BELOW_REACH || feet > box.getMaxY() + STILL_REACH) {
            return false;
        }
        return camera.getX() >= box.getMinX() - SIDE_REACH
                && camera.getX() <= box.getMaxX() + SIDE_REACH
                && camera.getZ() >= box.getMinZ() - SIDE_REACH
                && camera.getZ() <= box.getMaxZ() + SIDE_REACH;
    }

    /** Puts the camera above the ghast's reach and hands it back its flight. */
    private void liftOff(Player player, HappyGhast ghast) {
        Location to = player.getLocation();
        to.setY(ghast.getBoundingBox().getMaxY() + STILL_REACH + CLEARANCE);
        player.teleport(to);
        keepFlying(player);
    }

    /**
     * Hands the player his flight back after the lift.
     *
     * <p>A teleport takes it away from him, and the client is sent that state
     * along with it, so it is given back once more a tick later - without that
     * he drops straight back onto the ghast he was just taken off. The same two
     * steps the plugin itself takes after carrying him through a portal.</p>
     */
    private void keepFlying(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && tasks.containsKey(player.getUniqueId())) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            }
        }.runTaskLater(plugin, 1L);
    }
}
