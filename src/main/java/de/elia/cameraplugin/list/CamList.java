package de.elia.cameraplugin.list;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /cam list}: who is in camera mode right now, for how long already,
 * and how far from his body.
 */
public final class CamList {

    /** The right {@code cam-list.access: admin} asks for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.list";

    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;

    public CamList(CamSettings settings, Messages messages, CameraPlayers cameraPlayers) {
        this.settings = settings;
        this.messages = messages;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Whether this sender may see the list, after {@code cam-list.access}. The
     * console always may, it is the server; so may a command block.
     */
    public boolean mayUse(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return switch (settings.getCamListAccess()) {
            case ADMIN -> player.hasPermission(PERMISSION);
            case ALL -> player.hasPermission("camplugin.use");
            case OFF -> false;
        };
    }

    /** Sends the list, one line for each camera player, sorted by name. */
    public void show(CommandSender sender) {
        List<Player> players = new ArrayList<>();
        for (UUID id : cameraPlayers.ids()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            messages.sendConfiguredMessage(sender, "cam-list-empty");
            return;
        }
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        messages.sendConfiguredMessage(sender, "cam-list-header", "{count}", String.valueOf(players.size()));
        long now = System.currentTimeMillis();
        for (Player player : players) {
            CameraData data = cameraPlayers.get(player.getUniqueId());
            String time = CamTimeLimit.formatDuration(
                    TimeUnit.MILLISECONDS.toSeconds(now - data.getStartedAt()));
            Location body = data.getBody().getLocation();
            Location at = player.getLocation();
            if (body.getWorld().equals(at.getWorld())) {
                messages.sendConfiguredMessage(sender, "cam-list-entry", "{player}", player.getName(),
                        "{time}", time, "{distance}", String.valueOf(Math.round(at.distance(body))));
            } else {
                messages.sendConfiguredMessage(sender, "cam-list-entry-away", "{player}", player.getName(),
                        "{time}", time);
            }
        }
    }
}
