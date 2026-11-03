package de.elia.cameraplugin.resume;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Where the last flight of a player ended, for {@code /cam resume}: the spot
 * the camera hung at and which way it looked.
 *
 * <p>The world is kept by its name, as {@link de.elia.cameraplugin.spot.Spot}
 * keeps it.</p>
 */
public record LastFlight(String world, double x, double y, double z, float yaw, float pitch) {

    /** Where a flight ended. */
    public static LastFlight at(Location where) {
        return new LastFlight(where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch());
    }

    /** Where it ended, looking its way, or {@code null} while its world is not there. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
