package de.elia.cameraplugin.resume;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.spot.JumpCheck;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.SharedSpots;
import de.elia.cameraplugin.spot.SpotModeration;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.visit.Visit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /cam resume}: the camera goes on where the last flight ended, looking
 * the way it looked - for a build looked at a bit at a time, with a walk in
 * between.
 *
 * <p>Where each flight ends is noted when camera mode ends, whatever ends it.
 * A flight that ended on a visit of {@code /cam visit} goes on as that visit,
 * as long as the spot is still shared with him.</p>
 *
 * <p>The camera is put down there only where it could fly to, see
 * {@link JumpCheck}: in the world of the body, within {@code max-distance} of
 * the body - the new one, where he stands now - or of the spot it visits,
 * where the signal reaches, where {@code cam-area} lets it, out of shut lava,
 * water and powder snow and not into a block. Before camera mode that is
 * asked before it starts, so that nobody pays for a start that only ends at
 * his body; and once more when it has started, in case the wait of
 * {@code start-delay} changed anything.</p>
 *
 * <p>Where it is kept is {@code resume-command.storage}: in the file of the
 * player, or in memory until a restart.</p>
 */
public final class CamResume {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final SharedSpots shared;
    private final JumpCheck jumpCheck;
    /** Where the last flight of each player ended, on {@code storage: memory}. */
    private final Map<UUID, LastFlight> inMemory = new HashMap<>();

    /** Where the camera goes on, and what it is held from there. */
    private record Plan(Location target, Visit visit) {
    }

    public CamResume(CameraPlugin plugin, PlayerStore store, SharedSpots shared, JumpCheck jumpCheck) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
        this.shared = shared;
        this.jumpCheck = jumpCheck;
    }

    private boolean inFile() {
        return settings.getResumeStorage() == StorageMode.FILE;
    }

    /** Whether {@code /cam resume} has somewhere to go for this player. */
    public boolean hasFlight(Player player) {
        return settings.isResumeEnabled() && lastFlight(player) != null;
    }

    private LastFlight lastFlight(Player player) {
        return inFile() ? store.get(player.getUniqueId()).getLastFlight() : inMemory.get(player.getUniqueId());
    }

    /**
     * Notes where a flight ended, as camera mode ends. Written to the file
     * with the rest of it, at the end of camera mode.
     *
     * @param cameraAt where the camera was, before it is put back at the body
     */
    public void remember(Player player, CameraData data, Location cameraAt) {
        if (!settings.isResumeEnabled() || cameraAt.getWorld() == null) {
            return;
        }
        Visit visit = data.getVisit();
        LastFlight flight = LastFlight.at(cameraAt, visit == null ? null : visit.owner(),
                visit == null ? null : visit.spotKey());
        if (inFile()) {
            store.get(player.getUniqueId()).setLastFlight(flight);
        } else {
            inMemory.put(player.getUniqueId(), flight);
        }
    }

    /** {@code /cam resume}: in camera mode at once, otherwise camera mode starts first. */
    public void command(Player player) {
        if (!settings.isResumeEnabled()) {
            messages.sendConfiguredMessage(player, "resume-off");
            return;
        }
        LastFlight flight = lastFlight(player);
        if (flight == null) {
            messages.sendConfiguredMessage(player, "resume-none");
            return;
        }
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (planned(player, data, flight) == null) {
            return;
        }
        if (data != null) {
            go(player, data, flight);
            return;
        }
        plugin.getStarter().startThen(player, () -> {
            CameraData started = plugin.getCameraPlayers().get(player.getUniqueId());
            if (started != null) {
                go(player, started, flight);
            }
        });
    }

    /**
     * Where the camera goes on, if it may: before camera mode measured from
     * where he stands, which is where his body is going to be.
     *
     * @param data his camera mode, or {@code null} before it
     * @return the plan, or {@code null} when it may not; he has been told why
     */
    private Plan planned(Player player, CameraData data, LastFlight flight) {
        World home = data != null ? data.getBody().getWorld() : player.getWorld();
        Location target = flight.toLocation();
        if (target == null || !target.getWorld().equals(home)) {
            messages.sendConfiguredMessage(player, "resume-other-world");
            return null;
        }
        Visit visit = null;
        Location anchor;
        Location signalFrom;
        if (flight.wasVisit()) {
            // Going on with a visit is a visit.
            if (!plugin.getSpotModeration().check(player, SpotModeration.What.VIEW)) {
                return null;
            }
            Share share = shared.get(flight.visitOwner(), flight.visitSpot());
            Location spot = share == null ? null : share.getSpot().toLocation();
            if (!settings.isVisitorCameraEnabled() || share == null || !share.isFor(player.getUniqueId())
                    || spot == null || !spot.getWorld().equals(home)) {
                messages.sendConfiguredMessage(player, "resume-visit-gone");
                return null;
            }
            visit = new Visit(share.getOwner(), share.getOwnerName(), share.getSpot().key(),
                    share.getSpot().name(), spot);
            anchor = visit.anchor();
            signalFrom = visit.anchor().add(0.0, player.getEyeHeight(), 0.0);
        } else if (data != null) {
            anchor = data.getBody().getLocation();
            signalFrom = data.getBody().getEyeLocation();
        } else {
            anchor = player.getLocation();
            signalFrom = player.getEyeLocation();
        }
        if (!jumpCheck.mayGo(player, anchor, signalFrom, target, "resume")) {
            return null;
        }
        return new Plan(target, visit);
    }

    /** Puts the camera down where the flight ended and holds it from there. */
    private void go(Player player, CameraData data, LastFlight flight) {
        Plan plan = planned(player, data, flight);
        if (plan == null) {
            return;
        }
        // A jump of his own takes him off a tour he follows.
        plugin.getTour().leave(player);
        if (!plugin.getMovementGuard().jumpTo(player, data, plan.target(), plan.visit())) {
            return;
        }
        settings.getSpotSound().play(player);
        if (messages.isMessageEnabled("resume-jumped")) {
            plugin.getActionBar().flash(player, messages.getMessage("resume-jumped"));
        }
    }

    /** Forgets every flight kept in memory, when the plugin is switched off. */
    public void onDisable() {
        inMemory.clear();
    }
}
