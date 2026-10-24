package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.list.CamList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

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
                camList.show(sender, plugin.getListTeleport().mayUse(sender), plugin.getStop().showsButton(sender));
            } else {
                messages.sendConfiguredMessage(sender, "no-permission");
            }
            return true;
        }
        // From the console as well, like /cam stop.
        if (args.length > 0 && args[0].equalsIgnoreCase("ban")) {
            plugin.getBan().ban(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("unban")) {
            plugin.getBan().unban(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("banlist")) {
            plugin.getBan().list(sender);
            return true;
        }
        // From the console as well: whoever restarts the server wants
        // everybody back at his body first.
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            plugin.getStop().command(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // From the console as well, which gets where the flight went, if
        // not the line itself.
        if (args.length > 0 && args[0].equalsIgnoreCase("trail")) {
            plugin.getTrail().command(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // The console can look at the statistics of a player, only not at
        // its own.
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            plugin.getStats().command(sender, Arrays.copyOfRange(args, 1, args.length));
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

        if (args.length > 0 && args[0].equalsIgnoreCase("back")) {
            plugin.getBack().command(player);
            return true;
        }
        // What the buttons of /cam list run.
        if (args.length > 0 && args[0].equalsIgnoreCase("tp")) {
            plugin.getListTeleport().command(player, Arrays.copyOfRange(args, 1, args.length));
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
