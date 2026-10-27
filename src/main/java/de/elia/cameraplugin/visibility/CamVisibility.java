package de.elia.cameraplugin.visibility;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Who sees a camera player, after {@code camera-mode.player_visibility_mode}.
 */
public final class CamVisibility {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;

    public CamVisibility(CameraPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Whether the camera player is turned invisible. Mode NONE takes him away
     * from everybody and the effect is the only thing left that still does so,
     * so there it is applied even when the option is switched off.
     */
    public boolean needsInvisibility() {
        return settings.allowsInvisibilityPotion() || settings.getPlayerVisibilityMode() == VisibilityMode.NONE;
    }

    /**
     * Puts one viewer on the right side of the camera player.
     *
     * <p>Mode NONE leans on the invisibility instead of hiding the player from
     * the client: {@code hidePlayer} would take him out of the tab list as well,
     * and he is supposed to stay in there. What that costs is worn equipment -
     * the camera head keeps being drawn on an invisible player.</p>
     *
     * <p>A viewer in photo mode is left alone: it keeps every player out of
     * his picture, camera players as well, and hands him back to this when it
     * ends, see {@link de.elia.cameraplugin.photo.CamPhotoMode}. So is a
     * camera player {@code anti-scouting} hides from the viewer, being too
     * far away, see {@link de.elia.cameraplugin.scout.CamScoutGuard}, and one
     * on the same tour, see {@link de.elia.cameraplugin.tour.CamTour}.</p>
     */
    private void applyVisibility(Player camPlayer, Player viewer) {
        if (camPlayer.equals(viewer)) return;
        if (plugin.getPhotoMode().hidesPlayersFrom(viewer)) return;
        if (plugin.getScoutGuard().hides(viewer, camPlayer)) return;
        if (plugin.getTour().hidesPlayer(viewer, camPlayer)) return;
        switch (settings.getPlayerVisibilityMode()) {
            case CAM -> {
                if (cameraPlayers.contains(viewer.getUniqueId())) {
                    viewer.showPlayer(plugin, camPlayer);
                } else {
                    viewer.hidePlayer(plugin, camPlayer);
                }
            }
            // ALL shows him with the outline, NONE leans on the invisibility -
            // either way the entity stays where it is.
            case ALL, NONE -> viewer.showPlayer(plugin, camPlayer);
        }
    }

    public void updateVisibilityForAll() {
        for (UUID camId : cameraPlayers.ids()) {
            Player cam = Bukkit.getPlayer(camId);
            if (cam == null) continue;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                applyVisibility(cam, viewer);
            }
        }
    }
}
