package de.elia.cameraplugin.photo;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
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
 * the mobs and animals, and the items and experience orbs lying about, as
 * {@code photo-mode.hide-players}, {@code hide-bodies}, {@code hide-mobs} and
 * {@code hide-items} say. What belongs to a build - armour stands,
 * mannequins, item frames, paintings, minecarts, boats - stays in the picture.
 *
 * <p>Only he stops seeing them. Everybody else sees everything as before, and
 * nothing changes in the world. It runs until he types the command again or
 * camera mode ends, and it is not written anywhere.</p>
 *
 * <p>The server hides a player from another one only as a whole, so the
 * hidden players are gone from his tab list as well while it runs.</p>
 *
 * <p>Hidden is what he could see when it started, and whatever turns up later
 * - a player joining, a new body, a mob spawning, an item dropped, the mobs
 * of a chunk that loads - as soon as it does. Mobs and items only in the
 * world he is in, and in the next one as soon as he gets there. What another
 * plugin, a vanish plugin say, hid from him already is none of photo mode's:
 * it is left alone, and stays hidden when photo mode ends.</p>
 *
 * <p>A mob somebody rides whom he is to see - a player on a horse, while
 * {@code hide-players} is off - stays: the server moves a rider only with
 * what he sits on, without it he would hang in the air for him. Once the
 * rider gets off, the next look round hides it.</p>
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
        /** The bodies, with the mannequins in them and their names and hearts. */
        final Map<UUID, Entity> entities = new HashMap<>();
        /** The mobs, items and experience orbs. */
        final Map<UUID, Entity> mobsAndItems = new HashMap<>();
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
        // After the players: whether he still sees a rider decides over his mount.
        if (hidesMobsOrItems()) {
            for (Entity entity : viewer.getWorld().getEntities()) {
                hideMobOrItem(viewer, hidden, entity);
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

    /** Whether photo mode hides mobs or items at all, {@code hide-mobs} and {@code hide-items}. */
    private boolean hidesMobsOrItems() {
        return settings.photoHidesMobs() || settings.photoHidesItems();
    }

    /**
     * Whether photo mode takes this out of the picture for what it is: a mob
     * or animal, or an item lying about or an experience orb. The bodies are
     * armour stands and mannequins, neither of them a mob.
     */
    private boolean ofHiddenKind(Entity entity) {
        if (entity instanceof Mob) {
            return settings.photoHidesMobs();
        }
        return (entity instanceof Item || entity instanceof ExperienceOrb) && settings.photoHidesItems();
    }

    /**
     * Whether something of a hidden kind carries somebody the viewer is to
     * see, on it or on what rides it: then it stays in the picture.
     */
    private boolean carriesSeen(Player viewer, Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (ofHiddenKind(passenger) ? carriesSeen(viewer, passenger) : viewer.canSee(passenger)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hides a mob, an item or an experience orb from the viewer, if it is in
     * his world and he still sees it. Also for one that is only about to be
     * added to the world: the server will then never show it to him.
     */
    private void hideMobOrItem(Player viewer, Hidden hidden, Entity entity) {
        if (ofHiddenKind(entity) && entity.getWorld().equals(viewer.getWorld())
                && viewer.canSee(entity) && !carriesSeen(viewer, entity)) {
            viewer.hideEntity(plugin, entity);
            hidden.mobsAndItems.put(entity.getUniqueId(), entity);
        }
    }

    /** Hides a mob or item that has just turned up from everybody photo mode runs for. */
    private void hideFromAll(Entity entity) {
        for (Map.Entry<UUID, Hidden> entry : active.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                hideMobOrItem(viewer, entry.getValue(), entity);
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
            // The same for a mob or an item - one that unloads with its chunk
            // is hidden again as it loads. A mount somebody he sees has got on
            // is shown again, not to leave the rider hanging in the air.
            hidden.mobsAndItems.values().removeIf(entity -> {
                if (entity.isValid() && !carriesSeen(viewer, entity)) {
                    return false;
                }
                viewer.showEntity(plugin, entity);
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
        for (Entity entity : hidden.mobsAndItems.values()) {
            player.showEntity(plugin, entity);
        }
        // Shown again is everybody photo mode hid, and camera mode decides
        // once more which of them he is to see - anti-scouting at once, before
        // anybody too far away is seen for a moment.
        plugin.getScoutGuard().refresh(player);
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
     * A mob spawning, an item dropped or an experience orb is hidden from
     * everybody photo mode runs for before it is in the world - before the
     * server could show it to anybody.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!active.isEmpty() && ofHiddenKind(event.getEntity())) {
            hideFromAll(event.getEntity());
        }
    }

    /** The mobs and items of a chunk that loads, before the server shows them to anybody. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (active.isEmpty() || !hidesMobsOrItems()) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            hideFromAll(entity);
        }
    }

    /** Through a portal: the mobs and items of the world he gets to, straight away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Hidden hidden = active.get(event.getPlayer().getUniqueId());
        if (hidden != null) {
            hideFrom(event.getPlayer(), hidden);
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
