package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.start.StartChecks;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

public class CamCommand implements CommandExecutor {

    private final CameraPlugin plugin;
    private final Messages messages;
    private final StartChecks startChecks;
    private final CamTimeLimit timeLimit;

    public CamCommand(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.startChecks = plugin.getStartChecks();
        this.timeLimit = plugin.getTimeLimit();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Before the player check: the reload also works from the server
        // console. Whoever changes the file on the server does not always have
        // an operator in the game to type the command.
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }

        if (!(sender instanceof Player player)) {
            messages.sendConfiguredMessage(sender, "no-player");
            return true;
        }

        if (!player.hasPermission("camplugin.use")) {
            messages.sendConfiguredMessage(player, "no-permission");
            return true;
        }

        if (plugin.isInCameraMode(player)) {
            plugin.exitCameraMode(player);
            messages.sendConfiguredMessage(player, "camera-off");
        } else {
            // Before the cooldown: "wait ten more seconds" tells a spectator
            // the wrong thing - waiting does not let a spectator start either.
            if (!startChecks.checkCamSpectator(player)) {
                return true;
            }
            if (timeLimit.isCooldownActive(player)) {
                long remaining = timeLimit.getCooldownRemaining(player);
                if (messages.isMessageEnabled("cooldown-text")) {
                    String msg = messages.getMessage("cooldown-text").replace("%time%", CamTimeLimit.formatDuration(remaining));
                    player.sendMessage(ChatColor.RED + msg);
                }
                return true;
            }
            if (!startChecks.checkCamArea(player)) {
                return true;
            }
            if (!startChecks.checkCamMedium(player)) {
                return true;
            }
            if (!startChecks.checkCamFalling(player)) {
                return true;
            }
            // Before the safety check: a harmful effect usually hurts as
            // well, and then "wait five more seconds" would come first and
            // the reason that really stands in the way only after it.
            if (!startChecks.checkCamEffects(player)) {
                return true;
            }
            if (!startChecks.checkCamSafety(player)) {
                return true;
            }
            plugin.enterCameraMode(player);
            messages.sendConfiguredMessage(player, "camera-on");
        }
        return true;
    }

    /**
     * Reads the config file again.
     *
     * <p>A player needs the same rights as for the command itself and has to be
     * an operator on top; the console needs nothing, it is the server.</p>
     *
     * @param sender who asked, the console being {@code null} to the plugin -
     *               there is no chat to send the notes about the file to
     */
    private boolean reload(CommandSender sender) {
        Player player = sender instanceof Player p ? p : null;
        if (player != null && (!player.hasPermission("camplugin.use") || !player.isOp())) {
            messages.sendConfiguredMessage(player, "no-permission");
            return true;
        }
        messages.sendConfiguredMessage(sender, "reload-start");
        if (plugin.reloadPlugin(player)) {
            messages.sendConfiguredMessage(sender, "reload-success");
        }
        return true;
    }

}
