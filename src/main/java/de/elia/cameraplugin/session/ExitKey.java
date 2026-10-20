package de.elia.cameraplugin.session;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Ends camera mode with the key that swaps the hands, F unless the player
 * bound it to another one, {@code exit-key}. The same as {@code /cam}.
 *
 * <p>That key needs nothing in the hands: the client tells the server about
 * it with both of them empty, and in camera mode they are. It does nothing
 * else in camera mode either, so it cannot be pressed for something else and
 * end camera mode by accident. Switched off, it does nothing at all - the
 * hands still do not swap, there is nothing in them to swap.</p>
 */
public final class ExitKey implements Listener {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    public ExitKey(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExitKey(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isInCameraMode(player)) {
            return;
        }
        event.setCancelled(true);
        if (settings.isExitKeyEnabled()) {
            plugin.exitCameraMode(player, EndReason.COMMAND);
            messages.sendConfiguredMessage(player, "camera-off");
        }
    }
}
