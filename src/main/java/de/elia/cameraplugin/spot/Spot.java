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
 * @param look    the time, weather and night vision its owner had picked for
 *                himself when he saved it, see {@link SpotLook}
 */
public record Spot(String name, String world, double x, double y, double z, float yaw, float pitch,
                   long created, SpotLook look) {

    public Spot {
        if (look == null) {
            look = SpotLook.NONE;
        }
    }

    /**
     * The spot of a location, under a name, with a look of its own, first
     * saved at a moment before: a spot that is moved keeps its date.
     */
    public static Spot at(String name, Location where, long created, SpotLook look) {
        return new Spot(name, where.getWorld() == null ? "" : where.getWorld().getName(),
                where.getX(), where.getY(), where.getZ(), where.getYaw(), where.getPitch(), created, look);
    }

    /** The most characters a name of a spot has; the least is one. */
    public static final int MAX_NAME = 16;

    /**
     * A name as it is typed, made into the name of a spot: without spaces
     * at its ends and with one space wherever there were more - the chat of
     * Java joins them anyway, so a name with two of them could not be typed
     * again. Any sign goes; only the length counts: one to
     * {@link #MAX_NAME} characters.
     *
     * @return the name, or {@code null} when it is empty or too long
     */
    public static String validName(String typed) {
        String name = clean(typed);
        int length = name.codePointCount(0, name.length());
        return length < 1 || length > MAX_NAME ? null : name;
    }

    /** Without spaces at the ends, and one space wherever there were more. */
    private static String clean(String typed) {
        return typed.trim().replaceAll("\\s+", " ");
    }

    /** What the spot is found by: its name, capitals not counting. */
    public String key() {
        return key(name);
    }

    /** What a spot of this name is found by: capitals and extra spaces not counting. */
    public static String key(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
    }

    /**
     * A key as one part of a path of a YAML file, where a {@code .} would
     * start a section of its own: written as {@code %2E}, and a {@code %}
     * as {@code %25}. A key of the time names had neither stays as it was.
     */
    public static String pathKey(String key) {
        return key.replace("%", "%25").replace(".", "%2E");
    }

    /** The key a part of a path stands for, see {@link #pathKey}. */
    public static String fromPathKey(String part) {
        StringBuilder key = new StringBuilder(part.length());
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '%' && part.regionMatches(true, i + 1, "2E", 0, 2)) {
                key.append('.');
                i += 2;
            } else if (c == '%' && part.startsWith("25", i + 1)) {
                key.append('%');
                i += 2;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }

    /** Where the spot is, looking its way, or {@code null} while its world is not there. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }
}
