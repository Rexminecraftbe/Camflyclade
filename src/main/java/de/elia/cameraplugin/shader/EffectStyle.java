package de.elia.cameraplugin.shader;

import java.util.Locale;

/**
 * What an effect of {@code shader-effects} looks like, its {@code style}.
 *
 * <p>Every style but {@link #CRT} is a look that grows with its strength:
 * an effect in stages shows its stage's share of it, an animation starts
 * with all of it and fades away. {@code crt} is an animation of its own, a
 * tube screen switching on or off, and is only there for the camera switching
 * on and off.</p>
 */
public enum EffectStyle {
    /** Snow and lines slipping sideways, like a weak signal. */
    STATIC,
    /** The picture tearing and jumping, red and blue running apart. */
    GLITCH,
    /** The picture lit up in the colour of the tint. */
    FLASH,
    /** The edges glowing in the colour of the tint and beating like a heart, faster the stronger. */
    PULSE,
    /** The picture flashing and its edges glowing in the colour of the tint, beating fast. */
    ALARM,
    /** The picture shaking. */
    SHAKE,
    /** The picture going dark and pale. */
    FADE,
    /** A tube screen switching on or off. */
    CRT;

    /** The name in the config file, e.g. {@code static}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The style of this name in the config file, or {@code null}. */
    public static EffectStyle byName(String name) {
        for (EffectStyle style : values()) {
            if (style.configName().equalsIgnoreCase(name)) {
                return style;
            }
        }
        return null;
    }

    /**
     * What this style looks like at this strength, 0 doing nothing. For
     * {@code crt}, which is no look but an animation, nothing.
     */
    ShaderValues look(int tint, double x) {
        ShaderValues values = new ShaderValues(tint);
        return switch (this) {
            case STATIC -> values.tintAmount(0.5 * x).staticSnow(0.8 * x).scanlines(0.4 * x).lineJitter(0.7 * x)
                    .colorSplit(0.5 * x).dropouts(0.4 * x).desaturate(0.5 * x).vignette(0.4 * x);
            case GLITCH -> values.tintAmount(0.6 * x).lineJitter(x).colorSplit(x).dropouts(0.8 * x)
                    .staticSnow(0.25 * x).shake(0.35 * x).flash(0.25 * x);
            case FLASH -> values.tintAmount(0.3 * x).flash(0.85 * x).vignette(0.2 * x);
            case PULSE -> values.tintAmount(0.25 * x).glow(0.9 * x).pulse(1.0).vignette(0.5 * x)
                    .desaturate(0.4 * x).speed(1.0 + x);
            case ALARM -> values.tintAmount(0.3 * x).flash(0.45 * x).glow(0.6 * x).pulse(1.0)
                    .scanlines(0.2 * x).speed(1.5 + x);
            case SHAKE -> values.tintAmount(0.2 * x).shake(x).colorSplit(0.4 * x).flash(0.15 * x);
            case FADE -> values.black(0.85 * x).desaturate(x).vignette(0.8 * x);
            case CRT -> values;
        };
    }

    /**
     * One picture of an animation in this style.
     *
     * @param strength    how strong, 0 to 1
     * @param t           how far along, 0 for its first tick and nearly 1 for
     *                    its last
     * @param switchingOn for {@code crt}: whether the screen switches on, at
     *                    the start of camera mode, or off
     */
    ShaderValues frame(int tint, double strength, double t, boolean switchingOn) {
        if (this == CRT) {
            return switchingOn ? switchOn(tint, strength, t) : switchOff(tint, strength, t);
        }
        double left = 1.0 - t;
        return look(tint, strength * left * left);
    }

    /**
     * A tube screen switching on: a dot growing into a line across the
     * middle, the line opening into the picture, and the last of the lines and
     * the snow of the tube fading away.
     */
    private static ShaderValues switchOn(int tint, double strength, double t) {
        ShaderValues values = new ShaderValues(tint);
        if (t < 0.2) {
            return values.collapse(1.0).shrink(1.0 - t / 0.2);
        }
        if (t < 0.65) {
            double p = (t - 0.2) / 0.45;
            double open = 1.0 - (1.0 - p) * (1.0 - p);
            return values.collapse(1.0 - open).flash(0.5 * strength * (1.0 - p)).scanlines(0.4 * strength);
        }
        double left = 1.0 - (t - 0.65) / 0.35;
        return values.staticSnow(0.3 * strength * left).scanlines(0.4 * strength * left)
                .colorSplit(0.3 * strength * left);
    }

    /**
     * A tube screen switching off, laid over the picture the player sees again
     * back at the body: from the first tick nothing but the line the camera's
     * picture fell into, shrinking to a dot that goes out - and then the
     * player's own eyes opening, out of the dark.
     */
    private static ShaderValues switchOff(int tint, double strength, double t) {
        ShaderValues values = new ShaderValues(tint);
        if (t < 0.3) {
            return values.collapse(1.0).shrink(t / 0.3);
        }
        if (t < 0.45) {
            return values.collapse(1.0).shrink(1.0).black((t - 0.3) / 0.15);
        }
        double p = (t - 0.45) / 0.55;
        double left = 1.0 - p;
        return values.black(left * left * (3.0 - 2.0 * left)).desaturate(0.5 * strength * left)
                .vignette(0.6 * strength * left);
    }
}
