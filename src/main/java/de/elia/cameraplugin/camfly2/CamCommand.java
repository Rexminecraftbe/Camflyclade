package de.elia.cameraplugin.camfly2;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

public class CamCommand implements CommandExecutor {

    private final CameraPlugin plugin;

    public CamCommand(CameraPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.sendConfiguredMessage(sender, "no-player");
            return true;
        }

        if (!player.hasPermission("camplugin.use")) {
            plugin.sendConfiguredMessage(player, "no-permission");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!player.isOp()) {
                plugin.sendConfiguredMessage(player, "no-permission");
                return true;
            }
            plugin.sendConfiguredMessage(player, "reload-start");
            if (plugin.reloadPlugin(player)) {
                plugin.sendConfiguredMessage(player, "reload-success");
            }
            return true;
        }

        if (plugin.isInCameraMode(player)) {
            plugin.exitCameraMode(player);
            plugin.sendConfiguredMessage(player, "camera-off");
        } else {
            if (plugin.isCooldownActive(player)) {
                long remaining = plugin.getCooldownRemaining(player);
                if (plugin.isMessageEnabled("cooldown-text")) {
                    String msg = plugin.getMessage("cooldown-text").replace("%time%", plugin.formatDuration(remaining));
                    player.sendMessage(ChatColor.RED + msg);
                }
                return true;
            }
            if (!plugin.checkCamArea(player)) {
                return true;
            }
            if (!plugin.checkCamSafety(player)) {
                return true;
            }
            plugin.enterCameraMode(player);
            plugin.sendConfiguredMessage(player, "camera-on");
        }
        return true;
    }
}