package de.elia.cameraplugin.orb;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps experience orbs from flying to a camera player - on the server, and
 * on the screen.
 *
 * <p>On the server most of that is done elsewhere. An orb picks the player it
 * flies to the way a mob picks its target, and
 * {@link de.elia.cameraplugin.mob.MobTargeting} turns a camera player down;
 * picking one up is not possible in camera mode either, see
 * {@link de.elia.cameraplugin.inventory.CamInventoryGuard#startFor}. Two
 * things are left to this class.</p>
 *
 * <p>The orbs that fly to the player already when camera mode starts, see
 * {@link #turnOrbsFromPlayer(Player)}. The server asks the plugins only when
 * an orb picks a player, so these would go on following the camera without
 * asking anybody.</p>
 *
 * <p>And the screen. The client works out the flight of an orb by itself:
 * the server sends where an orb is once a second at the most, and in between
 * the client lets it fly to the nearest player within eight blocks it knows
 * of - by the rules of the game, which know nothing of what a plugin turned
 * down. On the screen of the camera player, and of everybody who sees the
 * camera player, the orbs came flying after the camera all the same, and
 * jumped back to where they lay whenever the server sent their place.</p>
 *
 * <p>Nothing in the API sends the place of an entity to one client. What it
 * has is hiding an entity from a player and showing it again: the client
 * drops the orb and is sent it anew, at the place it has on the server. And a
 * client keeps an orb it has just been sent still for its first tick - the
 * game works out the flight from the second tick on. An orb sent anew every
 * tick therefore does not move on that screen at all: it lies where it lies
 * on the server, and moves as it moves there.</p>
 *
 * <p>That is done once a tick for every orb near a camera player, see
 * {@link #reachOf}, for every player whose client has both the orb and that
 * camera player: the camera player and whoever sees the camera player. A
 * client that does not know a camera player lets no orb fly to it, and is
 * left alone.</p>
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
 * <p>What is still possible: when the server falls behind, a client may tick
 * twice before the orb comes anew, and moves it for one tick - a hundredth to
 * a tenth of a block, put back with the next tick of the server.</p>
 */
public final class CamOrbGuard {

    /** How close a player has to come before an orb flies to them, in blocks: the game's own distance. */
    private static final double FOLLOW_RANGE = 8.0;

    /**
     * How far around a camera player who keeps still the orbs are sent anew,
     * in blocks: {@link #FOLLOW_RANGE}, with room for a client that has the
     * camera player a little away from where the server has them.
     */
    private static final double REACH = FOLLOW_RANGE + 4.0;

    /**
     * For how many ticks of flight the reach grows, see {@link #reachOf}: the
     * camera player's own client is that far ahead of the server - by the time
     * its movement takes to arrive -, and every other client that far behind -
     * by the time the server's takes to arrive, and the ticks the client
     * takes to glide a player to the place it was sent.
     */
    private static final double LEAD_TICKS = 6.0;

    /**
     * The most a camera player can fly in a tick, in blocks, with room to
     * spare: sprinting at the highest {@code flight-speed} the config allows
     * comes to five and a half. A step further than that is a teleport, and
     * says nothing about the speed.
     */
    private static final double MAX_FLIGHT_STEP = 8.0;

    /**
     * What is left of the speed of a camera player from one tick to the next
     * when they stop. The server gets the movement of a client at uneven
     * times - none in one tick, two steps in the next -, and the reach must
     * not shrink and grow with that.
     */
    private static final double SLOWDOWN = 0.8;

    /**
     * How far up the orbs around a player who starts camera mode are taken
     * for a tick, in blocks: three times {@link #FOLLOW_RANGE}, so the player
     * is out of reach from any of them, see {@link #turnOrbsFromPlayer}.
     */
    private static final double AWAY = 3.0 * FOLLOW_RANGE;

    private final JavaPlugin plugin;
    private final CameraPlayers cameraPlayers;
    /** How fast each camera player has been flying, by player id. */
    private final Map<UUID, Flight> flights = new HashMap<>();
    /** The orbs taken up by {@link #turnOrbsFromPlayer}, with the place they go back to. */
    private final Map<ExperienceOrb, Location> takenUp = new HashMap<>();
    private BukkitTask task;

    /** Where a camera player was a tick ago, and how fast they have been flying, in blocks per tick. */
    private static final class Flight {
        Location last;
        double speed;
    }

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

    /** Stops looking, and puts back the orbs that are up for their tick. */
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Not sent anew: Bukkit turns hiding down for a plugin that is being
        // switched off. The server sends their place within a second.
        for (Map.Entry<ExperienceOrb, Location> entry : takenUp.entrySet()) {
            putBack(entry.getKey(), entry.getValue());
        }
        takenUp.clear();
        flights.clear();
    }

    /**
     * Makes the orbs that fly to a player who is just starting camera mode
     * let go of them.
     *
     * <p>Which player an orb follows, the server asks the plugins only when
     * the orb picks one. An orb that was flying to the player already - in an
     * experience farm, the ones on their way and the ones around the player
     * not taken in yet - goes on following the camera without asking.
     * It lets go only once its player is more than eight blocks away, and no
     * API makes it let go otherwise.</p>
     *
     * <p>So every orb close enough to follow the player is taken
     * {@link #AWAY} blocks up for one tick of the world: there the player is
     * out of its reach, and it lets go. Back at its place, it looks for the
     * nearest player again, finds the camera, and is turned down by
     * {@link de.elia.cameraplugin.mob.MobTargeting}. It comes back without
     * the swing it had, so it drops where it was, and it is sent anew to
     * everybody who has it - in the one tick it was up there, the server may
     * have sent that place.</p>
     *
     * <p>Back after two ticks and not one, as in
     * {@link de.elia.cameraplugin.session.CameraMode}: the command that
     * starts camera mode runs between two ticks, and a task one tick later
     * would come before the world had ticked at all.</p>
     *
     * <p>An orb that sits on something, or carries something, stays: taking
     * it up would take it off, or would not be done at all.</p>
     */
    public void turnOrbsFromPlayer(Player player) {
        Location at = player.getLocation();
        double reach = FOLLOW_RANGE + 1.0;
        List<ExperienceOrb> taken = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(reach, reach, reach)) {
            if (entity instanceof ExperienceOrb orb && orb.isValid() && !orb.isInsideVehicle()
                    && orb.getPassengers().isEmpty() && !takenUp.containsKey(orb)
                    && orb.getLocation().distanceSquared(at) <= reach * reach) {
                Location back = orb.getLocation();
                if (orb.teleport(back.clone().add(0.0, AWAY, 0.0))) {
                    takenUp.put(orb, back);
                    taken.add(orb);
                }
            }
        }
        if (taken.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (ExperienceOrb orb : taken) {
                Location back = takenUp.remove(orb);
                if (back != null && putBack(orb, back) && orb.isVisibleByDefault()) {
                    for (Player viewer : orb.getTrackedBy()) {
                        sendAnew(viewer, orb);
                    }
                }
            }
        }, 2L);
    }

    /**
     * Puts an orb back where it was taken up from, without the swing it had.
     * Whether it is still there to be put back - it may have been merged with
     * another one up there.
     */
    private static boolean putBack(ExperienceOrb orb, Location back) {
        if (!orb.isValid()) {
            return false;
        }
        orb.teleport(back);
        orb.setVelocity(new Vector());
        return true;
    }

    /** Sends every orb near a camera player anew to the clients that would let it fly there. */
    private void sendOrbsAnew() {
        if (cameraPlayers.isEmpty()) {
            flights.clear();
            return;
        }
        flights.keySet().retainAll(cameraPlayers.ids());
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
                    sendAnew(viewer, orb);
                }
            }
        }
    }

    private void sendAnew(Player viewer, ExperienceOrb orb) {
        viewer.hideEntity(plugin, orb);
        viewer.showEntity(plugin, orb);
    }

    /** The orbs within the reach of a camera player, each with the camera players it is near. */
    private Map<ExperienceOrb, List<Player>> orbsNearCameras() {
        Map<ExperienceOrb, List<Player>> near = new HashMap<>();
        for (UUID id : cameraPlayers.ids()) {
            Player camera = plugin.getServer().getPlayer(id);
            if (camera == null) {
                continue;
            }
            Location at = camera.getLocation();
            double reach = reachOf(id, at);
            for (Entity entity : camera.getNearbyEntities(reach, reach, reach)) {
                if (entity instanceof ExperienceOrb orb && orb.isValid() && orb.isVisibleByDefault()
                        && !takenUp.containsKey(orb) && orb.getLocation().distanceSquared(at) <= reach * reach) {
                    near.computeIfAbsent(orb, o -> new ArrayList<>(1)).add(camera);
                }
            }
        }
        return near;
    }

    /**
     * How far around this camera player the orbs are sent anew, in blocks.
     *
     * <p>{@link #REACH}, and more the faster they fly: the camera player's
     * own client is ahead of where the server has them, every other client
     * behind, each by the way {@link #LEAD_TICKS} ticks of flight cover. An
     * orb further away than that may still move on a screen for a tick or
     * two before it is reached, and is put back then.</p>
     */
    private double reachOf(UUID id, Location at) {
        Flight flight = flights.computeIfAbsent(id, key -> new Flight());
        double step = flight.last != null && at.getWorld() != null && at.getWorld().equals(flight.last.getWorld())
                ? flight.last.distance(at) : 0.0;
        if (step > MAX_FLIGHT_STEP) {
            step = 0.0;
        }
        flight.speed = Math.max(step, flight.speed * SLOWDOWN);
        flight.last = at;
        return REACH + flight.speed * LEAD_TICKS;
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
