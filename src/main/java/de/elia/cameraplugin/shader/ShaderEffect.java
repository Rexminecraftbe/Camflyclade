package de.elia.cameraplugin.shader;

import de.elia.cameraplugin.session.EndReason;

import java.util.ArrayList;
import java.util.List;

/**
 * The effects of {@code shader-effects}, each a section of its own there.
 *
 * <p>Some are animations, a row of post effects one tick each: the camera
 * switching on, switching off - one for each sound the end has, see
 * {@link #ending(EndReason)} -, and a nudge. The others come in stages for as
 * long as their cause lasts, stronger the worse it gets, the way the noise of
 * a weak signal does.</p>
 *
 * <p>Each brings the look it has out of the box; the config file only
 * changes it.</p>
 */
public enum ShaderEffect {
    /** The camera switching on, at the start of camera mode. */
    START("start", true, EffectStyle.CRT, 12, 0xFFFFFF, 1.0),
    /** The camera switching off when the player ends camera mode, as {@code sounds.end} sounds. */
    END("end", true, EffectStyle.CRT, 14, 0xFFFFFF, 1.0),
    /** The camera switching off because something happened to the body, as {@code sounds.end-body}. */
    END_BODY("end-body", true, EffectStyle.GLITCH, 14, 0xFF3030, 1.0),
    /** The camera switching off because it was ended from outside, as {@code sounds.end-forced}. */
    END_FORCED("end-forced", true, EffectStyle.STATIC, 16, 0xFFFFFF, 1.0),
    /** Somebody nudging the body. */
    NUDGE("nudge", true, EffectStyle.SHAKE, 8, 0xFFE08A, 0.8),
    /** The last seconds before the time limit or the battery end camera mode. */
    END_WARNING("end-warning", false, EffectStyle.ALARM, 4, 0xFF3B30, 0.8),
    /** The body low on health. */
    LOW_HEALTH("low-health", false, EffectStyle.PULSE, 4, 0xD00000, 1.0),
    /** The camera close to the border: a forbidden area, or {@code max-distance}. */
    BORDER("border", false, EffectStyle.STATIC, 4, 0x9FC4FF, 0.7);

    /** The most ticks an animation lasts, two seconds - one post effect for each in the pack. */
    public static final int MAX_TICKS = 40;

    /** The fewest: one tick would be gone before anybody saw it. */
    public static final int MIN_TICKS = 2;

    private final String configName;
    private final boolean animation;
    private final EffectStyle defaultStyle;
    private final int defaultSteps;
    private final int defaultTint;
    private final double defaultStrength;

    ShaderEffect(String configName, boolean animation, EffectStyle defaultStyle, int defaultSteps, int defaultTint,
                 double defaultStrength) {
        this.configName = configName;
        this.animation = animation;
        this.defaultStyle = defaultStyle;
        this.defaultSteps = defaultSteps;
        this.defaultTint = defaultTint;
        this.defaultStrength = defaultStrength;
    }

    /** The name of its section under {@code shader-effects}, e.g. {@code end-body}. */
    public String configName() {
        return configName;
    }

    /** The name its post effects carry in the pack, e.g. {@code end_body} for {@code camfly:end_body_1}. */
    public String fileName() {
        return configName.replace('-', '_');
    }

    /** Whether it is an animation, a post effect for each tick - or else in stages. */
    public boolean isAnimation() {
        return animation;
    }

    /** Whether it is the camera switching on or off, the only ones {@code crt} is there for. */
    public boolean switchesCamera() {
        return this == START || this == END || this == END_BODY || this == END_FORCED;
    }

    /** The styles it may have: every one, and {@code crt} only for the camera switching on and off. */
    public List<String> allowedStyles() {
        List<String> names = new ArrayList<>();
        for (EffectStyle style : EffectStyle.values()) {
            if (style != EffectStyle.CRT || switchesCamera()) {
                names.add(style.configName());
            }
        }
        return names;
    }

    public EffectStyle defaultStyle() {
        return defaultStyle;
    }

    /** How many ticks an animation lasts out of the box, or in how many stages the others come. */
    public int defaultSteps() {
        return defaultSteps;
    }

    public int defaultTint() {
        return defaultTint;
    }

    public double defaultStrength() {
        return defaultStrength;
    }

    /**
     * The camera switching off for this end, as the end sound goes: none for
     * an end nobody would see, the death, leaving the server, the server going
     * down.
     *
     * @return the effect, or {@code null} for none
     */
    public static ShaderEffect ending(EndReason reason) {
        return switch (reason.getKind()) {
            case SELF -> END;
            case BODY -> END_BODY;
            case FORCED -> END_FORCED;
            case SILENT -> null;
        };
    }
}
