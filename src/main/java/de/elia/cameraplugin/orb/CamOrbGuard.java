package de.elia.cameraplugin.orb;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps experience orbs lying where they are on the screen as well, wherever
 * a camera player comes near them.
 *
 * <p>On the server they lie still already. An orb picks the player it flies
 * to the way a mob picks its target, and
 * {@link de.elia.cameraplugin.mob.MobTargeting} turns a camera player down;
 * picking one up is not possible in camera mode either, see
 * {@link de.elia.cameraplugin.inventory.CamInventoryGuard#startFor}. But the
 * client works out the flight of an orb by itself. The server sends where an
 * orb is once a second at the most, and in between the client lets it fly to
 * the nearest player within eight blocks it knows of - by the rules of the
 * game, which know nothing of what a plugin turned down. On the screen of the
 * camera player, and of everybody who sees the camera player, the orbs came
 * flying after the camera all the same, and jumped back to where they lay
 * whenever the server sent their place.</p>
 *
 * <p>Nothing in the API sends the place of an entity to one client. What it
 * has is hiding an entity from a player and showing it again: the client
 * drops the orb and is sent it anew, at the place it has on the server. And a
 * client keeps an orb it has just been sent still for its first tick - the
 * game works out the flight from the second tick on. An orb sent anew every
 * tick therefore does not move on that screen at all: it lies where it lies
 * on the server, and moves as it moves there.</p>
 *
 * <p>That is done once a tick for every orb within {@link #REACH} of a camera
 * player, for every player whose client has both the orb and that camera
 * player: the camera player and whoever sees the camera player. A client that
 * does not know a camera player lets no orb fly to it, and is left alone.</p>
 *
 * <p>Left alone as well are orbs that are hidden from somebody - by photo
 * mode, or by another plugin: the server does not send them to that player,
 * so there is nothing to send anew. So is an orb that is not visible by
 * default: hiding and showing it again would leave it shown for this plugin
 * from then on, and it would no longer go when the plugin that showed it
 * hides it again.</p>
 *
 * <p>What it costs: an orb sent anew starts its colours over every tick, so
 * near a camera player orbs keep one shade instead of shimmering. A player who
 * walks up to an orb next to a camera player sees it come closer tick by tick,
 * the way it does on the server, instead of smoothly. And every orb sent anew
 * comes with a {@code PlayerHideEntityEvent} and a
 * {@code PlayerShowEntityEvent}.</p>
 *
 * <p>Not reached: an orb that was already flying to the player when camera
 * mode started. The server asks the plugins only when an orb changes the
 * player it follows, so on the server such an orb keeps following the camera
 * until it is more than eight blocks away - and everybody sees it do so, as it
 * is sent where it really is.</p>
 */
public final class CamOrbGuard {

    /** How close a player has to come before an orb flies to them, in blocks: the game's own distance. */
    private static final double FOLLOW_RANGE = 8.0;

    /**
     * How far around a camera player the orbs are sent anew, in blocks.
     *
     * <p>More than {@link #FOLLOW_RANGE}: a client has the camera player a
     * little behind where the server has them, and the camera player's own
     * client a little ahead - by the time a packet takes, at the speed of the
     * flight. Four blocks cover that at ordinary speeds. Flying faster, an
     * orb may move on the screen for a tick before it is reached, by a tenth
     * of a block at the most, and is put back in the next.</p>
     */
    private static final double REACH = FOLLOW_RANGE + 4.0;

    private final JavaPlugin plugin;
    private final CameraPlayers cameraPlayers;
    private BukkitTask task;

    public CamOrbGuard(JavaPlugin plugin, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Starts looking at the orbs around the camera players, once a tick. With
     * nobody in camera mode a look costs nothing.
     */
    public void start() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sendOrbsAnew, 1L, 1L);
        }
    }

    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Sends every orb near a camera player anew to the clients that would let it fly there. */
    private void sendOrbsAnew() {
        if (cameraPlayers.isEmpty()) {
            return;
        }
        Map<ExperienceOrb, List<Player>> near = orbsNearCameras();
        if (near.isEmpty()) {
            return;
        }
        // Who has each camera player, asked once a tick and not once an orb.
        Map<Player, Set<Player>> seenBy = new HashMap<>();
        for (Map.Entry<ExperienceOrb, List<Player>> entry : near.entrySet()) {
            ExperienceOrb orb = entry.getKey();
            for (Player viewer : orb.getTrackedBy()) {
                if (knowsOne(viewer, entry.getValue(), seenBy)) {
                    viewer.hideEntity(plugin, orb);
                    viewer.showEntity(plugin, orb);
                }
            }
        }
    }

    /** The orbs within {@link #REACH} of a camera player, each with the camera players it is near. */
    private Map<ExperienceOrb, List<Player>> orbsNearCameras() {
        Map<ExperienceOrb, List<Player>> near = new HashMap<>();
        for (UUID id : cameraPlayers.ids()) {
            Player camera = plugin.getServer().getPlayer(id);
            if (camera == null) {
                continue;
            }
            Location at = camera.getLocation();
            for (Entity entity : camera.getNearbyEntities(REACH, REACH, REACH)) {
                if (entity instanceof ExperienceOrb orb && orb.isValid() && orb.isVisibleByDefault()
                        && orb.getLocation().distanceSquared(at) <= REACH * REACH) {
                    near.computeIfAbsent(orb, o -> new ArrayList<>(1)).add(camera);
                }
            }
        }
        return near;
    }

    /**
     * Whether the client of this viewer has one of these camera players: the
     * viewer is one of them, or the server sends one of them to the viewer.
     */
    private static boolean knowsOne(Player viewer, List<Player> cameras, Map<Player, Set<Player>> seenBy) {
        for (Player camera : cameras) {
            if (camera.equals(viewer) || seenBy.computeIfAbsent(camera, Player::getTrackedBy).contains(viewer)) {
                return true;
            }
        }
        return false;
    }
}
