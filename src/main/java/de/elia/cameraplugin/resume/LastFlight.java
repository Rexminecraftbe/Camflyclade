package de.elia.cameraplugin.resume;

import de.elia.cameraplugin.spot.SpotLook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Where the last flight of a player ended, for {@code /cam resume}: the spot
 * the camera hung at, which way it looked, and the time of day, the weather
 * and the night vision it had then - kept as a spot keeps them, see
 * {@link SpotLook}.
 *
 * <p>The world is kept by its name, as {@link de.elia.cameraplugin.spot.Spot}
 * keeps it.</p>
 *
 * @param look the time, weather and night vision of the flight, never {@code null}
 */
public record LastFlight(String world, double x, double y, double z, float yaw, float pitch, SpotLook look) {

    public LastFlight {
        if (look == null) {
            look = SpotLook.NONE;
        }
    }

    /** Where a flight ended, with what it showed. */
    public static LastFlight at(Location where, SpotLook look) {
        return new LastFlight(where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch(), look);
    }

    /** Where it ended, looking its way, or {@code null} while its world is not there. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
