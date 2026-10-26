package de.elia.cameraplugin.back;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import org.bukkit.entity.Player;

/**
 * {@code /cam back}: the camera goes back to its body, and camera mode runs
 * on, {@code back-command}.
 *
 * <p>For whoever has lost his way. Before, he had to end camera mode and start
 * it again for that - and wait out the cooldown, and pay for the start once
 * more. Nothing is gained by it either: the body is where camera mode ends up
 * anyway.</p>
 */
public final class CamBack {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    public CamBack(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /** Whether {@code /cam back} does anything for this player right now. */
    public boolean mayUse(Player player) {
        return settings.isBackCommandEnabled() && plugin.isInCameraMode(player);
    }

    /** Takes the camera of a player back to his body, as he asked for with {@code /cam back}. */
    public void command(Player player) {
        if (!settings.isBackCommandEnabled()) {
            messages.sendConfiguredMessage(player, "back-off");
            return;
        }
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (data == null) {
            messages.sendConfiguredMessage(player, "back-not-in-cam");
            return;
        }
        // Back at his body, he follows no tour any more.
        plugin.getTour().leave(player);
        if (!plugin.getMovementGuard().returnToBody(player, data)) {
            return;
        }
        settings.getBackSound().play(player);
        if (messages.isMessageEnabled("back-to-body")) {
            plugin.getActionBar().flash(player, messages.getMessage("back-to-body"));
        }
    }
}
