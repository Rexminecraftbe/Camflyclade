package de.elia.cameraplugin.config;

import de.elia.cameraplugin.list.ListAccess;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.boss.BarStyle;

import java.util.Locale;

/**
 * Values that several sections of the config file have, read the same way
 * in each: a block, a particle, where something is kept, who may use
 * something, how a bossbar is divided. For {@link CamSettings} and the
 * classes of its sections.
 */
public final class SettingValues {

    private SettingValues() {
    }

    /**
     * Reads one of the blocks of the wall, {@code camera-mode.border-block}
     * and its three companions: the name of a block, with or without
     * {@code minecraft:} in front, capitals and spaces not counting -
     * {@code light_blue_stained_glass} and {@code Light Blue Stained Glass}
     * are the same block. Its states may follow in square brackets, the way
     * the game writes them, e.g. {@code glass_pane[north=true,south=true]}.
     * Anything that is no block falls back to {@code def}.
     *
     * <p>Fences, panes and iron bars given without states are joined on every
     * side. Standing on its own, each of them is only a thin post, and a wall
     * of posts has gaps wide enough to slip through.</p>
     */
    public static BlockData block(ConfigReader config, Messages messages, String path, String def) {
        String raw = config.getString(path, def);
        BlockData block = parseBlock(raw);
        if (block == null) {
            config.warnUnknownValue(path, raw, messages.configMessage("config-allowed-blocks"), def);
            return parseBlock(def);
        }
        return block;
    }

    /** The block a {@code border-block} entry names, or {@code null} for none. */
    public static BlockData parseBlock(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        int states = text.indexOf('[');
        String name = (states < 0 ? text : text.substring(0, states)).trim().replace(' ', '_');
        Material material = name.isEmpty() ? null : Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            return null;
        }
        if (states >= 0) {
            try {
                return material.createBlockData(text.substring(states));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        BlockData block = material.createBlockData();
        if (block instanceof MultipleFacing sides) {
            for (BlockFace face : sides.getAllowedFaces()) {
                sides.setFace(face, true);
            }
        }
        return block;
    }

    /**
     * Reads {@code camera-particles.particle}: the name of a particle, with or
     * without {@code minecraft:} in front, capitals and spaces not counting.
     *
     * <p>Only a particle that needs nothing but its name will do. A dust wants
     * a colour and a block crack a block, and the server turns one away that
     * comes without them - every tick, for every player who could see it.</p>
     */
    public static Particle particle(ConfigReader config, Messages messages, String path, Particle def) {
        String defName = def.name().toLowerCase(Locale.ROOT);
        String raw = config.getString(path, defName);
        Particle particle = parseParticle(raw);
        if (particle == null || particle.getDataType() != Void.class) {
            config.warnUnknownValue(path, raw, messages.configMessage("config-allowed-particles"), defName);
            return def;
        }
        return particle;
    }

    private static Particle parseParticle(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Reads where something is kept, the values file and memory. */
    public static StorageMode storage(ConfigReader config, String path) {
        return config.getChoice(path, "file", "file", "memory").equalsIgnoreCase("memory")
                ? StorageMode.MEMORY
                : StorageMode.FILE;
    }

    /** Reads who may use something, the values admin, all and false. */
    public static ListAccess access(ConfigReader config, String path) {
        String access = config.getChoice(path, "admin", "admin", "all", "false").toLowerCase(Locale.ROOT);
        return switch (access) {
            case "all" -> ListAccess.ALL;
            case "false" -> ListAccess.OFF;
            default -> ListAccess.ADMIN;
        };
    }

    /**
     * Reads how many notches a bossbar is divided into, such as
     * {@code time-limit.bossbar-notches}: 0 for none, or 6, 10, 12 or 20 -
     * the ones the game can draw, as {@code /bossbar} has them.
     */
    public static BarStyle notches(ConfigReader config, String path, int def) {
        int notches = config.getInt(path, def);
        BarStyle style = notchStyle(notches);
        if (style == null) {
            config.warnUnknownValue(path, notches, "0, 6, 10, 12, 20", String.valueOf(def));
            return notchStyle(def);
        }
        return style;
    }

    /** The bossbar with this many notches, or {@code null} when the game has none. */
    private static BarStyle notchStyle(int notches) {
        return switch (notches) {
            case 0 -> BarStyle.SOLID;
            case 6 -> BarStyle.SEGMENTED_6;
            case 10 -> BarStyle.SEGMENTED_10;
            case 12 -> BarStyle.SEGMENTED_12;
            case 20 -> BarStyle.SEGMENTED_20;
            default -> null;
        };
    }
}
