package de.elia.cameraplugin.session;

import de.elia.cameraplugin.tour.CamTour;
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
 *
 * <p>How far the camera got from the body is noted twice: where it has been
 * at all, for the log, and how far flying alone carried it away, for the
 * statistics - a jump to a spot far off is no flight, see
 * {@link CameraData#noteStep}.</p>
 *
 * <p>The steps of a guest on a tour of {@code /cam follow} go into the log
 * only. For the statistics the tour counts the way the guide takes him
 * along, see {@link CamTour}: through the guide's eyes his client sends no
 * steps, and looking round freely they would be that way twice.</p>
 */
public final class SessionTracker implements Listener {

    private final CameraPlayers cameraPlayers;
    private final CamTour tour;

    public SessionTracker(CameraPlayers cameraPlayers, CamTour tour) {
        this.cameraPlayers = cameraPlayers;
        this.tour = tour;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCameraMove(PlayerMoveEvent event) {
        CameraData data = cameraPlayers.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (data == null || to == null) {
            return;
        }
        boolean own = !tour.isFollowing(event.getPlayer());
        Location from = event.getFrom();
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            double step = from.distance(to);
            data.addDistanceFlown(step);
            if (own) {
                data.addDistanceCounted(step);
            }
        }
        Location home = data.getBody().getLocation();
        if (home.getWorld() != null && home.getWorld().equals(to.getWorld())) {
            double after = home.distance(to);
            data.noteDistanceFromBody(after);
            if (own && home.getWorld().equals(from.getWorld())) {
                data.noteStep(home.distance(from), after);
            }
        }
    }
}
