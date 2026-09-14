package de.elia.cameraplugin.area;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where camera mode is allowed at all: the section {@code cam-area} of the
 * config file.
 *
 * <p>The lists are not all read from the same side. The forbidden ones name
 * the places camera mode is not allowed in, {@code dimensions} names the
 * dimensions it is allowed in. That keeps every one of them short: there are
 * hundreds of biomes and dozens of structures but only three dimensions.
 * Biomes and structures have a switch of their own each, so a list can be laid
 * aside without being emptied.</p>
 *
 * <p>Structures are named in two lists, which decide how closely they are
 * measured: {@code forbidden-structures-box} takes the whole box a structure
 * occupies, {@code forbidden-structures-components} only the boxes of its
 * single pieces. The nether fortress is the reason for the second one - its
 * box holds far more empty nether than fortress.</p>
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

    /** Forbidden out of the box and compact enough to be taken as a whole. */
    private static final List<String> DEFAULT_BOX_STRUCTURES =
            List.of("monument", "swamp_hut", "pillager_outpost");

    /**
     * Forbidden out of the box and so sprawling that their box would take in
     * far more than the structure itself.
     */
    private static final List<String> DEFAULT_COMPONENT_STRUCTURES =
            List.of("mansion", "fortress", "ancient_city", "trial_chambers",
                    "bastion_remnant", "end_city");

    /** How many chunks the structure lookup keeps at once by default. */
    private static final int DEFAULT_REMEMBERED_CHUNKS = 256;

    /** How long the message at the border waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    private AreaRuleLevel level = AreaRuleLevel.START_AND_FLIGHT;
    private int warningCooldown = DEFAULT_WARNING_COOLDOWN;
    private boolean biomesEnabled = true;
    private boolean structuresEnabled = false;
    private int rememberedChunks = DEFAULT_REMEMBERED_CHUNKS;
    private final Set<NamespacedKey> forbiddenBiomes = new HashSet<>();
    private final Set<NamespacedKey> boxStructures = new HashSet<>();
    private final Set<NamespacedKey> componentStructures = new HashSet<>();
    private final Set<World.Environment> allowedDimensions =
            EnumSet.noneOf(World.Environment.class);

    /**
     * What has already been worked out for a chunk, the least recently used
     * entry giving way first once {@code structures-cache-chunks} is reached.
     * Worth keeping because a structure does not move: it is placed once while
     * the chunk is generated and stays as it is. Only one that is set
     * afterwards, by hand or by another plugin, is missed until the next
     * {@code /cam reload}, which empties this.
     */
    private final Map<ChunkKey, List<ForbiddenStructure>> knownChunks =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<ChunkKey, List<ForbiddenStructure>> eldest) {
                    return size() > rememberedChunks;
                }
            };

    /** One chunk of one world. */
    private record ChunkKey(UUID world, int x, int z) {
    }

    /** A forbidden structure reaching into a chunk, with the boxes to test against. */
    private record ForbiddenStructure(String name, List<BoundingBox> boxes) {
    }

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
        // The two lists used to be one, which was always measured by the box. A
        // file that still carries it keeps saying what it said: its entries
        // become the box list and nothing is measured by pieces until somebody
        // fills that list himself.
        List<String> onlyList = config.getStringList("cam-area.forbidden-structures", null);
        readKeys(config, "cam-area.forbidden-structures-box",
                onlyList != null ? onlyList : DEFAULT_BOX_STRUCTURES,
                Registry.STRUCTURE, boxStructures);
        readKeys(config, "cam-area.forbidden-structures-components",
                onlyList != null ? List.of() : DEFAULT_COMPONENT_STRUCTURES,
                Registry.STRUCTURE, componentStructures);
        dropStructuresInBothLists(config);
        rememberedChunks = config.getInt("cam-area.structures-cache-chunks",
                DEFAULT_REMEMBERED_CHUNKS, 0);
        knownChunks.clear();
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
        String dimension = forbiddenDimension(location);
        if (dimension != null) {
            return dimension;
        }
        String biome = forbiddenBiome(world, location);
        return biome != null ? biome : forbiddenStructure(world, location);
    }

    /**
     * The dimension of this spot, when camera mode is not allowed in it at all.
     * Asked on its own by the portal rules: a portal into a forbidden dimension
     * does not let a camera player through in the first place, while a portal
     * standing in a forbidden biome or structure does and brings him back.
     *
     * @return the name of the dimension, written the way it is written in the
     *         config file, or {@code null} when camera mode is allowed in it
     */
    public String forbiddenDimension(Location location) {
        World world = location == null ? null : location.getWorld();
        if (world == null) {
            return null;
        }
        String dimension = DIMENSIONS.get(world.getEnvironment());
        // A world carrying a dimension of its own belongs to none of the three
        // and is therefore forbidden by none of them.
        return dimension != null && !allowedDimensions.contains(world.getEnvironment())
                ? dimension
                : null;
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
     * <p>What counts as inside depends on the list the structure stands in: the
     * box it occupies as a whole, or only the boxes of its single pieces. The
     * first takes the cellar of a mansion along with the air above its roof,
     * the second leaves the gaps between the corridors of a fortress open.
     * A structure named in both lists is measured by neither, see
     * {@link #dropStructuresInBothLists(ConfigReader)}.</p>
     */
    private String forbiddenStructure(World world, Location location) {
        if (!structuresEnabled || (boxStructures.isEmpty() && componentStructures.isEmpty())) {
            return null;
        }
        for (ForbiddenStructure structure : structuresAround(world,
                location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            for (BoundingBox box : structure.boxes()) {
                if (box.contains(location.getX(), location.getY(), location.getZ())) {
                    return structure.name();
                }
            }
        }
        return null;
    }

    /**
     * The forbidden structures reaching into this chunk, worked out once and
     * then kept, see {@link #knownChunks}.
     *
     * <p>Worth keeping because the same chunk is asked again and again: a step
     * of one block sets off this check, so flying straight through a chunk asks
     * about it some sixteen times. On 0 nothing is kept and every step asks
     * anew.</p>
     */
    private List<ForbiddenStructure> structuresAround(World world, int chunkX, int chunkZ) {
        if (rememberedChunks <= 0) {
            return lookUpStructures(world, chunkX, chunkZ);
        }
        ChunkKey chunk = new ChunkKey(world.getUID(), chunkX, chunkZ);
        List<ForbiddenStructure> known = knownChunks.get(chunk);
        if (known == null) {
            known = lookUpStructures(world, chunkX, chunkZ);
            knownChunks.put(chunk, known);
        }
        return known;
    }

    /**
     * Asks the world which forbidden structures reach into this chunk and works
     * out the boxes to measure against.
     *
     * <p>Only the boxes reaching into the chunk are taken, so a fortress with a
     * hundred pieces does not leave all hundred of them to be measured at every
     * step.</p>
     */
    private List<ForbiddenStructure> lookUpStructures(World world, int chunkX, int chunkZ) {
        BoundingBox chunkBox = new BoundingBox(chunkX << 4, world.getMinHeight(), chunkZ << 4,
                (chunkX << 4) + 16, world.getMaxHeight(), (chunkZ << 4) + 16);
        List<ForbiddenStructure> found = new ArrayList<>();
        for (GeneratedStructure generated : world.getStructures(chunkX, chunkZ)) {
            NamespacedKey structure = structureKey(generated);
            boolean byPieces = componentStructures.contains(structure);
            if (!byPieces && !boxStructures.contains(structure)) {
                continue;
            }
            List<BoundingBox> boxes = byPieces
                    ? pieceBoxesIn(generated, chunkBox)
                    : List.of(generated.getBoundingBox());
            if (!boxes.isEmpty()) {
                found.add(new ForbiddenStructure(displayName(structure), boxes));
            }
        }
        return List.copyOf(found);
    }

    /**
     * The boxes of the pieces that reach into this chunk. A structure carrying
     * no pieces at all falls back to its own box, so that naming it under
     * {@code forbidden-structures-components} does not quietly forbid nothing.
     */
    private static List<BoundingBox> pieceBoxesIn(GeneratedStructure generated, BoundingBox chunk) {
        Collection<StructurePiece> pieces = generated.getPieces();
        if (pieces.isEmpty()) {
            return List.of(generated.getBoundingBox());
        }
        List<BoundingBox> boxes = new ArrayList<>();
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            if (box.overlaps(chunk)) {
                boxes.add(box);
            }
        }
        return List.copyOf(boxes);
    }

    // getKey is marked as outdated on both sides, but it is the only way both
    // of them offer: the getKeyOrThrow of the Spigot API is not on Paper, and
    // Paper's key() is not in the Spigot API the plugin is built against.
    @SuppressWarnings({"deprecation", "removal"})
    private static NamespacedKey structureKey(GeneratedStructure generated) {
        return generated.getStructure().getKey();
    }

    /**
     * Takes every structure standing in both lists out of both of them.
     *
     * <p>The two lists say how closely a structure is measured, so naming one in
     * both says two things about it at once. That is a mistake and not a choice
     * between them: it is reported and the structure is left alone, as if it
     * stood in neither list.</p>
     */
    private void dropStructuresInBothLists(ConfigReader config) {
        Set<NamespacedKey> inBoth = new HashSet<>(boxStructures);
        inBoth.retainAll(componentStructures);
        for (NamespacedKey structure : inBoth) {
            config.warnEntryInBothLists("cam-area.forbidden-structures-box",
                    "cam-area.forbidden-structures-components", displayName(structure));
        }
        boxStructures.removeAll(inBoth);
        componentStructures.removeAll(inBoth);
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
