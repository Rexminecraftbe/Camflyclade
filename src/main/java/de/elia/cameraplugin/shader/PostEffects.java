package de.elia.cameraplugin.shader;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays a post effect over the screen of a player and takes it off again: a
 * shader the client runs over the finished picture, new with 26.3, as
 * {@code /posteffect} does it.
 *
 * <p>Paper hands them out through {@code Player#postEffects()}, which the
 * Spigot API the plugin is built against does not have; it is reached by
 * reflection. Where it is missing - on Spigot - the game's own command does
 * the same, sent from the console. That one writes a line into the console
 * every time, which is why it is only the way out.</p>
 *
 * <p>The server merely says which effects a player has: the effect itself
 * lives on the client, in a resource pack or the game. One the client does
 * not know shows nothing. The server keeps the list with the player and
 * saves it along with him, so an effect stays on him until it is taken off -
 * after a crash as well, see {@link de.elia.cameraplugin.crash.CamCrashGuard}.</p>
 *
 * <p>Paper sends the list to the client once a tick, whatever changed in it
 * during the tick: taking one effect off and laying the next one on is one
 * packet. That is what makes an animation of one post effect a tick cheap -
 * through the command, though, each would be a line in the console, see
 * {@link #direct()}.</p>
 */
public final class PostEffects {

    /** {@code Player#postEffects()}, or {@code null} where the server has none. */
    private static final Method POST_EFFECTS;
    /** {@code PlayerPostEffects#add(Key)}. */
    private static final Method ADD;
    /** {@code PlayerPostEffects#remove(Key)}. */
    private static final Method REMOVE;
    /** {@code Key#key(String)}, the key of Adventure. */
    private static final Method KEY;
    /** {@code PlayerPostEffects#values()}. */
    private static final Method VALUES;
    /** {@code Key#asString()}. */
    private static final Method AS_STRING;

    static {
        Method postEffects = null;
        Method add = null;
        Method remove = null;
        Method key = null;
        Method values = null;
        Method asString = null;
        try {
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            Class<?> effects = Class.forName("io.papermc.paper.entity.PlayerPostEffects");
            key = keyClass.getMethod("key", String.class);
            add = effects.getMethod("add", keyClass);
            remove = effects.getMethod("remove", keyClass);
            values = effects.getMethod("values");
            asString = keyClass.getMethod("asString");
            // Last: only once everything else is there can it be used.
            postEffects = Player.class.getMethod("postEffects");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            postEffects = null;
        }
        POST_EFFECTS = postEffects;
        ADD = add;
        REMOVE = remove;
        KEY = key;
        VALUES = values;
        AS_STRING = asString;
    }

    private PostEffects() {
    }

    /**
     * Whether the effects go straight to Paper, and not through the command
     * and the console. Only then are animations shown at all.
     */
    public static boolean direct() {
        return POST_EFFECTS != null;
    }

    /**
     * Lays the effect over the screen of the player, if he does not have it
     * already.
     *
     * @param key the effect, such as {@code minecraft:blur} or {@code camfly:signal_2}
     */
    public static void add(Player player, String key) {
        apply(player, key, true);
    }

    /** Takes the effect off the screen of the player, if he has it. */
    public static void remove(Player player, String key) {
        apply(player, key, false);
    }

    /**
     * Takes every effect of this namespace off the player, e.g. all of
     * CamFly's own, {@code camfly}. Only on Paper, which says which ones the
     * player has; the command has nothing to say it with but a line in the
     * console.
     */
    public static void removeAll(Player player, String namespace) {
        if (POST_EFFECTS == null) {
            return;
        }
        try {
            Object effects = POST_EFFECTS.invoke(player);
            List<Object> own = new ArrayList<>();
            for (Object key : (List<?>) VALUES.invoke(effects)) {
                if (((String) AS_STRING.invoke(key)).startsWith(namespace + ":")) {
                    own.add(key);
                }
            }
            for (Object key : own) {
                REMOVE.invoke(effects, key);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Nothing that could be done about it: they stay.
        }
    }

    private static void apply(Player player, String key, boolean add) {
        if (POST_EFFECTS != null) {
            try {
                Object effects = POST_EFFECTS.invoke(player);
                (add ? ADD : REMOVE).invoke(effects, KEY.invoke(null, key));
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // On to the command, which reaches the same list.
            }
        }
        // The id and not the name: it stands for exactly one player whatever
        // anybody is called.
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "minecraft:posteffect " + (add ? "add " : "remove ") + player.getUniqueId() + " " + key);
    }
}
