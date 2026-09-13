package de.elia.cameraplugin.area;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where camera mode is allowed at all: the section {@code cam-area} of the
 * config file.
 *
 * <p>Two lists say it, from opposite sides. {@code forbidden-biomes} names the
 * biomes camera mode is not allowed in, {@code dimensions} names the
 * dimensions it is allowed in. That keeps both of them short: there are
 * hundreds of biomes but only three dimensions.</p>
 *
 * <p>This class only says whether a spot is forbidden, see
 * {@link #forbiddenArea(Location)}. What follows from that - no start, or no
 * start and no way in - is decided by {@link AreaRuleLevel}.</p>
 */
public final class CamAreaRules {

    /** The name each dimension carries in the config file. */
    private static final Map<World.Environment, String> DIMENSIONS =
            new EnumMap<>(World.Environment.class);

    static {
        DIMENSIONS.put(World.Environment.NORMAL, "overworld");
        DIMENSIONS.put(World.Environment.NETHER, "nether");
        DIMENSIONS.put(World.Environment.THE_END, "end");
    }

    /** The dimensions camera mode is allowed in out of the box. */
    private static final Set<String> ALLOWED_BY_DEFAULT = Set.of("overworld");

    /** The biomes forbidden out of the box: the caves nobody is meant to peek into. */
    private static final List<String> DEFAULT_FORBIDDEN_BIOMES =
            List.of("lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves");

    /** How long the message at the border waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    private AreaRuleLevel level = AreaRuleLevel.START_AND_FLIGHT;
    private int warningCooldown = DEFAULT_WARNING_COOLDOWN;
    private final Set<NamespacedKey> forbiddenBiomes = new HashSet<>();
    private final Set<World.Environment> allowedDimensions =
            EnumSet.noneOf(World.Environment.class);

    /** Reads the whole section out of the config file. */
    public void load(ConfigReader config) {
        level = resolveLevel(config);
        warningCooldown = config.getInt("cam-area.warning-cooldown", DEFAULT_WARNING_COOLDOWN, 0);
        forbiddenBiomes.clear();
        for (String entry : config.getStringList("cam-area.forbidden-biomes", DEFAULT_FORBIDDEN_BIOMES)) {
            NamespacedKey biome = toBiomeKey(entry);
            if (biome == null) {
                config.warnUnknownEntry("cam-area.forbidden-biomes", entry);
                continue;
            }
            forbiddenBiomes.add(biome);
        }
        allowedDimensions.clear();
        for (Map.Entry<World.Environment, String> dimension : DIMENSIONS.entrySet()) {
            boolean allowed = config.getBoolean("cam-area.dimensions." + dimension.getValue(),
                    ALLOWED_BY_DEFAULT.contains(dimension.getValue()));
            if (allowed) {
                allowedDimensions.add(dimension.getKey());
            }
        }
    }

    public AreaRuleLevel getLevel() {
        return level;
    }

    /** How long the message at the border waits before it is sent again, in seconds. */
    public int getWarningCooldown() {
        return warningCooldown;
    }

    /**
     * The area that keeps camera mode out of this spot. The dimension is asked
     * first: whoever is not allowed to be in the Nether at all does not need to
     * hear which of its biomes he is standing in.
     *
     * @return the name of the dimension or of the biome, written the way it is
     *         written in the config file, or {@code null} when camera mode is
     *         allowed there
     */
    @SuppressWarnings("deprecation")
    public String forbiddenArea(Location location) {
        World world = location == null ? null : location.getWorld();
        if (world == null) {
            return null;
        }
        String dimension = DIMENSIONS.get(world.getEnvironment());
        // A world carrying a dimension of its own belongs to none of the three
        // and is therefore forbidden by none of them.
        if (dimension != null && !allowedDimensions.contains(world.getEnvironment())) {
            return dimension;
        }
        if (forbiddenBiomes.isEmpty()) {
            return null;
        }
        Biome biome = world.getBiome(location);
        // getKey and not getKeyOrNull: the latter comes from RegistryAware,
        // which the Spigot API puts on a biome but Paper does not - the call
        // would fail there. getKey sits on Keyed, which both of them have, and
        // a biome read out of a world is registered and therefore has a key.
        NamespacedKey key = biome == null ? null : biome.getKey();
        if (key == null || !forbiddenBiomes.contains(key)) {
            return null;
        }
        return displayName(key);
    }

    /**
     * Reads {@code cam-area.level} and falls back to the strictest level when
     * the number is unknown - a rule nobody can read is better kept than
     * quietly dropped.
     */
    private AreaRuleLevel resolveLevel(ConfigReader config) {
        int configured = config.getInt("cam-area.level", AreaRuleLevel.START_AND_FLIGHT.getId());
        AreaRuleLevel requested = AreaRuleLevel.fromId(configured);
        if (requested == null) {
            config.warnUnknownValue("cam-area.level", configured, "0, 1, 2",
                    String.valueOf(AreaRuleLevel.START_AND_FLIGHT.getId()));
            return AreaRuleLevel.START_AND_FLIGHT;
        }
        return requested;
    }

    /**
     * Turns one entry of {@code forbidden-biomes} into the key of a biome.
     * Capitals and spaces do not matter, so {@code lush_caves},
     * {@code LUSH_CAVES} and {@code Lush Caves} all mean the same biome. An
     * entry without a namespace is a vanilla biome and gets {@code minecraft:}
     * put in front of it.
     *
     * <p>The name is looked up in the biome registry of the server, so that a
     * typo is noticed instead of quietly matching nothing for the rest of the
     * run. A biome from a datapack is in that registry as well: datapacks are
     * read before the plugins are started.</p>
     *
     * @return the key of the biome, or {@code null} when the server knows no
     *         biome by that name
     */
    private static NamespacedKey toBiomeKey(String entry) {
        String text = entry.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (text.isEmpty()) {
            return null;
        }
        if (text.indexOf(':') < 0) {
            text = NamespacedKey.MINECRAFT + ":" + text;
        }
        NamespacedKey key = NamespacedKey.fromString(text);
        return key != null && Registry.BIOME.get(key) != null ? key : null;
    }

    /** The name of a biome the way it is written in the config file. */
    private static String displayName(NamespacedKey key) {
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.toString();
    }
}
