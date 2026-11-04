package de.elia.cameraplugin.spawning;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.SpawnerSpawnEvent;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code mob-spawning}: where a player in camera mode counts for the mobs that
 * spawn by themselves.
 *
 * <p>To the game a camera is a player like any other. Mobs spawn round it,
 * between 24 and 128 blocks away, and a spawner starts once it is close
 * enough. Without this the camera could be hung at the best spot of a farm,
 * in the middle of the air, while the body stands safely somewhere else.</p>
 *
 * <p>Only players make the game spawn anything, and a body is none, so the
 * spawning cannot simply be moved over to it. What this does instead is look
 * at every mob that natural spawning or a spawner is about to bring out and
 * call it off where it would not have come out without camera mode. The game
 * goes on choosing where to try round the camera; this only sorts out. With
 * {@link SpawnMode#BODY} the players it measures against are the bodies of
 * those in camera mode and everybody out of it - a mob comes out only where it
 * would if each of them stood at his body, with the camera close by nothing
 * changes, the further away it flies the less gets through, and never more
 * than at the body. With {@link SpawnMode#OFF} the bodies do not count
 * either. Players out of camera mode count as ever; vanilla has already
 * measured against them before anything gets here, so this never lets
 * anything through that vanilla would have stopped.</p>
 *
 * <p>Natural spawning is measured as the game measures it: the nearest
 * player more than 24 and at most 128 blocks away. A spawner needs one within
 * its own range, its {@code RequiredPlayerRange}. Left alone are the mobs
 * that come under natural spawning without being part of it - phantoms,
 * cats, the wandering trader and his llamas, each brought by a spawner of its
 * own with rules of its own -, patrols, trial spawners, and despawning, which
 * the game keeps reckoning from the camera.</p>
 *
 * <p>On Paper the check runs before the mob is even made, on Paper's
 * {@code PreCreatureSpawnEvent}, looked up at runtime like the events in
 * {@link de.elia.cameraplugin.interaction.CamKnockbackGuard}: otherwise every
 * try called off would first make a whole mob, and with the spawning round a
 * far camera never filling up, the game keeps trying. Where a try is out of
 * the reach of every player, it is told to give up the whole group for this
 * tick. Spigot has only Bukkit's {@code CreatureSpawnEvent}, after the
 * mob is made.</p>
 */
public final class CamSpawnFilter implements Listener {

    /** Paper's event before a mob of natural spawning or a spawner is made. */
    private static final String PAPER_PRE_SPAWN = "com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent";

    /** Paper's question whether a player counts for spawning at all, as vanish plugins set it. */
    private static final String PAPER_AFFECTS_SPAWNING = "getAffectsSpawning";

    /** Nothing spawns by itself this close to a player, 24 blocks, squared. */
    static final double MIN_DISTANCE_SQUARED = 24.0 * 24.0;

    /** Nothing spawns by itself further than this from every player, 128 blocks, squared. */
    static final double MAX_DISTANCE_SQUARED = 128.0 * 128.0;

    /**
     * How far from every player a try has to be for its whole group to be
     * given up, squared: 128 blocks and 40 more. A group strays from where it
     * started by up to five blocks a step, four steps along each axis -
     * under 35 blocks -, so none of it can come back into reach.
     */
    private static final double GIVE_UP_DISTANCE_SQUARED = (128.0 + 40.0) * (128.0 + 40.0);

    /**
     * Mobs that come under the reason {@code NATURAL} but have spawners of
     * their own, which do not keep to 24 and 128 blocks: phantoms over a
     * player who has not slept, cats in villages and swamp huts, the
     * wandering trader and his llamas.
     */
    private static final Set<EntityType> OWN_SPAWNERS = EnumSet.of(EntityType.PHANTOM, EntityType.CAT,
            EntityType.WANDERING_TRADER, EntityType.TRADER_LLAMA);

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    /** Paper's {@code Player.getAffectsSpawning}, or {@code null} on Spigot. */
    private final Method affectsSpawning;
    /** Reused for every position asked for, the checks run often. */
    private final Location scratch = new Location(null, 0, 0, 0);

    public CamSpawnFilter(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
        Method method;
        try {
            method = Player.class.getMethod(PAPER_AFFECTS_SPAWNING);
        } catch (NoSuchMethodException ex) {
            method = null;
        }
        affectsSpawning = method;
    }

    /**
     * Hands the checks to the server: the spawners always, natural spawning
     * on the event the server offers.
     */
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!registerPaperEvent()) {
            plugin.getServer().getPluginManager().registerEvents(new BukkitNaturalSpawn(), plugin);
        }
    }

    /**
     * Listens to Paper's event before a mob is made, where the server has it.
     *
     * @return whether it is there
     */
    private boolean registerPaperEvent() {
        Class<? extends Event> preSpawn;
        Method location;
        Method type;
        Method reason;
        Method giveUp;
        try {
            preSpawn = Class.forName(PAPER_PRE_SPAWN).asSubclass(Event.class);
            location = preSpawn.getMethod("getSpawnLocation");
            type = preSpawn.getMethod("getType");
            reason = preSpawn.getMethod("getReason");
            giveUp = preSpawn.getMethod("setShouldAbortSpawn", boolean.class);
        } catch (ClassNotFoundException | NoSuchMethodException | ClassCastException ex) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvent(preSpawn, new Listener() {
        }, EventPriority.NORMAL, (listener, event) -> {
            // The events of phantoms and spawners share this list of
            // listeners, so the server hands those in here as well.
            if (!preSpawn.isInstance(event) || !applies()) {
                return;
            }
            try {
                if (reason.invoke(event) != SpawnReason.NATURAL
                        || OWN_SPAWNERS.contains((EntityType) type.invoke(event))) {
                    return;
                }
                double nearest = nearestSquared((Location) location.invoke(event), false);
                if (!naturalAllowed(nearest)) {
                    ((Cancellable) event).setCancelled(true);
                    if (nearest > GIVE_UP_DISTANCE_SQUARED) {
                        giveUp.invoke(event, true);
                    }
                }
            } catch (ReflectiveOperationException ex) {
                throw new EventException(ex);
            }
        }, plugin, true);
        return true;
    }

    /** A spawner runs only with somebody who counts within its range. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        CreatureSpawner spawner = event.getSpawner();
        // Without a block - a spawner in a minecart - there is no range to ask.
        if (spawner == null || !applies()) {
            return;
        }
        Location center = spawner.getLocation().add(0.5, 0.5, 0.5);
        double range = spawner.getRequiredPlayerRange();
        double nearest = nearestSquared(center, true);
        // A range below 0 is the game's "anywhere in the world".
        boolean inRange = range < 0 ? nearest < Double.POSITIVE_INFINITY : nearest < range * range;
        if (!inRange) {
            event.setCancelled(true);
        }
    }

    /** Whether anything is to be checked at all: not as {@code camera}, and not with nobody in camera mode. */
    private boolean applies() {
        return settings.getSpawnMode() != SpawnMode.CAMERA && !cameraPlayers.isEmpty();
    }

    /** Whether a mob of natural spawning may come out with the nearest player this far away, squared. */
    private static boolean naturalAllowed(double nearestSquared) {
        return nearestSquared > MIN_DISTANCE_SQUARED && nearestSquared <= MAX_DISTANCE_SQUARED;
    }

    /**
     * How far it is from here to the nearest player who counts, squared, or
     * infinity when nobody in this world does: everybody out of camera mode
     * who is no spectator, and with {@code body} the bodies of those in it.
     *
     * @param aliveOnly whether only the living count, as for a spawner
     */
    private double nearestSquared(Location at, boolean aliveOnly) {
        World world = at.getWorld();
        double nearest = Double.POSITIVE_INFINITY;
        if (world == null) {
            return nearest;
        }
        for (Player player : world.getPlayers()) {
            if (cameraPlayers.contains(player.getUniqueId()) || !counts(player)
                    || (aliveOnly && player.isDead())) {
                continue;
            }
            nearest = Math.min(nearest, player.getLocation(scratch).distanceSquared(at));
        }
        if (settings.getSpawnMode() != SpawnMode.BODY) {
            return nearest;
        }
        for (UUID id : cameraPlayers.ids()) {
            CameraData data = cameraPlayers.get(id);
            LivingEntity body = data == null ? null : data.getBody();
            if (body == null || !body.isValid() || !world.equals(body.getWorld())) {
                continue;
            }
            nearest = Math.min(nearest, body.getLocation(scratch).distanceSquared(at));
        }
        return nearest;
    }

    /**
     * Whether a player out of camera mode counts for spawning, as he would for
     * the game: no spectator, and on Paper not one a vanish plugin took out of
     * it.
     */
    private boolean counts(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (affectsSpawning == null) {
            return true;
        }
        try {
            return (Boolean) affectsSpawning.invoke(player);
        } catch (ReflectiveOperationException ex) {
            return true;
        }
    }

    /**
     * Bukkit's event, for a server without Paper's: after the mob is made. A
     * class of its own, so a Paper server never registers it.
     */
    private final class BukkitNaturalSpawn implements Listener {

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onCreatureSpawn(CreatureSpawnEvent event) {
            if (event.getSpawnReason() != SpawnReason.NATURAL || OWN_SPAWNERS.contains(event.getEntityType())
                    || !applies()) {
                return;
            }
            if (!naturalAllowed(nearestSquared(event.getLocation(), false))) {
                event.setCancelled(true);
            }
        }
    }
}
