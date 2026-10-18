package de.elia.cameraplugin.teleport;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ends camera mode when a command or another plugin teleports somebody, as far
 * as {@code teleports.command} and {@code teleports.plugin} say: the camera
 * player himself, or a player who would come out right where a camera flies.
 *
 * <p>Without it a camera player taken away by {@code /tpa} or {@code /tp}
 * stayed in camera mode at the far end, his body and his things left behind,
 * and a player fetched by {@code /tpahere} from a camera player came out where
 * the camera hung in the air. Now the one arrives out of camera mode, with his
 * things, and the other arrives at the body, where the camera player stands
 * again by then.</p>
 *
 * <p>Camera mode's own teleports - back to the body, back from behind a
 * portal, off a happy ghast - go through {@link #teleport(Player, Location)}
 * and are left alone.</p>
 */
public final class CamTeleportGuard implements Listener {

    /**
     * How close to a spot a teleport has to come out to count as landing
     * there, in blocks. A teleport plugin sends a player to the other one's
     * own position, so the two are the same spot; this only leaves room for
     * rounding.
     */
    private static final double SAME_SPOT = 0.1;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The players camera mode is teleporting itself right now. */
    private final Set<UUID> ownTeleports = new HashSet<>();
    /**
     * Whose camera a teleport under way comes out at, by the player being
     * teleported: found while the teleport can still be sent elsewhere, and
     * ended once it is certain to happen, see {@link #onTeleportDecided}.
     */
    private final Map<UUID, UUID> arrivals = new HashMap<>();

    public CamTeleportGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * Teleports a player on camera mode's own behalf, a teleport this guard
     * lets through untouched.
     *
     * @return whether the server carried it out
     */
    public boolean teleport(Player player, Location to) {
        UUID id = player.getUniqueId();
        ownTeleports.add(id);
        try {
            return player.teleport(to);
        } finally {
            ownTeleports.remove(id);
        }
    }

    /**
     * Sends a teleport that would come out at a camera to the body of its
     * player instead. Done here, while the teleport can still be sent
     * elsewhere; camera mode itself only ends once nobody can call the
     * teleport off any more.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || !endsCamMode(event)) {
            return;
        }
        Player camera = cameraAt(player, to);
        if (camera == null) {
            return;
        }
        CameraData data = cameraPlayers.get(camera.getUniqueId());
        Location body = data.getBody().getLocation();
        // Looking the way the teleport meant him to.
        body.setYaw(to.getYaw());
        body.setPitch(to.getPitch());
        event.setTo(body);
        arrivals.put(player.getUniqueId(), camera.getUniqueId());
    }

    /**
     * Ends camera mode once the teleport is certain: for the player being
     * teleported, and for the one whose camera he was headed for.
     *
     * <p>The teleported camera player is not put back at his body first, the
     * teleport under way takes him on from where he is. A teleport that
     * another plugin calls off leaves both in camera mode, as if it had
     * never been asked for.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportDecided(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID cameraId = arrivals.remove(player.getUniqueId());
        if (event.isCancelled() || !endsCamMode(event)) {
            return;
        }
        if (cameraPlayers.contains(player.getUniqueId()) && !staysPut(event)) {
            messages.sendMessage(player, "teleport-ended");
            plugin.exitCameraModeForTeleport(player);
        }
        if (cameraId == null || !cameraPlayers.contains(cameraId)) {
            return;
        }
        Player camera = Bukkit.getPlayer(cameraId);
        if (camera != null) {
            messages.sendMessage(camera, "teleport-arrival-ended", "{player}", player.getName());
            plugin.exitCameraMode(camera, EndReason.FORCED);
        }
    }

    /**
     * Whether this teleport is one that ends camera mode: by a command or by
     * another plugin, as the config file has it, and not one of camera mode's
     * own.
     */
    private boolean endsCamMode(PlayerTeleportEvent event) {
        return !ownTeleports.contains(event.getPlayer().getUniqueId())
                && settings.teleportEndsCamMode(event.getCause());
    }

    /**
     * Whether the teleport leaves the player where he is. Some plugins
     * teleport a player onto his own spot, to turn his head; that carries the
     * camera nowhere, and ending camera mode there would drop him out of the
     * air.
     */
    private static boolean staysPut(PlayerTeleportEvent event) {
        Location to = event.getTo();
        return to != null && sameSpot(event.getFrom(), to);
    }

    /**
     * The camera player this teleport would come out at, or {@code null}. The
     * player being teleported is never his own camera.
     */
    private Player cameraAt(Player traveller, Location to) {
        for (UUID id : cameraPlayers.ids()) {
            if (id.equals(traveller.getUniqueId())) {
                continue;
            }
            Player camera = Bukkit.getPlayer(id);
            if (camera != null && sameSpot(camera.getLocation(), to)) {
                return camera;
            }
        }
        return null;
    }

    private static boolean sameSpot(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) <= SAME_SPOT * SAME_SPOT;
    }
}
