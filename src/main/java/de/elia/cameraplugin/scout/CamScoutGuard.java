package de.elia.cameraplugin.scout;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.photo.CamPhotoMode;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * No scouting, {@code anti-scouting}: the camera is no way to look for other
 * players. A camera player sees the others only within
 * {@code anti-scouting.radius} - of his body, or the spot he visits, which
 * takes its place, or of the camera itself, as {@code measured-from} says -
 * and the bodies of other camera players, which stand where those players
 * really are, only as close as well.
 *
 * <p>Only he stops seeing them, and only while he is in camera mode; everybody
 * else sees everything as before. Players in another world than the camera
 * are left alone, it would not see them anyway. The server hides a player
 * from another one only as a whole, so the hidden players are gone from his
 * tab list as well while they are hidden, as in photo mode.</p>
 *
 * <p>What another plugin, a vanish plugin say, hid from him is none of this
 * class's: it is left alone, and stays hidden. While photo mode keeps the
 * players or the bodies out of his picture, this class leaves them to it,
 * and looks again when photo mode ends.</p>
 *
 * <p>Whoever holds {@code camplugin.antiscout.bypass} sees everybody, the way
 * an operator does by default.</p>
 */
public final class CamScoutGuard implements Listener {

    /** The right that sees everybody all the same. */
    public static final String BYPASS = "camplugin.antiscout.bypass";

    /** How often it looks who has come closer or gone further away, in ticks. */
    private static final long INTERVAL = 10L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    /** What this class hides from each camera player, by his id. */
    private final Map<UUID, Hidden> active = new HashMap<>();
    /** Looks again while anybody is in camera mode with it switched on, otherwise {@code null}. */
    private BukkitRunnable task;

    /** What it has hidden from one player, to be shown to him again. */
    private static final class Hidden {
        final Set<UUID> players = new HashSet<>();
        final Map<UUID, Entity> entities = new HashMap<>();
    }

