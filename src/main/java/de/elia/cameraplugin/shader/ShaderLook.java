package de.elia.cameraplugin.shader;

import de.elia.cameraplugin.config.ConfigReader;

import java.util.Locale;

/**
 * What CamFly's own shader for a weak signal looks like, the section
 * {@code signal.shader}: each value as it is at the weakest signal. The
 * stages below it take a share of each, see {@link ShaderPack}.
 *
 * @param stages     in how many stages the noise grows, 1 to {@link #MAX_STAGES}
 * @param staticSnow snow over the picture, 0 to 1
 * @param scanlines  the dark lines of a tube screen
 * @param lineJitter lines slipping sideways, and a tear running down
 * @param colorSplit red and blue running apart
 * @param dropouts   bands in which the picture drops out for a moment
 * @param desaturate colours fading, 1 being black and white
 * @param vignette   darker edges
 * @param speed      how fast it flickers, 1 being a new picture every tick
 * @param tint       the colour the picture takes on, as {@code 0xRRGGBB}
 */
public record ShaderLook(int stages, double staticSnow, double scanlines, double lineJitter, double colorSplit,
                         double dropouts, double desaturate, double vignette, double speed, int tint) {

    /** The most stages there are: post effects {@code camfly:signal_1} to {@code _8}. */
    public static final int MAX_STAGES = 8;

    /** The fastest {@code speed}: four pictures a tick are more than any screen shows. */
    private static final double MAX_SPEED = 4.0;

    /** Reads the section, e.g. {@code signal.shader}. */
    public static ShaderLook read(ConfigReader config, String path, String allowedColors) {
        return new ShaderLook(
                config.getInt(path + ".stages", 4, 1, MAX_STAGES),
                share(config, path + ".static", 0.55),
                share(config, path + ".scanlines", 0.35),
                share(config, path + ".line-jitter", 0.5),
                share(config, path + ".color-split", 0.4),
                share(config, path + ".dropouts", 0.3),
                share(config, path + ".desaturate", 0.6),
                share(config, path + ".vignette", 0.45),
                config.getDouble(path + ".speed", 1.0, 0.0, MAX_SPEED),
                readColor(config, path + ".tint", "#9fffb0", allowedColors));
    }

    private static double share(ConfigReader config, String path, double def) {
        return config.getDouble(path, def, 0.0, 1.0);
    }

    /**
     * Reads a colour written as {@code #RRGGBB}, the hash being optional. One
     * that is none is reported and the default is used.
     */
    private static int readColor(ConfigReader config, String path, String def, String allowed) {
        String raw = config.getString(path, def);
        Integer color = parseColor(raw);
        if (color == null) {
            config.warnUnknownValue(path, raw, allowed, def);
            color = parseColor(def);
        }
        return color;
    }

    private static Integer parseColor(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (!text.matches("[0-9a-f]{6}")) {
            return null;
        }
        return Integer.parseInt(text, 16);
    }
}
