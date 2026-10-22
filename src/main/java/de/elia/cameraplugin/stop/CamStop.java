package de.elia.cameraplugin.stop;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /cam stop <player>} and {@code /cam stop all}: ends the camera mode
 * of somebody else, or of everybody - before an event or a restart, say. The
 * section {@code stop-command}; {@code /cam list} offers it as a button
 * behind each line, see {@code cam-list.stop}.
 *
 * <p>It ends the way {@code /cam reload} ends it, from outside: he is put
 * back at his body, hears {@code sounds.end-forced}, and is told who it
 * was.</p>
 */
public final class CamStop {

    /** The right the command asks for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.stop";

    /** The word that stands for everybody in camera mode. */
    private static final String ALL = "all";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;

    public CamStop(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Whether this sender may end the camera mode of others: while
     * {@code stop-command.enabled} is on, a player with the right of the
     * command, and the console, which is the server.
     */
    public boolean mayUse(CommandSender sender) {
        return settings.isStopCommandEnabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION));
    }

    /**
     * Whether the lines of {@code /cam list} end in a button for it: a player
     * who may use it, while {@code cam-list.stop} is on. The console cannot
     * click.
     */
    public boolean showsButton(CommandSender sender) {
        return sender instanceof Player && settings.isCamListStop() && mayUse(sender);
    }

    /** {@code /cam stop}, with what follows it. */
    public void command(CommandSender sender, String[] args) {
        if (!settings.isStopCommandEnabled()) {
            messages.sendConfiguredMessage(sender, "stop-off");
            return;
        }
        if (!mayUse(sender)) {
            messages.sendConfiguredMessage(sender, "no-permission");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(sender, "stop-usage");
            return;
        }
        if (args[0].equalsIgnoreCase(ALL)) {
            stopAll(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !cameraPlayers.contains(target.getUniqueId())) {
            messages.sendConfiguredMessage(sender, "stop-not-in-cam", "{player}", args[0]);
            return;
        }
        if (target.equals(sender)) {
            // His own camera mode he ends the way /cam does it.
            plugin.getStarter().toggle(target);
            return;
        }
        stop(target, sender);
        messages.sendConfiguredMessage(sender, "stop-done", "{player}", target.getName());
    }

    /** {@code /cam stop all}: everybody in camera mode, the sender as well. */
    private void stopAll(CommandSender sender) {
        List<Player> players = new ArrayList<>();
        for (UUID id : cameraPlayers.ids()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            messages.sendConfiguredMessage(sender, "stop-none");
            return;
        }
        for (Player player : players) {
            if (player.equals(sender)) {
                plugin.getStarter().toggle(player);
            } else {
                stop(player, sender);
            }
        }
        messages.sendConfiguredMessage(sender, "stop-all-done", "{count}", String.valueOf(players.size()));
    }

    /** Ends the camera mode of one player and tells him who did it. */
    private void stop(Player target, CommandSender by) {
        plugin.exitCameraMode(target, EndReason.STOP);
        String name = by instanceof Player player ? player.getName() : messages.getMessage("stop-console-name");
        messages.sendMessage(target, "stopped-by", "{player}", name);
    }
}
