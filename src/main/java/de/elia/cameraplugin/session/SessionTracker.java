package de.elia.cameraplugin.session;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Measures a camera mode while it runs: how far the camera flies, and how far
 * it gets from the body. Both go into the log of {@code cam-log} and into the
 * statistics when camera mode ends.
 *
 * <p>Only real steps count. A teleport - the push back at the border, a
 * portal, a command - is an event of its own and never reaches this handler,
 * so it adds nothing to the way flown. A step that something turned away
 * did not happen and is left out as well.</p>
 */
public final class SessionTracker implements Listener {

    private final CameraPlayers cameraPlayers;

    public SessionTracker(CameraPlayers cameraPlayers) {
        this.cameraPlayers = cameraPlayers;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraMove(PlayerMoveEvent event) {
        CameraData data = cameraPlayers.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (data == null || to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            data.addDistanceFlown(from.distance(to));
        }
        // From the spot of a visit, which takes the place of the body: the
        // way to a body a thousand blocks off is no flight of the camera.
        Location home = data.getVisit() != null ? data.getVisit().anchor() : data.getBody().getLocation();
        if (home.getWorld() != null && home.getWorld().equals(to.getWorld())) {
            data.noteDistanceFromBody(home.distance(to));
        }
    }
}
