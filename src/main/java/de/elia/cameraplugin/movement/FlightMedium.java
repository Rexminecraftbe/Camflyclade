package de.elia.cameraplugin.movement;

import de.elia.cameraplugin.config.CamSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a camera player can fly into besides air and be kept out of: water and
 * powder snow, each behind a switch of its own under {@code camera-mode}.
 *
 * <p>Lava is not one of them. Its switch does not stop the camera at the edge
 * but ends camera mode, see {@link CamMovementGuard}.</p>
 *
 * <p>The whole body counts, from the feet to the top of the head, and not only
 * the block the feet are in: flying up against a ceiling of powder snow, the
 * head gets there first - and a single layer of it is thin enough for the eyes
 * to come out above it before the feet have even reached it.</p>
 */
public enum FlightMedium {
    /**
     * Water, {@code camera-mode.allow_water_flight}. The water standing in a
     * waterlogged block counts as well, and so does the water around kelp,
     * seagrass and in a bubble column: the server holds a player in any of
     * them to be in water.
     */
    WATER("cant-fly-in-water", "cam-water-start"),
    /**
     * Powder snow, {@code camera-mode.allow_powder_snow_flight}. Only leather
     * boots carry a player over it, and camera mode takes the armour off - the
     * camera sinks straight into it.
     */
    POWDER_SNOW("cant-fly-in-powder-snow", "cam-powder-snow-start");

    /** Blocks that always stand in water, whatever their block data says. */
    private static final Set<Material> ALWAYS_IN_WATER = Set.of(Material.WATER,
            Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);

    /**
     * How far the body is drawn in on every side before its blocks are looked
     * up, in blocks. Without it a block the body only touches from outside
     * would count as reached into: the ground it stands on, or the water it
     * skims along.
     */
    private static final double TOUCH_MARGIN = 1.0E-3;

    private final String flightMessage;
    private final String startMessage;

    FlightMedium(String flightMessage, String startMessage) {
        this.flightMessage = flightMessage;
        this.startMessage = startMessage;
    }

    /** The message for a step that is stopped at the edge. */
    public String getFlightMessage() {
        return flightMessage;
    }

    /** The message for a start that is refused because the player is in it. */
    public String getStartMessage() {
        return startMessage;
    }

    /** Whether its switch keeps camera mode out of it. */
    public boolean isClosed(CamSettings settings) {
        return switch (this) {
            case WATER -> !settings.allowsWaterFlight();
            case POWDER_SNOW -> !settings.allowsPowderSnowFlight();
        };
    }

    /** Whether this block is filled with it. */
    public boolean fills(Block block) {
        Material type = block.getType();
        return switch (this) {
            // Luft zuerst aussortiert: Um eine Kamera herum ist fast alles
            // Luft, und getBlockData legt fuer jeden Block ein neues Objekt an.
            case WATER -> ALWAYS_IN_WATER.contains(type) || (!type.isAir()
                    && block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged());
            case POWDER_SNOW -> type == Material.POWDER_SNOW;
        };
    }

    /**
     * What the body of the player reaches into at {@code to} that camera mode
     * keeps it out of - counting only the blocks it does not reach into at
     * {@code from} already.
     *
     * <p>That leaves a way out to a camera that is in there already, because
     * the water ran over it or a command put it there: it may move about in
     * the blocks it is in and out of them, just not into a further one.</p>
     *
     * @param from where the step starts, or {@code null} when every block
     *             counts, as it does at the start of camera mode
     * @return what was found, water before powder snow, or {@code null} when
     *         the body stays clear of everything that is shut
     */
    public static FlightMedium reachedInto(Player player, CamSettings settings, Location from, Location to) {
        Set<FlightMedium> closed = EnumSet.noneOf(FlightMedium.class);
        for (FlightMedium medium : values()) {
            if (medium.isClosed(settings)) {
                closed.add(medium);
            }
        }
        World world = to.getWorld();
        if (closed.isEmpty() || world == null) {
            return null;
        }
        BodySpan target = BodySpan.of(player, to);
        BodySpan already = from != null && world.equals(from.getWorld()) ? BodySpan.of(player, from) : null;
        for (int x = target.minX(); x <= target.maxX(); x++) {
            for (int y = target.minY(); y <= target.maxY(); y++) {
                for (int z = target.minZ(); z <= target.maxZ(); z++) {
                    if (already != null && already.contains(x, y, z)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    for (FlightMedium medium : closed) {
                        if (medium.fills(block)) {
                            return medium;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The blocks the body of a player reaches into at one spot, as the
     * smallest and the largest block coordinate on each axis.
     */
    private record BodySpan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /**
         * Measured with the size the player has right now - in flight the full
         * height, as nobody flying crouches or swims.
         */
        static BodySpan of(Player player, Location where) {
            double halfWidth = player.getWidth() / 2.0 - TOUCH_MARGIN;
            double top = where.getY() + player.getHeight() - TOUCH_MARGIN;
            return new BodySpan(
                    Location.locToBlock(where.getX() - halfWidth),
                    Location.locToBlock(where.getY() + TOUCH_MARGIN),
                    Location.locToBlock(where.getZ() - halfWidth),
                    Location.locToBlock(where.getX() + halfWidth),
                    Location.locToBlock(top),
                    Location.locToBlock(where.getZ() + halfWidth));
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
}
