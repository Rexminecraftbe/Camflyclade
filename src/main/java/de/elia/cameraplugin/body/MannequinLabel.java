package de.elia.cameraplugin.body;

import org.bukkit.entity.Mannequin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Takes the grey "NPC" line off a mannequin.
 *
 * <p>A mannequin carries a description that the client draws under its name.
 * The switch that hides it is young API and its name has already differed
 * between builds, so it is looked up at runtime like the profile setter in
 * {@link MannequinSkin}. Failing to find it costs nothing but the extra line
 * under the name, so it stays quiet instead of writing a warning on every
 * camera mode.</p>
 */
public final class MannequinLabel {

    private MannequinLabel() {
    }

    /**
     * Hides the description under the mannequin's name.
     *
     * @return {@code true} when this API version could do it
     */
    public static boolean hideDescription(Mannequin mannequin) {
        Method setter = findDescriptionSetter();
        if (setter == null) {
            return false;
        }
        try {
            setter.invoke(mannequin, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Looks up the single-argument boolean setter that hides the description. */
    private static Method findDescriptionSetter() {
        for (Method method : Mannequin.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter != boolean.class && parameter != Boolean.class) {
                continue;
            }
            String name = method.getName();
            if (name.equals("setDescriptionHidden") || name.equals("setHideDescription")) {
                return method;
            }
        }
        return null;
    }
}
