package de.elia.cameraplugin.ore;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.config.SettingValues;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The section {@code hide-ores} of the config file: which ores a camera
 * player sees as plain stone, and where. See {@link de.elia.cameraplugin.config.CamSettings#hideOres()}.
 */
public final class OreSettings {

    /** The brightest light the game has, sky or block. */
    private static final int MAX_LIGHT = 15;

    /** The word of {@code hide-ores.max-y} for no limit at all, as for every other setting that is off. */
    private static final String NO_LIMIT = "false";

    /**
     * What the file said for no limit before it was {@code false}. YAML reads
     * an {@code off} without quotes as {@code false} anyway; this is the one
     * in quotes.
     */
    private static final String OLD_NO_LIMIT = "off";

    /** The biomes of {@code hide-ores.biomes} out of the box: the caves the game has as biomes of their own. */
    private static final List<String> DEFAULT_ORE_BIOMES =
            List.of("lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves");

    /** The ores of the game and what they look like for a camera player, as the file built into the jar has them. */
    private static final Map<String, String> DEFAULT_ORE_COVERS = defaultOreCovers();

    /** Whether a camera player sees the ores of caves as stone, {@code hide-ores.enabled}. */
    private boolean hideOres;
    /** What each ore looks like for him, {@code hide-ores.blocks}, in the order of the file. */
    private final Map<Material, BlockData> oreCovers = new LinkedHashMap<>();
    /** Whether only the ores of the biomes of {@code hide-ores.biomes} are hidden, {@code hide-ores.where: biomes}. */
    private boolean oreBiomesOnly;
    /** The biomes whose ores are hidden under {@code hide-ores.where: biomes}. */
    private final Set<NamespacedKey> oreBiomes = new HashSet<>();
    /** The most sky light an ore may have at its open side and still count as one of a cave, {@code hide-ores.max-sky-light}. */
    private int oreMaxSkyLight;
    /** The highest ore that is hidden, {@code hide-ores.max-y}; {@link Integer#MAX_VALUE} for no limit. */
    private int oreMaxY;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public OreSettings(ConfigReader config, Messages messages) {
        hideOres = config.getBoolean("hide-ores.enabled", true);
        readOreCovers(config, messages);
        oreBiomesOnly = config.getChoice("hide-ores.where", "caves", "caves", "biomes")
                .equalsIgnoreCase("biomes");
        readOreBiomes(config);
        oreMaxSkyLight = config.getInt("hide-ores.max-sky-light", 0, 0, MAX_LIGHT);
        oreMaxY = readOreMaxY(config, messages);
    }

    /**
     * Reads {@code hide-ores.blocks}: each ore with the block it looks like
     * for a camera player. An ore or a block that is none - mistyped, an
     * item, a block of a newer version - is reported, and the entry left
     * out. Without the section at all, the ores of the game as the file
     * built into the jar has them.
     */
    private void readOreCovers(ConfigReader config, Messages messages) {
        oreCovers.clear();
        String path = "hide-ores.blocks";
        Map<String, String> entries = config.getNames(path);
        if (!config.has(path)) {
            entries = DEFAULT_ORE_COVERS;
        }
        String allowed = messages.configMessage("config-allowed-blocks");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Material ore = Material.matchMaterial(entry.getKey().trim());
            if (ore == null || !ore.isBlock() || ore.isAir()) {
                config.warnUnknownEntry(path, entry.getKey());
                continue;
            }
            BlockData cover = SettingValues.parseBlock(entry.getValue());
            if (cover == null) {
                config.warnUnknownValue(path + "." + entry.getKey(), entry.getValue(), allowed, "-");
                continue;
            }
            oreCovers.put(ore, cover);
        }
    }

    /**
     * Reads {@code hide-ores.biomes}, the way {@code cam-area.forbidden-biomes}
     * is read: a name the server does not know is reported and left out.
     */
    private void readOreBiomes(ConfigReader config) {
        oreBiomes.clear();
        String path = "hide-ores.biomes";
        for (String entry : config.getStringList(path, DEFAULT_ORE_BIOMES)) {
            NamespacedKey key = CamAreaRules.toKey(entry, Registry.BIOME);
            if (key == null) {
                config.warnUnknownEntry(path, entry);
            } else {
                oreBiomes.add(key);
            }
        }
    }

    /**
     * Reads {@code hide-ores.max-y}: the height of the highest ore that is
     * hidden, out of the box 15 - where the diamonds begin -, or
     * {@code false} for ores of every height.
     */
    private int readOreMaxY(ConfigReader config, Messages messages) {
        String path = "hide-ores.max-y";
        String raw = config.getString(path, "15").trim();
        Integer height = maxY(raw);
        if (height == null) {
            config.warnUnknownValue(path, raw, messages.configMessage("config-allowed-height"), "15");
            return 15;
        }
        return height;
    }

    /**
     * The height a value of {@code hide-ores.max-y} stands for, as
     * {@link ConfigReader#getString} hands it over: a whole number, or
     * {@link Integer#MAX_VALUE} for {@code false} - also an {@code off}
     * without quotes, which YAML reads as {@code false} - and an
     * {@code "off"} in quotes.
     *
     * @return the height, or {@code null} when the value is none of these
     */
    static Integer maxY(String raw) {
        String value = raw.trim();
        if (value.equalsIgnoreCase(NO_LIMIT) || value.equalsIgnoreCase(OLD_NO_LIMIT)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, String> defaultOreCovers() {
        Map<String, String> covers = new LinkedHashMap<>();
        for (String ore : List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "emerald", "diamond")) {
            covers.put(ore + "_ore", "stone");
            covers.put("deepslate_" + ore + "_ore", "deepslate");
        }
        covers.put("raw_iron_block", "tuff");
        covers.put("raw_copper_block", "granite");
        covers.put("ancient_debris", "netherrack");
        return Collections.unmodifiableMap(covers);
    }

    /** Whether a camera player sees the ores of caves as stone, {@code hide-ores.enabled}. */
    public boolean enabled() {
        return hideOres && !oreCovers.isEmpty();
    }

    /** What each ore looks like for a camera player, {@code hide-ores.blocks}; not to be changed. */
    public Map<Material, BlockData> covers() {
        return Collections.unmodifiableMap(oreCovers);
    }

    /**
     * The most sky light the open side of an ore may have for it to count
     * as one of a cave, {@code hide-ores.max-sky-light}: 0 out of the box,
     * the dark of a cave; at 15 the sky does not matter at all.
     */
    public int maxSkyLight() {
        return oreMaxSkyLight;
    }

    /**
     * The height of the highest ore that is hidden, {@code hide-ores.max-y};
     * {@link Integer#MAX_VALUE} while every height is.
     */
    public int maxY() {
        return oreMaxY;
    }

    /**
     * The biomes whose ores alone are hidden, {@code hide-ores.biomes}, under
     * {@code hide-ores.where: biomes}; {@code null} under {@code caves}, where
     * the ores of every cave are. Not to be changed.
     */
    public Set<NamespacedKey> biomes() {
        return oreBiomesOnly ? Collections.unmodifiableSet(oreBiomes) : null;
    }
}
