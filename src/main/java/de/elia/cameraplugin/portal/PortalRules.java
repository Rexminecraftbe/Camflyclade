package de.elia.cameraplugin.portal;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a portal lets a camera player through at all: the section
 * {@code portals} of the config file.
 *
 * <p>Both portals are shut out of the box. Camera mode leaves a body behind,
 * and everything it is measured by sits with that body: how far the player may
 * get away from it and, once he is back beside it, where camera mode ends. A
 * portal takes him into a world his body is not in, so it is opened only where
 * that is wanted.</p>
 *
 * <p>This class only says what the config file says. Whether a portal is shut
 * for a second reason - {@code cam-area} forbidding the dimension behind it -
 * and what happens on the other side is decided by the plugin, which has the
 * body and the area rules at hand.</p>
 *
 * <p>The one thing it does remember is which portals have already taken a
 * camera player into a biome or a structure he is not allowed in, see
 * {@link #rememberForbiddenArea(Location, String, Location)}. Where a portal
 * comes out cannot be asked beforehand - it has to be walked through once - so
 * without that the same portal would send him over and fetch him back every
 * single time.</p>
 *
 * <p>What is remembered is not taken on trust, as long as
 * {@code portals.forget-changed} is on. A portal leads where the world lets it
 * lead, and the world is built on: an entry is looked over before it turns
 * anybody away, see {@link #forbiddenAreaBehind(Location, AreaCheck)}, and a
 * portal newly built next to the far side of a remembered one lets that entry
 * go, see {@link #forgetPortalsNear(World, Collection)}. Switched off, an entry
 * stands until {@code /cam reload}.</p>
 */
public final class PortalRules {

    /** How long the message at a shut portal waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    /** How many portals with a forbidden far side are remembered by default. */
    private static final int DEFAULT_REMEMBERED_PORTALS = 128;

    /**
     * How close a newly built portal has to be to the far side of a remembered
     * one to let that entry go, in blocks. The default is the radius the server
     * looks for an existing portal in.
     */
    private static final int DEFAULT_FORGET_RADIUS = 128;

    /**
     * How far around the spot a trip came out at a portal block is looked for,
     * in blocks. Whoever comes out of a portal is set down in it, but not
     * always in its very middle.
     */
    private static final int ARRIVAL_PORTAL_REACH = 2;

    /**
     * How far the walk to the corner of a portal goes at most, in blocks. A
     * portal built the usual way is nowhere near that wide; the limit is there
     * so that a field of portal blocks somebody filled in by hand cannot send
     * the walk off across the world.
     */
    private static final int MAX_PORTAL_WALK = 32;

    private final Set<PortalKind> open = EnumSet.noneOf(PortalKind.class);
    private PortalReturn returnTo = PortalReturn.BODY;
    private int warningCooldown = DEFAULT_WARNING_COOLDOWN;
    private boolean rememberBlocked = true;
    private int rememberedPortals = DEFAULT_REMEMBERED_PORTALS;
    private boolean forgetChanged = true;
    private int forgetRadius = DEFAULT_FORGET_RADIUS;

    /**
     * The portals that have already come out in a biome or a structure camera
     * mode is not allowed in, each with the name of that area and the spot the
     * trip came out at, the least recently used one giving way first once
     * {@code remember-blocked-count} is reached.
     *
     * <p>Only kept for as long as the server runs and emptied by
     * {@code /cam reload}. Single entries are given up sooner than that
     * wherever the world says they are out of date: the portal over there taken
     * down, the area over there gone, or a portal newly built close enough to
     * take the trip instead.</p>
     */
    private final Map<PortalSpot, BlockedPortal> blockedPortals =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PortalSpot, BlockedPortal> eldest) {
                    return size() > rememberedPortals;
                }
            };

    /** One portal, taken by the lowest of its blocks. */
    private record PortalSpot(UUID world, int x, int y, int z) {
    }

    /**
     * The spot a trip through a portal came out at, kept as plain coordinates
     * and not as a {@link Location}: a location holds on to its world only
     * weakly and starts throwing once that world is gone, and an entry here can
     * well outlive a world.
     *
     * @param inPortal whether a portal block stood here when this was written
     *                 down. The end has none - whoever arrives there is set
     *                 down on the obsidian platform - so a missing one only
     *                 means something where there was one to begin with.
     */
    private record Arrival(UUID world, double x, double y, double z, boolean inPortal) {

        /** The spot as a location, or {@code null} when its world is not loaded. */
        Location location() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z);
        }
    }

    /** One portal with a forbidden far side: the name of that area and where it is. */
    private record BlockedPortal(String area, Arrival arrival) {
    }

    /**
     * What keeps camera mode out of a spot. Handed in by the plugin, which has
     * the area rules at hand; this class only asks.
     */
    @FunctionalInterface
    public interface AreaCheck {

        /**
         * @param where the spot to look at
         * @return the name of the biome or structure that forbids it, written
         *         the way the config file writes it, or {@code null} when
         *         camera mode is allowed there
         */
        String areaAt(Location where);
    }

    /** Reads the whole section out of the config file. */
    public void load(ConfigReader config) {
        open.clear();
        for (PortalKind kind : PortalKind.values()) {
            if (config.getBoolean("portals." + kind.getConfigName(), false)) {
                open.add(kind);
            }
        }
        returnTo = PortalReturn.byName(config.getChoice("portals.return-to",
                PortalReturn.BODY.getConfigName(), PortalReturn.configNames()));
        warningCooldown = config.getInt("portals.warning-cooldown", DEFAULT_WARNING_COOLDOWN, 0);
        rememberBlocked = config.getBoolean("portals.remember-blocked", true);
        rememberedPortals = config.getInt("portals.remember-blocked-count",
                DEFAULT_REMEMBERED_PORTALS, 0);
        forgetChanged = config.getBoolean("portals.forget-changed", true);
        forgetRadius = config.getInt("portals.forget-radius", DEFAULT_FORGET_RADIUS, 0);
        blockedPortals.clear();
    }

    /**
     * The area behind this portal, when it has already taken a camera player
     * into a biome or a structure camera mode is not allowed in.
     *
     * <p>What was written down is looked over before it is used, unless
     * {@code portals.forget-changed} is off. Is the portal on the far side
     * gone, or is the spot over there not forbidden any more, the entry is
     * dropped and this portal is walked through once again - otherwise it would
     * keep turning players away from a trip that has long since stopped ending
     * where it did.</p>
     *
     * @param portal the spot the player is stepping into the portal at
     * @param check  what forbids camera mode at a spot, asked again at the spot
     *               the earlier trip came out at
     * @return the name of that area, written the way the config file writes it,
     *         or {@code null} when nothing is known about this portal
     */
    public String forbiddenAreaBehind(Location portal, AreaCheck check) {
        if (!rememberBlocked || blockedPortals.isEmpty()) {
            return null;
        }
        PortalSpot spot = spotOf(portal);
        BlockedPortal blocked = spot == null ? null : blockedPortals.get(spot);
        if (blocked == null) {
            return null;
        }
        if (!forgetChanged) {
            return blocked.area();
        }
        String area = areaStillBehind(blocked, check);
        if (area == null) {
            blockedPortals.remove(spot);
        } else if (!area.equals(blocked.area())) {
            // Drueben gilt jetzt etwas anderes als beim letzten Mal. Der Name
            // steht in der Meldung an den Spieler, also wird er nachgefuehrt.
            blockedPortals.put(spot, new BlockedPortal(area, blocked.arrival()));
        }
        return area;
    }

    /**
     * What forbids camera mode on the far side of a remembered portal now.
     *
     * @return the name of the area, or {@code null} when this portal is to be
     *         walked through once more
     */
    private static String areaStillBehind(BlockedPortal blocked, AreaCheck check) {
        Arrival arrival = blocked.arrival();
        Location where = arrival.location();
        if (where == null) {
            // Die Welt von drueben ist nicht geladen. Was dort gilt, laesst sich
            // nicht sagen, und eine Probereise kostet weniger als ein Portal,
            // das aus einem nicht mehr pruefbaren Grund gesperrt bleibt.
            return null;
        }
        if (arrival.inPortal() && !portalStandsAt(where)) {
            // Das Portal drueben ist abgebaut oder versetzt worden. Die Reise
            // kommt jetzt an einem anderen heraus - an dem naechstgelegenen,
            // oder an einem, das der Server neu setzt.
            return null;
        }
        return check.areaAt(where);
    }

    /**
     * Whether a portal block still stands where a trip came out.
     *
     * <p>Reading a block loads the chunk it sits in. That chunk was generated by
     * the trip this entry was written down on, so it is a load and not a
     * generation, and it happens once per attempt at a shut portal - the portal
     * cooldown the plugin puts on the player sees to that.</p>
     */
    private static boolean portalStandsAt(Location arrival) {
        World world = arrival.getWorld();
        if (world == null) {
            return false;
        }
        int x = arrival.getBlockX();
        int z = arrival.getBlockZ();
        int lowest = Math.max(world.getMinHeight(), arrival.getBlockY() - ARRIVAL_PORTAL_REACH);
        int highest = Math.min(world.getMaxHeight() - 1,
                arrival.getBlockY() + ARRIVAL_PORTAL_REACH);
        for (int blockY = lowest; blockY <= highest; blockY++) {
            for (int blockX = x - ARRIVAL_PORTAL_REACH; blockX <= x + ARRIVAL_PORTAL_REACH; blockX++) {
                for (int blockZ = z - ARRIVAL_PORTAL_REACH; blockZ <= z + ARRIVAL_PORTAL_REACH; blockZ++) {
                    if (isPortal(world.getBlockAt(blockX, blockY, blockZ).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether this is one of the two blocks a player travels through. */
    private static boolean isPortal(Material material) {
        return material == Material.NETHER_PORTAL || material == Material.END_PORTAL;
    }

    /**
     * Writes down that this portal comes out in an area camera mode is not
     * allowed in, so that the next trip through it is refused at the portal
     * instead of ending in a walk over and a teleport straight back.
     *
     * <p>The spot it came out at is written down with it. That is what makes
     * the entry worth anything later on: without it there would be no way of
     * telling whether the far side is still the far side.</p>
     *
     * @param portal  the spot the player stepped into the portal at
     * @param area    the name of the biome or structure on the other side
     * @param arrival the spot he came out at
     */
    public void rememberForbiddenArea(Location portal, String area, Location arrival) {
        if (!rememberBlocked || rememberedPortals <= 0) {
            return;
        }
        PortalSpot spot = spotOf(portal);
        World arrivedIn = arrival == null ? null : arrival.getWorld();
        if (spot == null || arrivedIn == null) {
            return;
        }
        blockedPortals.put(spot, new BlockedPortal(area,
                new Arrival(arrivedIn.getUID(), arrival.getX(), arrival.getY(), arrival.getZ(),
                        portalStandsAt(arrival))));
    }

    /**
     * Lets go of every remembered portal whose far side lies close enough to a
     * portal newly built here that the trip could come out at the new one
     * instead.
     *
     * <p>The server takes the portal nearest to where a trip lands, so one
     * built next to the far side of a remembered portal quietly takes its
     * place. Nothing about the portal the player steps into changes, and the
     * entry would go on refusing him a trip that no longer leads where it
     * did - until {@code /cam reload}, which is a heavy answer to a portal
     * somebody moved by two chunks.</p>
     *
     * <p>Hangs on {@code portals.forget-changed} like the checking does, and on
     * {@code portals.forget-radius} of its own, which switches off this half
     * alone.</p>
     *
     * @param world  the world the new portal stands in
     * @param blocks the blocks it is made of
     */
    public void forgetPortalsNear(World world, Collection<BlockState> blocks) {
        if (!forgetChanged || forgetRadius <= 0 || blockedPortals.isEmpty() || world == null
                || blocks == null || blocks.isEmpty()) {
            return;
        }
        UUID id = world.getUID();
        blockedPortals.values().removeIf(blocked ->
                blocked.arrival().world().equals(id) && reaches(blocks, blocked.arrival()));
    }

    /**
     * Whether one of these blocks stands within {@code portals.forget-radius}
     * of the far side, measured the way the server measures its search for a
     * portal: a square around the spot the trip lands at, height left out of
     * it.
     */
    private boolean reaches(Collection<BlockState> blocks, Arrival arrival) {
        int x = (int) Math.floor(arrival.x());
        int z = (int) Math.floor(arrival.z());
        for (BlockState block : blocks) {
            if (Math.abs(block.getX() - x) <= forgetRadius
                    && Math.abs(block.getZ() - z) <= forgetRadius) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block a portal is remembered by: the lowest of its blocks, found by
     * walking from the one the player stands in - first along x, then y, then
     * z. Every block of the same portal walks down to the same one, so a portal
     * is remembered once instead of once per block somebody happens to step
     * into.
     *
     * <p>A spot that is not a portal block at all is taken as it is. That is
     * not supposed to happen - the server only sends somebody through a portal
     * he is standing in - and one block of a wide portal remembered on its own
     * is a smaller mistake than remembering nothing.</p>
     */
    private static PortalSpot spotOf(Location portal) {
        if (portal == null || portal.getWorld() == null) {
            return null;
        }
        Block block = portal.getBlock();
        Material material = block.getType();
        if (material == Material.NETHER_PORTAL || material == Material.END_PORTAL) {
            block = walk(block, BlockFace.WEST, material);
            block = walk(block, BlockFace.DOWN, material);
            block = walk(block, BlockFace.NORTH, material);
        }
        return new PortalSpot(portal.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ());
    }

    /** The last block of the portal in this direction, see {@link #MAX_PORTAL_WALK}. */
    private static Block walk(Block from, BlockFace direction, Material material) {
        Block block = from;
        for (int step = 0; step < MAX_PORTAL_WALK; step++) {
            Block next = block.getRelative(direction);
            if (next.getType() != material) {
                return block;
            }
            block = next;
        }
        return block;
    }

    /** Whether this kind of portal lets a camera player through. */
    public boolean letsThrough(PortalKind kind) {
        return open.contains(kind);
    }

    /** Where a player who has to be brought back is put down. */
    public PortalReturn getReturnTo() {
        return returnTo;
    }

    /** How long the message at a shut portal waits before it is sent again, in seconds. */
    public int getWarningCooldown() {
        return warningCooldown;
    }
}
