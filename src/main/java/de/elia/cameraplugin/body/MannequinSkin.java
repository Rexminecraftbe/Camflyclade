package de.elia.cameraplugin.body;

import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * Puts a player's skin onto a mannequin.
 *
 * <p>A mannequin takes its texture from a profile, but the type of that profile
 * differs between server implementations: Paper expects its own
 * {@code ResolvableProfile}, which is built from a {@code PlayerProfile} through
 * a static factory, while other implementations take a {@code PlayerProfile}
 * straight away. Only this one call is therefore resolved at runtime; the rest
 * of the mannequin handling uses the API directly.</p>
 */
public final class MannequinSkin {

    private MannequinSkin() {
    }

    /**
     * Applies the player's skin to the mannequin.
     *
     * @param withName whether the mannequin may carry the player's name. A
     *                 mannequin is drawn like a player and shows the name of
     *                 its profile above it, which no name tag setting takes
     *                 away, so a body without a name gets its skin through a
     *                 profile that has none either
     * @return {@code true} when the profile could be handed to the mannequin
     */
    public static boolean apply(Mannequin mannequin, Player player, boolean withName) {
        Method setter = findProfileSetter();
        if (setter == null) {
            return false;
        }
        PlayerProfile profile = player.getPlayerProfile();
        if (!withName) {
            PlayerProfile nameless = withoutName(profile, player.getUniqueId());
            if (nameless != null && setProfile(mannequin, setter, nameless)) {
                return true;
            }
            // The skin weighs more than the name: rather a body that is named
            // after all than one wearing the default skin.
        }
        return setProfile(mannequin, setter, profile);
    }

    /**
     * Copies the skin into a profile that carries no name.
     *
     * @return the nameless profile, or {@code null} when this server did not
     *         let the skin come across into it
     */
    private static PlayerProfile withoutName(PlayerProfile profile, UUID uniqueId) {
        try {
            PlayerProfile nameless = Bukkit.createPlayerProfile(uniqueId);
            nameless.setTextures(profile.getTextures());
            return nameless.getTextures().getSkin() == null ? null : nameless;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Hands one profile to the mannequin, in the type the setter expects. */
    private static boolean setProfile(Mannequin mannequin, Method setter, PlayerProfile profile) {
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
