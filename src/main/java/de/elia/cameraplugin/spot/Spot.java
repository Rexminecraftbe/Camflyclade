package de.elia.cameraplugin.spot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/**
 * One spot of {@code /cam spot}: where a player stood or his camera hung, and
 * which way he looked, under the name he gave it.
 *
 * <p>The world is kept by its name, as the folder of the world is called: a
 * world that is gone - deleted, or not loaded - leaves the spot in place
 * without a way to get there.</p>
 *
 * @param created when the spot was first saved, as {@link System#currentTimeMillis()}
 *                counts, for sorting by it; 0 for a spot saved before that was
 *                kept, whose date nobody knows
 */
public record Spot(String name, String world, double x, double y, double z, float yaw, float pitch,
                   long created) {

    /** The spot of a location, under a name, saved now. */
    public static Spot at(String name, Location where) {
        return at(name, where, System.currentTimeMillis());
    }

    /** The spot of a location, under a name, first saved at a moment before: a spot that is moved keeps its date. */
    public static Spot at(String name, Location where, long created) {
        return new Spot(name, where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch(), created);
    }

    /** What the spot is found by: its name, capitals not counting. */
    public String key() {
        return key(name);
    }

    /** What a spot of this name is found by. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Where the spot is, looking its way, or {@code null} while its world is not there. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
