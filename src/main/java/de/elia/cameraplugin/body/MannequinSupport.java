package de.elia.cameraplugin.body;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Access to the mannequin entity that Minecraft added in 1.21.9.
 *
 * <p>The plugin is compiled against an older API, so everything that is
 * specific to the mannequin is resolved at runtime. On a server that does not
 * know the entity every method here just reports failure and the caller falls
 * back to the armour stand.</p>
 */
public final class MannequinSupport {

    private static final EntityType MANNEQUIN_TYPE = resolveMannequinType();

    private MannequinSupport() {
    }

    /** {@code true} when this server provides the mannequin entity. */
    public static boolean isSupported() {
        return MANNEQUIN_TYPE != null;
    }

    /**
     * Spawns a mannequin at the given location.
     *
     * @return the spawned mannequin, or {@code null} if this server cannot spawn one
     */
    public static LivingEntity spawn(Location location) {
        if (MANNEQUIN_TYPE == null || location == null || location.getWorld() == null) {
            return null;
        }
        Entity spawned;
        try {
            spawned = location.getWorld().spawnEntity(location, MANNEQUIN_TYPE);
        } catch (RuntimeException ex) {
            return null;
        }
        if (spawned instanceof LivingEntity living) {
            return living;
        }
        spawned.remove();
        return null;
    }

    /**
     * Gives the mannequin the skin of the given player.
     *
     * @return {@code true} when the skin could be applied
     */
    public static boolean applyPlayerSkin(LivingEntity mannequin, Player player) {
        if (player == null) {
            return false;
        }
        return invokeSingleArgument(mannequin, player.getPlayerProfile(), "setProfile", "setPlayerProfile");
    }

    /**
     * Controls whether the mannequin can be pushed out of its place.
     *
     * @return {@code true} when the flag could be applied
     */
    public static boolean setImmovable(LivingEntity mannequin, boolean immovable) {
        return invokeSingleArgument(mannequin, immovable, "setImmovable");
    }

    private static EntityType resolveMannequinType() {
        try {
            return EntityType.valueOf("MANNEQUIN");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Calls the first of the given setters that accepts the argument. */
    private static boolean invokeSingleArgument(Object target, Object argument, String... methodNames) {
        if (target == null || argument == null) {
            return false;
        }
        for (String methodName : methodNames) {
            Method method = findMethod(target.getClass(), methodName, argument);
            if (method == null) {
                continue;
            }
            try {
                method.invoke(target, argument);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, Object argument) {
        Method candidate = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            if (!accepts(method.getParameterTypes()[0], argument)) {
                continue;
            }
            // Methods declared on an interface are always accessible, so prefer them.
            if (method.getDeclaringClass().isInterface()) {
                return method;
            }
            candidate = method;
        }
        return candidate;
    }

    private static boolean accepts(Class<?> parameterType, Object argument) {
        if (parameterType == boolean.class) {
            return argument instanceof Boolean;
        }
        return parameterType.isInstance(argument);
    }
}
