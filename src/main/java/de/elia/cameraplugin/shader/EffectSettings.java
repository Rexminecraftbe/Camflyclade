package de.elia.cameraplugin.shader;

import de.elia.cameraplugin.config.ConfigReader;

import java.util.List;
import java.util.Locale;

/**
 * One section of {@code shader-effects}, such as {@code shader-effects.nudge}.
 *
 * @param enabled  whether the effect is shown at all
 * @param style    what it looks like
 * @param steps    how many ticks an animation lasts, or in how many stages
 *                 the others come
 * @param tint     the colour, as {@code 0xRRGGBB}
 * @param strength how strong, 0 to 1
 */
public record EffectSettings(boolean enabled, EffectStyle style, int steps, int tint, double strength) {

    /** Reads the section of this effect, falling back on the look it brings, see {@link ShaderEffect}. */
    public static EffectSettings read(ConfigReader config, ShaderEffect effect, String allowedColors) {
        String path = "shader-effects." + effect.configName();
        // Read even when it is switched off, so that a value that does not
        // fit is reported now and not only once somebody turns it on.
        boolean enabled = config.getBoolean(path + ".enabled", true);
        List<String> styles = effect.allowedStyles();
        EffectStyle style = EffectStyle.byName(config.getChoice(path + ".style",
                effect.defaultStyle().configName(), styles.toArray(new String[0])));
        int steps = effect.isAnimation()
                ? config.getInt(path + ".ticks", effect.defaultSteps(), ShaderEffect.MIN_TICKS, ShaderEffect.MAX_TICKS)
                : config.getInt(path + ".stages", effect.defaultSteps(), 1, ShaderLook.MAX_STAGES);
        String defaultTint = String.format(Locale.ROOT, "#%06x", effect.defaultTint());
        int tint = ShaderLook.readColor(config, path + ".tint", defaultTint, allowedColors);
        double strength = config.getDouble(path + ".strength", effect.defaultStrength(), 0.0, 1.0);
        return new EffectSettings(enabled && strength > 0.0, style, steps, tint, strength);
    }
}
