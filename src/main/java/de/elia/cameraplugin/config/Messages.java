package de.elia.cameraplugin.config;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The texts of the {@code messages} section and the switches of
 * {@code message-settings} that turn them off. Both stand in the language file
 * the config file names under {@code language}, see
 * {@link ConfigFile#readConfigInto}.
 *
 * <p>Whatever that file leaves out, the English file built into the jar says:
 * a language file from an older version does not have the newer keys in it,
 * and a translation keeps working while it lacks a few.</p>
 */
public final class Messages {

    /** The English language file built into the jar. */
    private final FileConfiguration builtIn;

    /**
     * The language file in use. Until a file has been read, and while there is
     * none for the language, that is the built-in one.
     */
    private FileConfiguration language;

    public Messages(JavaPlugin plugin) {
        InputStream stream = plugin.getResource(ConfigFile.languagePath(ConfigFile.DEFAULT_LANGUAGE));
        builtIn = stream == null
                ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        language = builtIn;
    }

    /**
     * Puts a language file in place, once {@link ConfigFile} has read it.
     *
     * @param file the file as it was read, or {@code null} when there is no
     *             file for the language and the built-in one is used
     */
    void use(FileConfiguration file) {
        if (file == null) {
            language = builtIn;
            return;
        }
        file.setDefaults(builtIn);
        language = file;
    }

    public String getMessage(String path) {
        String text = language.getString("messages." + path);
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    public boolean isMessageEnabled(String path) {
        return isSwitchedOn("enabled") && isSwitchedOn(path);
    }

    /**
     * Whether a switch of {@code message-settings} is on. One that is no truth
     * value counts as on and is reported, see {@link #check()}; so does one
     * that is in neither file, as a message without a switch of its own is
     * always sent.
     */
    private boolean isSwitchedOn(String key) {
        Object value = language.get("message-settings." + key);
        return !(value instanceof Boolean on) || on;
    }

    /**
     * Checks the switches of the language file in one go. They are read one by
     * one while the server runs, so a wrong value would show up again and
     * again instead of once.
     *
     * @return a note for each switch that is no truth value
     */
    public List<ConfigIssue> check() {
        ConfigReader reader = new ConfigReader(language);
        reader.checkBooleanSection("message-settings");
        return reader.getWarnings();
    }

    /**
     * Sends a message unless its switch in {@code message-settings} is off.
     * Only a player is held to that switch: the console and command blocks
     * always get the answer to their command, which is also why
     * {@code no-player}, sent to them alone, has no switch at all.
     */
    public void sendConfiguredMessage(CommandSender sender, String path) {
        if (!(sender instanceof Player) || isMessageEnabled(path)) {
            sender.sendMessage(getMessage(path));
        }
    }

    /**
     * Sends a message with its placeholders filled in, unless it is switched
     * off or its text is empty - a blank line in the chat says nothing.
     *
     * @param fills placeholder and value, one pair after the other
     */
    public void sendMessage(Player player, String key, String... fills) {
        if (!isMessageEnabled(key)) {
            return;
        }
        String text = getMessage(key);
        if (text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < fills.length; i += 2) {
            text = text.replace(fills[i], fills[i + 1]);
        }
        player.sendMessage(text);
    }

    /**
     * A name from one of the lists in the language file that fill in a text:
     * {@code damage-names} for what hurt a body, {@code mob-names} for the mob
     * that attacked it, {@code effect-names}, {@code biome-names},
     * {@code structure-names}, {@code dimension-names} and
     * {@code portal-names}.
     *
     * <p>A name the language file leaves out comes from the English one, like
     * every text. One that neither of them has - something a data pack or
     * another plugin adds, or a newer version of the game - is the fallback,
     * and so is an empty one: a text with a gap where the name belongs says
     * less than a name that is not translated.</p>
     *
     * @param list     the list, {@code damage-names} for instance
     * @param key      the key of the thing, without its namespace
     * @param fallback what stands in for a name the list does not have
     */
    public String getName(String list, String key, String fallback) {
        String name = language.getString(list + "." + key);
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        return ChatColor.translateAlternateColorCodes('&', name);
    }

    /**
     * {@link #getName(String, String, String)} for a thing of the game, looked
     * up by its key without the namespace. Without a name it goes by that key,
     * with spaces for the underscores: {@code jump boost} reads better than
     * {@code jump_boost}, and still tells which line the list is missing.
     */
    public String getName(String list, NamespacedKey key) {
        return getName(list, key.getKey(), key.getKey().replace('_', ' '));
    }

    /**
     * Looks up the wording of a config note. Unlike {@link #getMessage(String)}
     * an empty entry falls back to the built-in text: a note that lost its
     * wording would be an empty line in the log and would hide the very problem
     * it is about. Use {@code message-settings.config-errors} to switch the
     * notes in the chat off instead.
     */
    public String configMessage(String key) {
        String raw = language.getString("messages." + key);
        if (raw == null || raw.isEmpty()) {
            raw = builtIn.getString("messages." + key, "");
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
