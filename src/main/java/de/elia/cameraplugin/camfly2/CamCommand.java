package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.list.CamList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CamCommand implements CommandExecutor {

    private final CameraPlugin plugin;
    private final Messages messages;
    private final CamList camList;

    public CamCommand(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.camList = plugin.getCamList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Before the player check: the reload also works from the server
        // console. Whoever changes the file on the server does not always have
        // an operator in the game to type the command.
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }
        // From the console as well: whoever runs the server wants to know who
        // is flying around, without being in the game.
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            if (camList.mayUse(sender)) {
                camList.show(sender);
            } else {
                messages.sendConfiguredMessage(sender, "no-permission");
            }
            return true;
        }
        // The console can hand out a camera and look at statistics, only not
        // for itself.
        if (args.length > 0 && args[0].equalsIgnoreCase("item")) {
            plugin.getCameraItem().give(sender, args.length > 1 ? args[1] : null);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            plugin.getStats().show(sender, args.length > 1 ? args[1] : null);
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.sendConfiguredMessage(sender, "no-player");
            return true;
        }

        if (!player.hasPermission("camplugin.use")) {
            messages.sendConfiguredMessage(player, "no-permission");
            return true;
        }

        plugin.getStarter().toggle(player);
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
