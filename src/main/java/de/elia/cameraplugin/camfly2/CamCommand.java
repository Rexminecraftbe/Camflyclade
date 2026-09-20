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
        // Vor der Spieler-Pruefung: Der Reload geht auch von der Server-Konsole
        // aus. Wer die Datei auf dem Server aendert, hat nicht immer einen
        // Operator im Spiel, der den Befehl fuer ihn tippen koennte.
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }

        if (!(sender instanceof Player player)) {
            plugin.sendConfiguredMessage(sender, "no-player");
            return true;
        }

        if (!player.hasPermission("camplugin.use")) {
            plugin.sendConfiguredMessage(player, "no-permission");
            return true;
        }

        if (plugin.isInCameraMode(player)) {
            plugin.exitCameraMode(player);
            plugin.sendConfiguredMessage(player, "camera-off");
        } else {
            // Vor der Abklingzeit: Wer als Zuschauer gar nicht hinein darf,
            // dem sagt "warte noch zehn Sekunden" das Falsche - danach darf er
            // genauso wenig.
            if (!plugin.checkCamGameMode(player)) {
                return true;
            }
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
            // Vor der Sicherheitspruefung: Ein schaedlicher Effekt tut meist
            // auch weh, und dann stuende erst "warte noch fuenf Sekunden" da
            // und danach erst der Grund, an dem es wirklich liegt.
            if (!plugin.checkCamEffects(player)) {
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
            plugin.sendConfiguredMessage(player, "no-permission");
            return true;
        }
        plugin.sendConfiguredMessage(sender, "reload-start");
        if (plugin.reloadPlugin(player)) {
            plugin.sendConfiguredMessage(sender, "reload-success");
        }
        return true;
    }

}