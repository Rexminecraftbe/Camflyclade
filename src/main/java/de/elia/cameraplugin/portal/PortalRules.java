package de.elia.cameraplugin.portal;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

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
 * {@link #rememberForbiddenArea(Location, String)}. Where a portal comes out
 * cannot be asked beforehand - it has to be walked through once - so without
 * that the same portal would send him over and fetch him back every single
 * time.</p>
 */
public final class PortalRules {

    /** How long the message at a shut portal waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    /** How many portals with a forbidden far side are remembered by default. */
    private static final int DEFAULT_REMEMBERED_PORTALS = 128;

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

    /**
     * The portals that have already come out in a biome or a structure camera
     * mode is not allowed in, each with the name of that area, the least
     * recently used one giving way first once {@code remember-blocked-count}
     * is reached.
     *
     * <p>Only kept for as long as the server runs and emptied by
     * {@code /cam reload}: a portal leads where the world says it leads, and
     * that can change - a new portal built closer by, the one over there broken
     * off. After a reload every portal is walked through once more.</p>
     */
    private final Map<PortalSpot, String> blockedPortals =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PortalSpot, String> eldest) {
                    return size() > rememberedPortals;
                }
            };

    /** One portal, taken by the lowest of its blocks. */
    private record PortalSpot(UUID world, int x, int y, int z) {
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
        blockedPortals.clear();
    }

    /**
     * The area behind this portal, when it has already taken a camera player
     * into a biome or a structure camera mode is not allowed in.
     *
     * @param portal the spot the player is stepping into the portal at
     * @return the name of that area, written the way the config file writes it,
     *         or {@code null} when nothing is known about this portal
     */
    public String forbiddenAreaBehind(Location portal) {
        if (!rememberBlocked || blockedPortals.isEmpty()) {
            return null;
        }
        PortalSpot spot = spotOf(portal);
        return spot == null ? null : blockedPortals.get(spot);
    }

    /**
     * Writes down that this portal comes out in an area camera mode is not
     * allowed in, so that the next trip through it is refused at the portal
     * instead of ending in a walk over and a teleport straight back.
     *
     * @param portal the spot the player stepped into the portal at
     * @param area   the name of the biome or structure on the other side
     */
    public void rememberForbiddenArea(Location portal, String area) {
        if (!rememberBlocked || rememberedPortals <= 0) {
            return;
        }
        PortalSpot spot = spotOf(portal);
        if (spot != null) {
            blockedPortals.put(spot, area);
        }
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