    public CamScoutGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Starts hiding for a player who has just entered camera mode. */
    public void startFor(Player player) {
        if (!settings.isAntiScoutingEnabled() || player.hasPermission(BYPASS)) {
            return;
        }
        active.putIfAbsent(player.getUniqueId(), new Hidden());
        refresh(player);
        if (task == null) {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    refreshAll();
                }
            };
            task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        }
    }

    /** Whether it is this class that hides this player from this viewer. */
    public boolean hides(Player viewer, Player other) {
        Hidden hidden = active.get(viewer.getUniqueId());
        return hidden != null && hidden.players.contains(other.getUniqueId());
    }

    /** One look round for everybody it runs for. */
    private void refreshAll() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) {
                active.remove(id);
            } else if (!cameraPlayers.contains(id)) {
                stopFor(viewer);
            } else {
                refresh(viewer);
            }
        }
        if (active.isEmpty()) {
            stopTask();
        }
    }

    /**
     * Looks once who is too far from this viewer and who is close enough
     * again. Also called by photo mode as it ends, so that nobody far away
     * is seen for a moment.
     */
    public void refresh(Player viewer) {
        Hidden hidden = active.get(viewer.getUniqueId());
        CameraData data = cameraPlayers.get(viewer.getUniqueId());
        if (hidden == null || data == null) {
            return;
        }
        Location center = center(viewer, data);
        double radius = settings.getAntiScoutingRadius();
        CamPhotoMode photo = plugin.getPhotoMode();
        if (!photo.hidesPlayersFrom(viewer)) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(viewer)) {
                    placePlayer(viewer, hidden, other, far(center, other.getLocation(), radius));
                }
            }
        }
        if (!(photo.isActive(viewer) && settings.photoHidesBodies())) {
            // A body that is gone is no longer there to be seen; showing it
            // again only takes it off the list the server keeps for him.
            hidden.entities.values().removeIf(part -> {
                if (part.isValid()) {
                    return false;
                }
                viewer.showEntity(plugin, part);
                return true;
            });
            for (UUID id : cameraPlayers.ids()) {
                CameraData other = cameraPlayers.get(id);
                if (other != null && !id.equals(viewer.getUniqueId())) {
                    placeBody(viewer, hidden, other, far(center, other.getBody().getLocation(), radius));
                }
            }
        }
    }

    /**
     * What the radius is measured from: the camera, or what it is held from -
     * the body, the spot it visits, or in another world the portal it came out
     * of. With nothing to measure against, the camera.
     */
    private Location center(Player viewer, CameraData data) {
        Location camera = viewer.getLocation();
        if (settings.getAntiScoutingFrom() == ScoutAnchor.CAMERA) {
            return camera;
        }
        Location anchor = plugin.getMovementGuard().distanceAnchor(data, camera);
        return anchor != null ? anchor : camera;
    }

    /** Whether a spot lies beyond the radius, in the world measured in. Another world is none of this. */
    private static boolean far(Location center, Location where, double radius) {
        return where.getWorld() != null && where.getWorld().equals(center.getWorld())
                && where.distanceSquared(center) > radius * radius;
    }

    private void placePlayer(Player viewer, Hidden hidden, Player other, boolean far) {
        UUID id = other.getUniqueId();
        if (far && !hidden.players.contains(id) && viewer.canSee(other)) {
            viewer.hidePlayer(plugin, other);
            hidden.players.add(id);
        } else if (!far && hidden.players.remove(id)) {
            viewer.showPlayer(plugin, other);
        }
    }

    /** The body of another camera player, the mannequin in it and the name and hearts over it. */
    private void placeBody(Player viewer, Hidden hidden, CameraData data, boolean far) {
        for (Entity part : parts(data)) {
            UUID id = part.getUniqueId();
            if (far && !hidden.entities.containsKey(id) && part.isValid() && viewer.canSee(part)) {
                viewer.hideEntity(plugin, part);
                hidden.entities.put(id, part);
            } else if (!far && hidden.entities.remove(id) != null) {
                viewer.showEntity(plugin, part);
            }
        }
    }

    private static List<Entity> parts(CameraData data) {
        List<Entity> parts = new ArrayList<>(List.of(data.getBody()));
        if (data.getHitbox() != null) {
            parts.add(data.getHitbox());
        }
        if (data.getNameDisplay() != null) {
            parts.add(data.getNameDisplay());
        }
        if (data.getHeartsFront() != null) {
            parts.add(data.getHeartsFront());
        }
        return parts;
    }

    /**
     * Hides the body of a camera player who has just started from everybody it
     * is too far from, before it is seen for a moment.
     */
    public void hideNewBody(Player owner, CameraData data) {
        for (Map.Entry<UUID, Hidden> entry : active.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            CameraData own = cameraPlayers.get(entry.getKey());
            if (viewer == null || own == null || viewer.equals(owner)
                    || (plugin.getPhotoMode().isActive(viewer) && settings.photoHidesBodies())) {
                continue;
            }
            Location center = center(viewer, own);
            placeBody(viewer, entry.getValue(), data,
                    far(center, data.getBody().getLocation(), settings.getAntiScoutingRadius()));
        }
    }

    /** Shows a player leaving camera mode everything it hid from him. */
    public void stopFor(Player player) {
        Hidden hidden = active.remove(player.getUniqueId());
        if (hidden == null) {
            return;
        }
        for (UUID id : hidden.players) {
            Player other = Bukkit.getPlayer(id);
            if (other != null) {
                player.showPlayer(plugin, other);
            }
        }
        for (Entity part : hidden.entities.values()) {
            player.showEntity(plugin, part);
        }
        if (active.isEmpty()) {
            stopTask();
        }
    }

    /**
     * A player leaving is shown again while he still can be: the server
     * remembers whom a player is hidden from by the id, so he would stay
     * hidden after he came back.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player leaving = event.getPlayer();
        for (Map.Entry<UUID, Hidden> entry : active.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null && entry.getValue().players.remove(leaving.getUniqueId())) {
                viewer.showPlayer(plugin, leaving);
            }
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void onDisable() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                stopFor(player);
            }
        }
        active.clear();
        stopTask();
    }
}
