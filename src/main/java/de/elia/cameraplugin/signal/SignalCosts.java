package de.elia.cameraplugin.signal;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What each block on its way costs the radio signal, in blocks of range:
 * {@code signal.block-cost} for every full block that cannot be seen
 * through, and {@code signal.block-costs} for the blocks that cost something
 * else - more, less, or all of it, {@code stop}.
 *
 * <p>A name in {@code block-costs} may hold a {@code *} for any letters, so
 * that one line takes in a whole family of blocks, such as {@code *copper}
 * for the copper blocks in all their stages of rust. A name without one comes
 * before every name with one; of two names with one, the later line wins.</p>
 */
public final class SignalCosts {

    /** What a block costs that lets no signal through at all, {@code stop}. */
    public static final double STOP = Double.POSITIVE_INFINITY;

    /** The word of {@code block-costs} for {@link #STOP}. */
    public static final String STOP_WORD = "stop";

    /** What a full block that cannot be seen through costs, {@code block-cost}. */
    private final double occluding;
    /** The blocks of {@code block-costs}, by block. */
    private final Map<Material, Double> listed;

    private SignalCosts(double occluding, Map<Material, Double> listed) {
        this.occluding = occluding;
        this.listed = listed;
    }

    /**
     * Reads {@code signal.block-cost} and {@code signal.block-costs}. A name
     * that is no block, or a name with {@code *} that fits none, is reported
     * and left out.
     *
     * @param allowed what the note on a value that is neither a number nor
     *                {@code stop} names as allowed
     */
    public static SignalCosts read(ConfigReader config, String allowed) {
        double occluding = config.getDouble("signal.block-cost", 5.0, 0.0);
        String path = "signal.block-costs";
        Map<String, Double> written = config.getCosts(path, STOP_WORD, allowed, "signal.block-cost");
        Map<Material, Double> listed = new EnumMap<>(Material.class);
        // The names with * first, so that a name without one is laid over them.
        for (boolean exact : new boolean[]{false, true}) {
            for (Map.Entry<String, Double> entry : written.entrySet()) {
                String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (name.startsWith("minecraft:")) {
                    name = name.substring("minecraft:".length());
                }
                if (name.contains("*") == exact) {
                    continue;
                }
                if (!exact) {
                    Pattern pattern = glob(name);
                    boolean any = false;
                    for (Material material : Material.values()) {
                        if (isBlock(material) && pattern.matcher(material.getKey().getKey()).matches()) {
                            listed.put(material, entry.getValue());
                            any = true;
                        }
                    }
                    if (!any) {
                        config.warnUnknownEntry(path, entry.getKey());
                    }
                    continue;
                }
                Material material = Material.matchMaterial(name);
                if (material == null || !isBlock(material)) {
                    config.warnUnknownEntry(path, entry.getKey());
                } else {
                    listed.put(material, entry.getValue());
                }
            }
        }
        return new SignalCosts(occluding, listed);
    }

    /** What one block of this kind costs the signal, {@link #STOP} for all of it. */
    public double of(Material type) {
        Double cost = listed.get(type);
        if (cost != null) {
            return cost;
        }
        return type.isOccluding() ? occluding : 0.0;
    }

    /** Whether no block costs anything, so that the way need not be looked at at all. */
    boolean free() {
        if (occluding > 0.0) {
            return false;
        }
        for (double cost : listed.values()) {
            if (cost > 0.0) {
                return false;
            }
        }
        return true;
    }

    /** {@code signal.block-cost}, what a full block that cannot be seen through costs. */
    public double occluding() {
        return occluding;
    }

    /** Whether this is a block of today's game: the old names from before 1.13 are none. */
    private static boolean isBlock(Material material) {
        return material.isBlock() && !material.name().startsWith("LEGACY_");
    }

    /** A name with {@code *} as a pattern, every other letter standing for itself. */
    private static Pattern glob(String name) {
        StringBuilder regex = new StringBuilder();
        for (String part : name.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
