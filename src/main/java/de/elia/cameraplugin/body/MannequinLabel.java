package de.elia.cameraplugin.body;

import org.bukkit.entity.Mannequin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Takes the grey "NPC" line off a mannequin.
 *
 * <p>A mannequin carries a description that the client draws under its name,
 * and how it is taken away differs between server implementations. Spigot has a
 * switch of its own, {@code setHideDescription(boolean)}. Paper offers only the
 * text, {@code setDescription(Component)}, and hides the line when that text is
 * handed in as {@code null}. Both routes are therefore looked up at runtime,
 * like the profile setter in {@link MannequinSkin}.</p>
 *
 * <p>A {@code setDescription} that takes plain text is deliberately left alone:
 * there {@code null} means the default, and that default is the "NPC" line
 * itself.</p>
 */
public final class MannequinLabel {

    private static volatile String usedSetter;

    private MannequinLabel() {
    }

    /**
     * Hides the description under the mannequin's name.
     *
     * @return {@code true} when this API version could do it
     */
    public static boolean hideDescription(Mannequin mannequin) {
        Method hideSwitch = findHideSwitch();
        if (hideSwitch != null && invoke(mannequin, hideSwitch, true)) {
            return true;
        }
        Method descriptionSetter = findComponentDescriptionSetter();
        return descriptionSetter != null && invoke(mannequin, descriptionSetter, null);
    }

    /**
     * The setter that was used for the description, or - when none of them
     * fitted - the ones this server's API offers.
     */
    public static String describeSetter() {
        String used = usedSetter;
        if (used != null) {
            return used;
        }
        StringBuilder available = new StringBuilder();
        for (Method method : Mannequin.class.getMethods()) {
            if (!isSingleArgumentSetter(method) || !method.getName().toLowerCase().contains("description")) {
                continue;
            }
            if (available.length() > 0) {
                available.append(", ");
            }
            available.append(describe(method));
        }
        return available.length() == 0 ? "keiner - die API kennt keine Beschreibung" : "keiner - vorhanden sind: " + available;
    }

    /** The switch that hides the description outright, where the API has one. */
    private static Method findHideSwitch() {
        for (Method method : Mannequin.class.getMethods()) {
            if (!isSingleArgumentSetter(method)) {
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

    /**
     * The setter for the description text, as long as it takes a text component
     * rather than plain text. Only such a setter hides the line when it is
     * given nothing.
     */
    private static Method findComponentDescriptionSetter() {
        for (Method method : Mannequin.class.getMethods()) {
            if (!isSingleArgumentSetter(method) || !method.getName().equals("setDescription")) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isPrimitive() || CharSequence.class.isAssignableFrom(parameter)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static boolean isSingleArgumentSetter(Method method) {
        return !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                && method.getName().startsWith("set");
    }

    /** Hands the value to the setter. A {@code null} is passed on as it is. */
    private static boolean invoke(Mannequin mannequin, Method setter, Object argument) {
        try {
            setter.invoke(mannequin, new Object[]{argument});
            usedSetter = describe(setter);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static String describe(Method method) {
        return method.getName() + '(' + method.getParameterTypes()[0].getSimpleName() + ')';
    }
}
