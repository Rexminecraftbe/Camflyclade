package de.elia.cameraplugin.list;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@code /cam list}: who is in camera mode right now, for how long already,
 * and how far from his body.
 *
 * <p>For whoever may go there, each line ends in two buttons, to the camera
 * and to the body, see {@link CamListTeleport}. They are put behind the
 * line and not into its text, so that a language file from before them gets
 * them as well.</p>
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

    /**
     * Sends the list, one line for each camera player, sorted by name.
     *
     * @param buttons whether each line ends in the buttons to the camera and
     *                to the body, see {@link CamListTeleport#mayUse}
     */
    public void show(CommandSender sender, boolean buttons) {
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
            boolean sameWorld = body.getWorld().equals(at.getWorld());
            String key = sameWorld ? "cam-list-entry" : "cam-list-entry-away";
            String distance = sameWorld ? String.valueOf(Math.round(at.distance(body))) : "";
            if (!buttons) {
                messages.sendConfiguredMessage(sender, key, "{player}", player.getName(),
                        "{time}", time, "{distance}", distance);
            } else if (messages.isMessageEnabled(key)) {
                String text = messages.getMessage(key).replace("{player}", player.getName())
                        .replace("{time}", time).replace("{distance}", distance);
                List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
                line.addAll(button("cam-list-camera", player.getName(), "camera"));
                line.addAll(button("cam-list-body", player.getName(), "body"));
                sender.spigot().sendMessage(line.toArray(new BaseComponent[0]));
            }
        }
    }

    /**
     * One button behind a line: its text, {@code cam-list-camera} or
     * {@code cam-list-body}, and on hovering the text of the same name with
     * {@code -hover} after it. A click runs {@code /cam tp}. Nothing when the
     * text is switched off or empty.
     *
     * @param where {@code camera} or {@code body}, as {@code /cam tp} takes it
     */
    private List<BaseComponent> button(String key, String player, String where) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{player}", player);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{player}", player))));
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam tp " + player + " " + where);
        List<BaseComponent> parts = new ArrayList<>();
        parts.add(new TextComponent(" "));
        for (BaseComponent part : TextComponent.fromLegacyText(text)) {
            part.setHoverEvent(hover);
            part.setClickEvent(click);
            parts.add(part);
        }
        return parts;
    }
}
