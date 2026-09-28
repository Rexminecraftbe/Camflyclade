package de.elia.cameraplugin.sulfurcube;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the camera player from pushing sulfur cubes around.
 *
 * <p>A sulfur cube that has swallowed a block rolls off whenever a player
 * walks into it. That push is no collision - camera mode switches collisions
 * off, see {@link de.elia.cameraplugin.scoreboard.NoCollisionTeam}, and the
 * cube is pushed all the same. The game hands it out to every player touching
 * the cube, in the player's own tick, and no event comes with it that could
 * be cancelled.</p>
 *
 * <p>What the push does ask is how firmly the cube stands: its knockback
 * resistance. A cube is therefore made to stand firm for as long as a camera
 * is close enough to push it, and let go again once no camera is. The other
 * way to move a cube, hitting it, is turned away by
 * {@link de.elia.cameraplugin.interaction.CamKnockbackGuard}.</p>
 *
 * <p>For those few ticks the cube does not roll for anybody else either: a
 * player kicking it while a camera sits right inside it kicks in vain. That
 * is the price of the one handle the game offers, and why the reach is kept
 * as tight as the game's own.</p>
 *
 * <p>The resistance is written onto the cube, so it is saved with it. It is
 * taken off before a chunk goes to disk, and anything a crash left behind
 * comes off again when the cube is loaded.</p>
 */
public final class CamSulfurCubeGuard implements Listener {

    /**
     * How close a player has to come to a cube to push it: the distance
     * between the two, measured flat, from centre to centre. The game's own
     * value.
     */
    private static final double PUSH_DISTANCE = 1.3;
    /**
     * Added on top of the reach, in blocks. The cube is looked at before the
     * entities move and is pushed afterwards, so it has to be caught a little
     * early rather than a little late.
     */
    private static final double MARGIN = 0.5;
    /** The box around the camera that is searched for cubes, in blocks. */
    private static final double SEARCH = 8.0;
    /**
     * What the cube is given on top of its own resistance. Far more than any
     * block it swallows takes away - the bounciest go down to minus two - and
     * the game caps the result at a full one, which no push gets through.
     */
    private static final double FIRM = 1024.0;
    /**
     * Ticks between two looks. The lowest there is: the game pushes the cube
     * in every tick a camera touches it.
     */
    private static final long INTERVAL = 1L;

    private final JavaPlugin plugin;
    private final AttributeModifier firm;
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();
    /** The cubes each camera player holds firm right now, by player id. */
    private final Map<UUID, Set<SulfurCube>> held = new HashMap<>();

    public CamSulfurCubeGuard(JavaPlugin plugin) {
        this.plugin = plugin;
        this.firm = new AttributeModifier(new NamespacedKey(plugin, "cam_no_push"), FIRM,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
    }

    /** Starts watching the cubes around this camera player. */
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
                watch(player);
            }
        };
        task.runTaskTimer(plugin, 0L, INTERVAL);
        tasks.put(player.getUniqueId(), task);
    }

    /** Stops watching and lets go of the cubes this camera held, when camera mode ends. */
    public void stopFor(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        Set<SulfurCube> before = held.remove(player.getUniqueId());
        if (before != null) {
            before.forEach(this::letGoUnlessHeld);
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        for (Set<SulfurCube> cubes : held.values()) {
            cubes.forEach(this::letGo);
        }
        held.clear();
    }

    /**
     * Takes the resistance off every cube that is loaded right now.
     *
     * <p>Called at the start: when the server went down without the plugin
     * shutting down first, a cube may still carry it. Cubes that are loaded
     * later are seen to by {@link #onEntitiesLoad}.</p>
     */
    public void releaseLeftovers() {
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntitiesByClass(SulfurCube.class).forEach(this::letGo);
        }
    }

    /**
     * Holds firm what this camera could push in the coming tick, and lets go
     * of what it can no longer reach - unless another camera still holds it.
     *
     * <p>A cube in reach is held firm again every tick, not only when it comes
     * into reach: loading its chunk takes the resistance off, see
     * {@link #onEntitiesLoad}, and a cube is sometimes loaded right next to a
     * camera.</p>
     */
    private void watch(Player player) {
        Set<SulfurCube> now = cubesInReach(player);
        Set<SulfurCube> before = held.put(player.getUniqueId(), now);
        now.forEach(this::standFirm);
        if (before != null) {
            for (SulfurCube cube : before) {
                if (!now.contains(cube)) {
                    letGoUnlessHeld(cube);
                }
            }
        }
    }

    private Set<SulfurCube> cubesInReach(Player player) {
        Set<SulfurCube> inReach = new HashSet<>();
        Location camera = player.getLocation();
        World world = camera.getWorld();
        if (world == null) {
            return inReach;
        }
        for (Entity entity : world.getNearbyEntities(camera, SEARCH, SEARCH, SEARCH,
                nearby -> nearby instanceof SulfurCube)) {
            SulfurCube cube = (SulfurCube) entity;
            if (canPush(player, camera, cube)) {
                inReach.add(cube);
            }
        }
        return inReach;
    }

    /**
     * Whether the camera could push this cube in the coming tick. The game's
     * own rule: less than {@link #PUSH_DISTANCE} apart, measured flat, and
     * overlapping in height.
     *
     * <p>Asked now, before the entities move, while the push comes after
     * them. The camera stays where it is until then - it is only moved by
     * what its client sends, and that arrives between two ticks - but the
     * cube can travel as far as it is flying. That distance goes on top.</p>
     */
    private boolean canPush(Player player, Location camera, SulfurCube cube) {
        Location at = cube.getLocation();
        double slack = cube.getVelocity().length() + MARGIN;
        double reach = PUSH_DISTANCE + slack;
        double dx = at.getX() - camera.getX();
        double dz = at.getZ() - camera.getZ();
        if (dx * dx + dz * dz >= reach * reach) {
            return false;
        }
        double feet = camera.getY();
        return feet <= at.getY() + cube.getHeight() + slack
                && feet + player.getHeight() > at.getY() - slack;
    }

    private void standFirm(SulfurCube cube) {
        AttributeInstance resistance = cube.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (resistance == null) {
            return;
        }
        // Taken off first: the server refuses the same modifier twice.
        resistance.removeModifier(firm);
        resistance.addModifier(firm);
    }

    private void letGo(SulfurCube cube) {
        AttributeInstance resistance = cube.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (resistance != null) {
            resistance.removeModifier(firm);
        }
    }

    /** Lets go of the cube, as long as no other camera holds it any more. */
    private void letGoUnlessHeld(SulfurCube cube) {
        for (Set<SulfurCube> cubes : held.values()) {
            if (cubes.contains(cube)) {
                return;
            }
        }
        letGo(cube);
    }

    /** Takes the resistance off before the cube is saved with its chunk. */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof SulfurCube cube) {
                letGo(cube);
            }
        }
    }

    /** Takes off whatever a crash left on a cube that is loaded again. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof SulfurCube cube) {
                letGo(cube);
            }
        }
    }
}
