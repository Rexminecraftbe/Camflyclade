package de.elia.cameraplugin.photo;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
 * Photo mode, {@code /cam photo}: for a picture or a video of his build, the
 * camera player no longer sees what stands in the way of it - the other
 * players, with the names, particles and outlines of those in camera mode, and
 * the bodies of camera mode, his own among them, with their names and hearts,
 * as {@code photo-mode.hide-players} and {@code hide-bodies} say.
 *
 * <p>Only he stops seeing them. Everybody else sees everything as before, and
 * nothing changes in the world. It runs until he types the command again or
 * camera mode ends, and it is not written anywhere.</p>
 *
 * <p>The server hides a player from another one only as a whole, so the
 * hidden players are gone from his tab list as well while it runs.</p>
 *
 * <p>Hidden is what he could see when it started, and whatever turns up later
 * - a player joining, a new body - as soon as it does. What another plugin,
 * a vanish plugin say, hid from him already is none of photo mode's: it is
 * left alone, and stays hidden when photo mode ends.</p>
 */
public final class CamPhotoMode implements Listener {

    /** How often photo mode looks for anything new to hide, in ticks. */
    private static final long INTERVAL = 10L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** What photo mode hides from each player it runs for, by his id. */
    private final Map<UUID, Hidden> active = new HashMap<>();
    /** Looks for anything new while photo mode runs for anybody, otherwise {@code null}. */
    private BukkitRunnable task;

    /** What photo mode has hidden from one player, to be shown to him again. */
    private static final class Hidden {
        final Set<UUID> players = new HashSet<>();
        final Map<UUID, Entity> entities = new HashMap<>();
    }

    public CamPhotoMode(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Whether {@code /cam photo} does anything for this player right now. */
    public boolean mayUse(Player player) {
        return settings.isPhotoModeEnabled() && plugin.isInCameraMode(player);
    }

    /** {@code /cam photo}: switches photo mode on, or off again. */
    public void command(Player player) {
        if (!settings.isPhotoModeEnabled()) {
            messages.sendConfiguredMessage(player, "photo-off");
            return;
        }
        if (!plugin.isInCameraMode(player)) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        if (active.containsKey(player.getUniqueId())) {
            stopFor(player);
            messages.sendConfiguredMessage(player, "photo-ended");
            return;
        }
        Hidden hidden = new Hidden();
        active.put(player.getUniqueId(), hidden);
        hideFrom(player, hidden);
        if (task == null) {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    refresh();
                }
            };
            task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        }
        messages.sendConfiguredMessage(player, "photo-started");
    }

    /** Whether photo mode runs for this player. */
    public boolean isActive(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /**
     * Whether photo mode keeps the players away from this viewer, so that
     * nothing else is to show them to him while it runs, see
     * {@link de.elia.cameraplugin.visibility.CamVisibility}.
     */
    public boolean hidesPlayersFrom(Player viewer) {
        return settings.photoHidesPlayers() && active.containsKey(viewer.getUniqueId());
    }

    /** Whether it was photo mode that hid this player from this viewer. */
    public boolean hides(Player viewer, Player other) {
        Hidden hidden = active.get(viewer.getUniqueId());
        return hidden != null && hidden.players.contains(other.getUniqueId());
    }

    /**
     * Whether the viewer would see this player but for photo mode. For
     * whatever asks about it on behalf of camera mode: who nudges the body,
     * who comes close to it. Photo mode only takes the player out of the
     * picture, not out of the game.
     */
    public boolean sees(Player viewer, Player other) {
        return viewer.canSee(other) || hides(viewer, other);
    }

    /** Hides the body of a camera player who has just started from everybody photo mode runs for. */
    public void hideNewBody(CameraData data) {
        if (!settings.photoHidesBodies()) {
            return;
        }
        for (Map.Entry<UUID, Hidden> entry : active.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                hideBody(viewer, entry.getValue(), data);
            }
        }
    }

    /** Hides everything there is to hide from one player that he still sees. */
    private void hideFrom(Player viewer, Hidden hidden) {
        if (settings.photoHidesPlayers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                hidePlayer(viewer, hidden, other);
            }
        }
        if (settings.photoHidesBodies()) {
            for (UUID id : cameraPlayers.ids()) {
                CameraData data = cameraPlayers.get(id);
                if (data != null) {
                    hideBody(viewer, hidden, data);
                }
            }
        }
    }

    private void hidePlayer(Player viewer, Hidden hidden, Player other) {
        if (!other.equals(viewer) && viewer.canSee(other)) {
            viewer.hidePlayer(plugin, other);
            hidden.players.add(other.getUniqueId());
        }
    }

    /** The body, the mannequin in it and the name and hearts over it. */
    private void hideBody(Player viewer, Hidden hidden, CameraData data) {
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
        for (Entity part : parts) {
            if (part.isValid() && viewer.canSee(part)) {
                viewer.hideEntity(plugin, part);
                hidden.entities.put(part.getUniqueId(), part);
            }
        }
    }

    /**
     * One look round for everybody photo mode runs for: whatever has turned
     * up since is hidden, whatever has gone is forgotten.
     */
    private void refresh() {
        for (Map.Entry<UUID, Hidden> entry : new ArrayList<>(active.entrySet())) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            // Camera mode ends photo mode, see stopFor; this only catches
            // what got past it.
            if (viewer == null) {
                active.remove(entry.getKey());
                continue;
            }
            if (!plugin.isInCameraMode(viewer)) {
                stopFor(viewer);
                continue;
            }
            Hidden hidden = entry.getValue();
            // A removed body is no longer there to be seen; showing it again
            // only takes it off the list the server keeps for him.
            hidden.entities.values().removeIf(part -> {
                if (part.isValid()) {
                    return false;
                }
                viewer.showEntity(plugin, part);
                return true;
            });
            hideFrom(viewer, hidden);
        }
        if (active.isEmpty()) {
            stopTask();
        }
    }

    /**
     * Ends photo mode for a player - by his command, or with camera mode -
     * and shows him again what it hid from him.
     */
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
        // Shown again is everybody photo mode hid, and camera mode decides
        // once more which of them he is to see.
        plugin.getVisibility().updateVisibilityForAll();
        if (active.isEmpty()) {
            stopTask();
        }
    }

    /** A player joining is hidden from everybody photo mode runs for straight away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.photoHidesPlayers()) {
            return;
        }
        for (Map.Entry<UUID, Hidden> entry : active.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                hidePlayer(viewer, entry.getValue(), event.getPlayer());
            }
        }
    }

    /**
     * A player leaving is shown again while he still can be. The server
     * remembers whom a player is to be hidden from by the id, so he would
     * otherwise stay hidden from that viewer after he came back - with photo
     * mode long over.
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
