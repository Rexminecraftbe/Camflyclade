package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who on a tour of {@link CamTour} sees whom: those on the same tour - the
 * guide and his guests - are mostly at the same spot, so they do not see each
 * other's cameras, and nobody sees the camera of a guest who follows his
 * guide closely. See {@link #hidesCamera} and {@link #sync}.
 *
 * <p>Where {@link OutOfSight} works, a client is told that a player is gone
 * and keeps him in its tab list; otherwise he is hidden with
 * {@code hidePlayer}.</p>
 */
final class TourVisibility {

    private final CameraPlugin plugin;
    private final CameraPlayers cameraPlayers;
    /** The tours, asked who is on which; read only, {@link CamTour} changes them. */
    private final CamTour tours;
    /** The internals that hide players from a client and leave them in its tab list. */
    private final OutOfSight outOfSight;
    /** The players the tour hid from each viewer with {@code hidePlayer}, see {@link #sync}. */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    /** The players the client of each viewer was told are gone, see {@link OutOfSight}. */
    private final Map<UUID, Set<UUID>> forgotten = new HashMap<>();

    TourVisibility(CameraPlugin plugin, CamTour tours, PacketSender sender) {
        this.plugin = plugin;
        this.cameraPlayers = plugin.getCameraPlayers();
        this.tours = tours;
        this.outOfSight = new OutOfSight(sender);
    }

    /** At the start and after a reload: looks up the internals, see {@link OutOfSight#load}. */
    void load(ConsoleLog log) {
        outOfSight.load(log);
    }

    /** A player leaves: nobody is hidden from him any more, and he from nobody he would know of. */
    void forget(UUID player) {
        hidden.remove(player);
        forgotten.remove(player);
    }

    /**
     * Whether a tour keeps the camera of one player out of the sight of
     * another: the two are on the same tour, guide and guest or two guests -
     * or the other one is a guest who follows his guide closely, whom nobody
     * sees, see {@link CamTour#followsClosely}. The viewer sees neither his name nor
     * his particles then.
     */
    boolean hidesCamera(Player viewer, Player other) {
        if (viewer.equals(other)) {
            return false;
        }
        UUID tour = tours.tourOf(viewer.getUniqueId());
        return tour != null && tour.equals(tours.tourOf(other.getUniqueId())) || tours.followsClosely(other);
    }

    /**
     * Whether a tour keeps the player himself from the viewer, as
     * {@link #hidesCamera} keeps his camera: all but the guide from a guest
     * who sees through the guide's eyes.
     */
    boolean keepsApart(Player viewer, Player other) {
        // A guest turned along with his guide sees from right behind him: the
        // guide would stand in his view, so he is kept from him as well.
        return hidesCamera(viewer, other) && !(other.getUniqueId().equals(tours.guideIdOf(viewer.getUniqueId()))
                && tours.seesThroughGuide(viewer) && !tours.turnsAlong(viewer));
    }

    /**
     * Whether a tour hides the player from the viewer with {@code hidePlayer},
     * where {@link OutOfSight} does not work - visibility leaves the two alone
     * then. Where it works, the server goes on showing him, and visibility
     * goes on as for anybody.
     */
    boolean hidesPlayer(Player viewer, Player other) {
        return !outOfSight.isAvailable() && keepsApart(viewer, other);
    }

    /**
     * Hides from each one on a tour the others {@link #keepsApart} says, and
     * shows back whom the tour no longer hides - once the tour is over, say.
     * Asked every tick and after every change of a tour: photo mode or the
     * end of camera mode may have shown somebody back in between. Everybody
     * online is a viewer: outside the tour, nobody sees a guest who follows
     * closely.
     *
     * <p>Where {@link OutOfSight} works, the client is told they are gone and
     * they stay in its tab list. Otherwise they are hidden with
     * {@code hidePlayer}: only who the viewer sees at all, and only that is
     * shown back, as far as visibility, photo mode and {@code anti-scouting}
     * let it.</p>
     *
     * <p>While the plugin is being switched off - the server stops, say -
     * the tours only end: whom they no longer hide is shown back, nobody is
     * hidden anew. Bukkit turns {@code hidePlayer} down for a plugin that is
     * not enabled.</p>
     */
    void sync() {
        Map<UUID, Set<UUID>> wanted = new HashMap<>();
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Map.Entry<UUID, Tour> tour : tours.all().entrySet()) {
            List<Player> members = new ArrayList<>();
            members.add(Bukkit.getPlayer(tour.getKey()));
            for (UUID guest : tour.getValue().guests.keySet()) {
                members.add(Bukkit.getPlayer(guest));
            }
            members.removeIf(member -> member == null);
            for (Player viewer : online) {
                for (Player other : members) {
                    if (keepsApart(viewer, other)) {
                        wanted.computeIfAbsent(viewer.getUniqueId(), id -> new HashSet<>()).add(other.getUniqueId());
                    }
                }
            }
        }
        boolean listed = outOfSight.isAvailable();
        letGo(hidden, listed ? Map.of() : wanted, false);
        letGo(forgotten, listed ? wanted : Map.of(), true);
        if (!plugin.isEnabled()) {
            return;
        }
        for (Map.Entry<UUID, Set<UUID>> entry : wanted.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            List<Player> gone = new ArrayList<>();
            for (UUID id : entry.getValue()) {
                Player other = Bukkit.getPlayer(id);
                if (other == null) {
                    continue;
                }
                if (listed) {
                    if (forgotten.computeIfAbsent(viewer.getUniqueId(), key -> new HashSet<>()).add(id)) {
                        gone.add(other);
                    }
                } else if (viewer.canSee(other)) {
                    viewer.hidePlayer(plugin, other);
                    hidden.computeIfAbsent(viewer.getUniqueId(), key -> new HashSet<>()).add(id);
                }
            }
            // Straight away, not only at the end of the tick.
            outOfSight.hide(viewer, idsOf(gone));
        }
    }

    /**
     * Shows back to each viewer whom the tour hid from him and no longer
     * hides. A player his client was told is gone the server still showed
     * him: hidden and shown once more, the server pairs the two anew, and
     * the client gets him back.
     *
     * @param forgot whether they were hidden by {@link OutOfSight}, rather
     *               than with {@code hidePlayer}
     */
    private void letGo(Map<UUID, Set<UUID>> from, Map<UUID, Set<UUID>> wanted, boolean forgot) {
        for (Map.Entry<UUID, Set<UUID>> entry : new ArrayList<>(from.entrySet())) {
            Set<UUID> keep = wanted.getOrDefault(entry.getKey(), Set.of());
            Player viewer = Bukkit.getPlayer(entry.getKey());
            for (UUID id : new ArrayList<>(entry.getValue())) {
                if (keep.contains(id)) {
                    continue;
                }
                entry.getValue().remove(id);
                Player other = Bukkit.getPlayer(id);
                if (viewer == null || other == null) {
                    continue;
                }
                if (forgot) {
                    if (viewer.canSee(other)) {
                        pairAnew(viewer, other);
                    }
                } else if (cameraPlayers.contains(other.getUniqueId())) {
                    // As visibility has him: outside camera mode, a viewer
                    // may not see a camera player at all.
                    plugin.getVisibility().applyVisibility(other, viewer);
                } else if (!plugin.getPhotoMode().hides(viewer, other)
                        && !plugin.getScoutGuard().hides(viewer, other)) {
                    viewer.showPlayer(plugin, other);
                }
            }
            if (entry.getValue().isEmpty()) {
                from.remove(entry.getKey());
            }
        }
    }

    /**
     * Hides the other player from the viewer and shows the same player again
     * straight away: the server pairs the two anew and sends the player to
     * the client, which was told the player is gone, see {@link OutOfSight}.
     * Only for a player the viewer sees.
     *
     * <p>While the plugin is being switched off, Bukkit turns
     * {@code hidePlayer} down for it, though not {@code showPlayer}; the
     * clients would keep missing whom the tours ending then took from them,
     * as long as the server runs on - after a {@code /reload}, say. The old
     * form without a plugin is not turned down; hidden and shown again
     * straight away under no plugin's name, the player ends up just as the
     * pair under the plugin's name would leave things.</p>
     */
    @SuppressWarnings("deprecation")
    private void pairAnew(Player viewer, Player other) {
        if (plugin.isEnabled()) {
            viewer.hidePlayer(plugin, other);
            viewer.showPlayer(plugin, other);
        } else {
            viewer.hidePlayer(other);
            viewer.showPlayer(other);
        }
    }

    /**
     * Tells every client once more who is gone: the server sends a player
     * anew whenever it pairs him with a client again, see
     * {@link OutOfSight}. Only those the server shows him count, in his
     * world.
     */
    void forgetAgain() {
        for (Map.Entry<UUID, Set<UUID>> entry : forgotten.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null) {
                continue;
            }
            List<Player> gone = new ArrayList<>();
            for (UUID id : entry.getValue()) {
                Player other = Bukkit.getPlayer(id);
                if (other != null && viewer.canSee(other) && viewer.getWorld().equals(other.getWorld())) {
                    gone.add(other);
                }
            }
            outOfSight.hide(viewer, idsOf(gone));
        }
    }

    private static int[] idsOf(List<Player> players) {
        int[] ids = new int[players.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = players.get(i).getEntityId();
        }
        return ids;
    }
}
