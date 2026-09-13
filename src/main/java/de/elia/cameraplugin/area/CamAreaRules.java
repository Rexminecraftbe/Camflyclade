package de.elia.cameraplugin.area;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.structure.GeneratedStructure;

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
 * <p>Three lists say it, and not all from the same side.
 * {@code forbidden-biomes} and {@code forbidden-structures} name the places
 * camera mode is not allowed in, {@code dimensions} names the dimensions it is
 * allowed in. That keeps every one of them short: there are hundreds of biomes
 * and dozens of structures but only three dimensions. Biomes and structures
 * have a switch of their own each, so a list can be laid aside without being
 * emptied.</p>
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

    /**
     * The structures forbidden out of the box: the ones worth looting, where
     * looking through the walls first would take the whole point out of them.
     */
    private static final List<String> DEFAULT_FORBIDDEN_STRUCTURES =
            List.of("mansion", "monument", "trial_chambers", "pillager_outpost", "ancient_city",
                    "swamp_hut", "fortress", "bastion_remnant", "end_city");

    /** How long the message at the border waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    private AreaRuleLevel level = AreaRuleLevel.START_AND_FLIGHT;
    private int warningCooldown = DEFAULT_WARNING_COOLDOWN;
    private boolean biomesEnabled = true;
    private boolean structuresEnabled = false;
    private final Set<NamespacedKey> forbiddenBiomes = new HashSet<>();
    private final Set<NamespacedKey> forbiddenStructures = new HashSet<>();
    private final Set<World.Environment> allowedDimensions =
            EnumSet.noneOf(World.Environment.class);

    /** Reads the whole section out of the config file. */
    public void load(ConfigReader config) {
        level = resolveLevel(config);
        warningCooldown = config.getInt("cam-area.warning-cooldown", DEFAULT_WARNING_COOLDOWN, 0);
        // The lists are read even when their switch is off, so that a mistyped
        // name is reported now and not only once somebody turns the switch on.
        biomesEnabled = config.getBoolean("cam-area.biomes-enabled", true);
        readKeys(config, "cam-area.forbidden-biomes", DEFAULT_FORBIDDEN_BIOMES,
                Registry.BIOME, forbiddenBiomes);
        structuresEnabled = config.getBoolean("cam-area.structures-enabled", false);
        readKeys(config, "cam-area.forbidden-structures", DEFAULT_FORBIDDEN_STRUCTURES,
                Registry.STRUCTURE, forbiddenStructures);
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
     * hear which of its biomes or structures he is standing in. Of the other
     * two the biome comes first, as it does in the config file.
     *
     * @return the name of the dimension, biome or structure, written the way it
     *         is written in the config file, or {@code null} when camera mode is
     *         allowed there
     */
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
        String biome = forbiddenBiome(world, location);
        return biome != null ? biome : forbiddenStructure(world, location);
    }

    /** The name of the biome at this spot, when it is a forbidden one. */
    @SuppressWarnings("deprecation")
    private String forbiddenBiome(World world, Location location) {
        if (!biomesEnabled || forbiddenBiomes.isEmpty()) {
            return null;
        }
        Biome biome = world.getBiome(location);
        // getKey and not getKeyOrNull: the latter comes from RegistryAware,
        // which the Spigot API puts on a biome but Paper does not - the call
        // would fail there. getKey sits on Keyed, which both of them have, and
        // a biome read out of a world is registered and therefore has a key.
        NamespacedKey key = biome == null ? null : biome.getKey();
        return key != null && forbiddenBiomes.contains(key) ? displayName(key) : null;
    }

    /**
     * The name of the structure this spot lies in, when it is a forbidden one.
     *
     * <p>Asked of the chunk the spot is in, which hands out every structure
     * reaching into it - a mansion is found from each of its corners, not only
     * from the chunk it started in. What counts as inside is the box a
     * structure takes up as a whole, not the single room somebody stands in:
     * the cellar of a mansion is part of the mansion, and so is the air above
     * its roof.</p>
     */
    // getKey is marked as outdated on both sides, but it is the only way both
    // of them offer: the getKeyOrThrow of the Spigot API is not on Paper, and
    // Paper's key() is not in the Spigot API the plugin is built against.
    @SuppressWarnings({"deprecation", "removal"})
    private String forbiddenStructure(World world, Location location) {
        if (!structuresEnabled || forbiddenStructures.isEmpty()) {
            return null;
        }
        for (GeneratedStructure generated : world.getStructures(location.getBlockX() >> 4,
                location.getBlockZ() >> 4)) {
            NamespacedKey key = generated.getStructure().getKey();
            if (forbiddenStructures.contains(key)
                    && generated.getBoundingBox().contains(location.getX(), location.getY(), location.getZ())) {
                return displayName(key);
            }
        }
        return null;
    }

    /**
     * Reads one list of names out of the config file and keeps the keys of the
     * entries the server knows.
     *
     * @param registry the registry the names are looked up in
     * @param into     the set that is filled, emptied first
     */
    private void readKeys(ConfigReader config, String path, List<String> defaults,
                          Registry<?> registry, Set<NamespacedKey> into) {
        into.clear();
        for (String entry : config.getStringList(path, defaults)) {
            NamespacedKey key = toKey(entry, registry);
            if (key == null) {
                config.warnUnknownEntry(path, entry);
                continue;
            }
            into.add(key);
        }
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
     * Turns one entry of a list into the key of a biome or a structure.
     * Capitals and spaces do not matter, so {@code lush_caves},
     * {@code LUSH_CAVES} and {@code Lush Caves} all mean the same biome. An
     * entry without a namespace is a vanilla one and gets {@code minecraft:}
     * put in front of it.
     *
     * <p>The name is looked up in the registry of the server, so that a typo is
     * noticed instead of quietly matching nothing for the rest of the run. A
     * biome or structure from a datapack is in that registry as well: datapacks
     * are read before the plugins are started.</p>
     *
     * @return the key, or {@code null} when the server knows nothing by that
     *         name
     */
    private static NamespacedKey toKey(String entry, Registry<?> registry) {
        String text = entry.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (text.isEmpty()) {
            return null;
        }
        if (text.indexOf(':') < 0) {
            text = NamespacedKey.MINECRAFT + ":" + text;
        }
        NamespacedKey key = NamespacedKey.fromString(text);
        return key != null && registry.get(key) != null ? key : null;
    }

    /** The name of a biome or structure the way it is written in the config file. */
    private static String displayName(NamespacedKey key) {
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.toString();
    }
}
