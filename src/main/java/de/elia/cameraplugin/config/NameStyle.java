package de.elia.cameraplugin.config;

import org.bukkit.ChatColor;

import java.util.Locale;

/**
 * How a name the plugin puts up looks: the switches that stand under
 * {@code body.name} for the name over the body and under
 * {@code camera-mode.name} for the name over the camera player.
 *
 * <p>Its colour is not among them: that is a colour code at the start of the
 * text in the language file, like the colour of every other text. Without
 * one the name shows in {@code defaultColor}. A {@code color} key that an
 * older file still has, because it named another colour than the default,
 * keeps its old meaning and colours the name whatever the text says.</p>
 *
 * @param color        the colour of the {@code color} key of an older file, or
 *                     {@code null} when the file has none
 * @param defaultColor the colour of a name whose text starts without a colour code
 * @param throughWalls whether it shows through blocks, {@code through-walls}
 * @param viewDistance how far away it is seen, in blocks, {@code view-distance}
 * @param background   whether it has the dark background of a name tag, {@code background}
 * @param shadowed     whether it casts a shadow, {@code shadow}
 * @param scale        its size, 1 being a name tag, {@code scale}
 */
public record NameStyle(ChatColor color, ChatColor defaultColor, boolean throughWalls, double viewDistance,
                        boolean background, boolean shadowed, double scale) {

    /**
     * The key of the colour that a section had until the colour came from
     * the text alone. The update of the file takes it out where it named
     * the default colour; another one stays and is still read.
     */
    public static final String OLD_COLOR = "color";

    /**
     * Reads the switches of one section.
     *
     * @param path         the section, e.g. {@code body.name}
     * @param defaultColor the colour of a name whose text brings none
     */
    public static NameStyle read(ConfigReader config, String path, ChatColor defaultColor) {
        return new NameStyle(
                config.isSet(path + "." + OLD_COLOR) ? readColor(config, path + "." + OLD_COLOR, defaultColor) : null,
                defaultColor,
                config.getBoolean(path + ".through-walls", false),
                config.getDouble(path + ".view-distance", 64.0, 1.0),
                config.getBoolean(path + ".background", true),
                config.getBoolean(path + ".shadow", false),
                config.getDouble(path + ".scale", 1.0, 0.1, 10.0));
    }

    /**
     * Reads a colour: one of the sixteen colours of the chat, by the name the
     * game gives it, e.g. {@code yellow} or {@code dark_green}. Capitals and
     * spaces do not count. Formatting such as bold is no colour and is turned
     * away with the rest.
     */
    private static ChatColor readColor(ConfigReader config, String path, ChatColor defaultColor) {
        String fallback = defaultColor.name().toLowerCase(Locale.ROOT);
        String raw = config.getString(path, fallback);
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (ChatColor color : ChatColor.values()) {
            if (color.isColor() && color.name().equals(name)) {
                return color;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (ChatColor color : ChatColor.values()) {
            if (color.isColor()) {
                allowed.append(allowed.length() == 0 ? "" : ", ").append(color.name().toLowerCase(Locale.ROOT));
            }
        }
        config.warnUnknownValue(path, raw, allowed.toString(), fallback);
        return defaultColor;
    }
}
