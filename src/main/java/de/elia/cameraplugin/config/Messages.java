package de.elia.cameraplugin.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The texts of the {@code messages} section and the switches of
 * {@code message-settings} that turn them off.
 */
public final class Messages {

    private final JavaPlugin plugin;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages." + path, ""));
    }

    public boolean isMessageEnabled(String path) {
        if (!plugin.getConfig().getBoolean("message-settings.enabled", true)) {
            return false;
        }
        return plugin.getConfig().getBoolean("message-settings." + path, true);
    }

    public void sendConfiguredMessage(CommandSender sender, String path) {
        if (!(sender instanceof Player) || isMessageEnabled(path)) {
            sender.sendMessage(getMessage(path));
        }
    }

    /**
     * Sends a message with its placeholders filled in, unless it is switched
     * off or carries no text at all - a config file from an older version does
     * not have the newer keys in it, and a blank line in the chat says nothing.
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
     * Looks up the wording of a config note. Unlike {@link #getMessage(String)}
     * an empty entry falls back to the built-in text: a note that lost its
     * wording would be an empty line in the log and would hide the very problem
     * it is about. Use {@code message-settings.config-errors} to switch the
     * notes in the chat off instead.
     */
    public String configMessage(String key, String fallback) {
        String raw = plugin.getConfig().getString("messages." + key, fallback);
        if (raw == null || raw.isEmpty()) {
            raw = fallback;
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
