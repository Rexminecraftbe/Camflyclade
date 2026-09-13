package de.elia.cameraplugin.body;

import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Puts a player's skin onto a mannequin.
 *
 * <p>A mannequin takes its texture from a profile, but the type of that profile
 * differs between server implementations: Paper expects its own
 * {@code ResolvableProfile}, which is built from a {@code PlayerProfile} through
 * a static factory, while other implementations take a {@code PlayerProfile}
 * straight away. Even where the player's own profile comes from the types part
 * ways - {@code getPlayerProfile} hands out Paper's profile on Paper and
 * Bukkit's everywhere else, so the method cannot be called with a type written
 * down here. Both calls are therefore resolved at runtime and the profile is
 * passed along as it comes; the rest of the mannequin handling uses the API
 * directly.</p>
 */
public final class MannequinSkin {

    private MannequinSkin() {
    }

    /**
     * Applies the player's skin to the mannequin.
     *
     * <p>The name in that profile is never drawn: a mannequin shows the name it
     * was given, like every other entity, and the grey line under it comes from
     * its description, see {@link MannequinLabel}.</p>
     *
     * @return {@code true} when the profile could be handed to the mannequin
     */
    public static boolean apply(Mannequin mannequin, Player player) {
        Method setter = findProfileSetter();
        if (setter == null) {
            return false;
        }
        Object profile = playerProfile(player);
        if (profile == null) {
            return false;
        }
        Object argument = asExpectedProfile(profile, setter.getParameterTypes()[0]);
        if (argument == null) {
            return false;
        }
        try {
            setter.invoke(mannequin, argument);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Reads the player's own profile, whatever type this server hands out for
     * it. Looked up at runtime and kept as an {@code Object} on purpose: Paper
     * returns its own profile type here, so a call written against the type in
     * the Bukkit API would not be there at all.
     *
     * @return the profile, or {@code null} when it could not be read
     */
    private static Object playerProfile(Player player) {
        try {
            return Player.class.getMethod("getPlayerProfile").invoke(player);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Looks up the single-argument profile setter declared by the mannequin API. */
    private static Method findProfileSetter() {
        for (Method method : Mannequin.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            String name = method.getName();
            if (name.equals("setProfile") || name.equals("setPlayerProfile")) {
                return method;
            }
        }
        return null;
    }

    /**
     * Returns the profile itself when the setter accepts it, otherwise wraps it
     * using a static factory of the expected type (such as Paper's
     * {@code ResolvableProfile.resolvableProfile(PlayerProfile)}).
     *
     * @return the value to pass to the setter, or {@code null} if none fits
     */
    private static Object asExpectedProfile(Object profile, Class<?> expected) {
        if (expected.isInstance(profile)) {
            return profile;
        }
        for (Method factory : expected.getMethods()) {
            if (!Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 1) {
                continue;
            }
            if (!expected.isAssignableFrom(factory.getReturnType())) {
                continue;
            }
            if (!factory.getParameterTypes()[0].isInstance(profile)) {
                continue;
            }
            try {
                return factory.invoke(null, profile);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return null;
            }
        }
        return null;
    }
}
