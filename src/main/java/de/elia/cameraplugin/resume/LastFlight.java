package de.elia.cameraplugin.resume;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Where the last flight of a player ended, for {@code /cam resume}: the spot
 * the camera hung at and which way it looked, and the shared spot he was
 * visiting then, if he was.
 *
 * <p>The world is kept by its name, as {@link de.elia.cameraplugin.spot.Spot}
 * keeps it.</p>
 *
 * @param visitOwner whose shared spot he was visiting, or {@code null} for no
 *                   visit
 * @param visitSpot  the key of that spot, or {@code null}
 */
public record LastFlight(String world, double x, double y, double z, float yaw, float pitch,
                         UUID visitOwner, String visitSpot) {

    /** Where a flight ended, and the visit it was on. */
    public static LastFlight at(Location where, UUID visitOwner, String visitSpot) {
        return new LastFlight(where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch(),
                visitOwner, visitOwner == null ? null : visitSpot);
    }

    /** Whether the flight was a visit to a shared spot. */
    public boolean wasVisit() {
        return visitOwner != null && visitSpot != null;
    }

    /** Where it ended, looking its way, or {@code null} while its world is not there. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
