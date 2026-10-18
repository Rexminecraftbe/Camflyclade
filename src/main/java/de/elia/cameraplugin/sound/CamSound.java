package de.elia.cameraplugin.sound;

import de.elia.cameraplugin.config.ConfigReader;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * One of the sounds of the section {@code sounds}: {@code start},
 * {@code end} or {@code border}.
 *
 * <p>Only the camera player hears it. Played where everybody could hear it,
 * it would give away a player whom {@code player_visibility_mode} keeps out
 * of sight.</p>
 *
 * @param key    the sound, as {@code /playsound} names it, or {@code null}
 *               when it is switched off
 * @param volume how loud, 1 being full volume
 * @param pitch  how high, 1 being the sound as it is
 */
public record CamSound(String key, float volume, float pitch) {

    /** A sound that is switched off and plays nothing. */
    public static final CamSound OFF = new CamSound(null, 0.0f, 1.0f);

    /** The lowest and highest pitch, the range the client plays a sound in. */
    private static final double MIN_PITCH = 0.5;
    private static final double MAX_PITCH = 2.0;

    /**
     * Reads one section, e.g. {@code sounds.start}.
     *
     * @param allowed      what the config note names as allowed, for a sound
     *                     the server does not know
     * @param defaultSound the sound that stands in the shipped config file
     * @param defaultPitch the pitch that stands there
     */
    public static CamSound read(ConfigReader config, String path, String allowed, String defaultSound,
                                double defaultPitch) {
        // Read even when it is switched off, so that a mistyped name is
        // reported now and not only once somebody turns the switch on.
        boolean enabled = config.getBoolean(path + ".enabled", true);
        String raw = config.getString(path + ".sound", defaultSound);
        String key = parseKey(raw);
        if (key == null) {
            config.warnUnknownValue(path + ".sound", raw, allowed, defaultSound);
            key = parseKey(defaultSound);
        }
        // Louder than 1 only carries further, and the sound is played right
        // at the player, so it would not sound any louder.
        double volume = config.getDouble(path + ".volume", 1.0, 0.0, 1.0);
        double pitch = config.getDouble(path + ".pitch", defaultPitch, MIN_PITCH, MAX_PITCH);
        if (!enabled || volume <= 0.0) {
            return OFF;
        }
        return new CamSound(key, (float) volume, (float) pitch);
    }

    /**
     * Turns the name of a sound into its key: with or without
     * {@code minecraft:} in front, capitals and spaces not counting.
     *
     * <p>A sound of the game is looked up, so that a typo is noticed instead
     * of quietly playing nothing. A sound with a namespace of its own comes
     * from a resource pack, which only the client knows, and is taken as it
     * is.</p>
     *
     * @return the key, or {@code null} when it names no sound
     */
    private static String parseKey(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = text.isEmpty() ? null : NamespacedKey.fromString(text);
        if (key == null) {
            return null;
        }
        if (key.getNamespace().equals(NamespacedKey.MINECRAFT) && Registry.SOUNDS.get(key) == null) {
            return null;
        }
        return key.toString();
    }

    /**
     * Plays the sound to the player alone. It follows him, so it does not
     * stay behind when he flies on or is teleported back to his body.
     */
    public void play(Player player) {
        if (key == null || !player.isOnline()) {
            return;
        }
        player.playSound(player, key, SoundCategory.MASTER, volume, pitch);
    }
}
