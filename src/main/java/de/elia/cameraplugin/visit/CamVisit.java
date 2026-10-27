package de.elia.cameraplugin.visit;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.spot.JumpCheck;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.SharedSpots;
import de.elia.cameraplugin.spot.SpotModeration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.UUID;

/**
 * The visitor camera, {@code /cam visit Owner:spot}: the camera goes to a spot
 * another player shares with him, from wherever he is - to look at the build
 * of a friend who shows it to him.
 *
 * <p>His body stays where he is. While the visit lasts, the spot takes the
 * place of the body: {@code max-distance}, the signal and the wall go out
 * from it, so the camera looks round the build it was shown and goes no
 * further than it would from its own body. {@code /cam back} ends the visit
 * and takes it back to the body.</p>
 *
 * <p>What keeps it fair: only a spot its owner shared with him, which its
 * owner saved where he was himself; in the world of his body; and only where
 * the camera may be at all - not in an area {@code cam-area} keeps it out of,
 * not in shut lava, water or powder snow, not in a block. Whoever takes the
 * share back, or deletes the spot, takes back the visit as well: the camera
 * goes back to its body. Starting camera mode for a visit asks everything a
 * start of {@code /cam} asks, and costs what it costs.</p>
 */
public final class CamVisit {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final SharedSpots shared;
    private final JumpCheck jumpCheck;

    public CamVisit(CameraPlugin plugin, SharedSpots shared, JumpCheck jumpCheck) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.shared = shared;
        this.jumpCheck = jumpCheck;
    }

    /** {@code /cam visit Owner:spot}. */
    public void command(Player player, String[] args) {
        if (!settings.isVisitorCameraEnabled()) {
            messages.sendConfiguredMessage(player, "visit-off");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(player, "visit-usage");
            return;
        }
        Share share = shared.find(player.getUniqueId(), args[0]);
        if (share == null) {
            messages.sendConfiguredMessage(player, "spot-shared-unknown", "{spot}", args[0]);
            return;
        }
        visit(player, share);
    }

    /**
     * Takes the camera to a spot shared with him: at once in camera mode, or
     * camera mode starts first, with its checks, its cost and its wait.
     */
    public void visit(Player player, Share share) {
        if (!settings.isVisitorCameraEnabled()) {
            messages.sendConfiguredMessage(player, "visit-off");
            return;
        }
        if (!plugin.getSpotModeration().check(player, SpotModeration.What.VIEW)) {
            return;
        }
        if (planned(player, share) == null) {
            return;
        }
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (data != null) {
            go(player, data, share);
            return;
        }
        // Asked once more when camera mode has started: the wait of
        // start-delay is long enough for somebody to build at the spot.
        plugin.getStarter().startThen(player, () -> {
            CameraData started = plugin.getCameraPlayers().get(player.getUniqueId());
            if (started != null) {
                go(player, started, share);
            }
        });
    }

    /**
     * The visit to a share, if the camera may go there: the spot in the world
     * of his body - where he stands, before camera mode -, where the camera
     * may be.
     *
     * @return the visit, or {@code null} when it may not; he has been told why
     */
    private Visit planned(Player player, Share share) {
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        World home = data != null ? data.getBody().getWorld() : player.getWorld();
        Location target = share.getSpot().toLocation();
        if (target == null || !target.getWorld().equals(home)) {
            messages.sendConfiguredMessage(player, "visit-other-world", "{spot}", share.label());
            return null;
        }
        if (!jumpCheck.mayStand(player, target, "visit", "{spot}", share.label())) {
            return null;
        }
        return new Visit(share.getOwner(), share.getOwnerName(), share.getSpot().key(), share.getSpot().name(),
                target);
    }

    /** Puts the camera down at the spot of a share and holds it from there. */
    private void go(Player player, CameraData data, Share share) {
        Visit visit = planned(player, share);
        if (visit == null) {
            return;
        }
        // A jump of his own takes him off a tour he follows.
        plugin.getTour().leave(player);
        if (!plugin.getMovementGuard().jumpTo(player, data, visit.anchor(), visit)) {
            return;
        }
        plugin.getSpots().viewed(player, share.getOwner(), share.getSpot());
        settings.getSpotSound().play(player);
        if (messages.isMessageEnabled("visit-started")) {
            plugin.getActionBar().flash(player, messages.getMessage("visit-started")
                    .replace("{owner}", visit.ownerName()).replace("{spot}", visit.spotName()));
        }
    }

    /**
     * Takes the camera of everybody who visits this spot and may no longer -
     * its owner took the share back, or deleted the spot - back to his body.
     */
    public void checkVisitors(UUID owner, String key) {
        Share share = shared.get(owner, key);
        for (UUID id : new ArrayList<>(plugin.getCameraPlayers().ids())) {
            CameraData data = plugin.getCameraPlayers().get(id);
            Visit visit = data == null ? null : data.getVisit();
            Player visitor = Bukkit.getPlayer(id);
            if (visit == null || visitor == null || !visit.owner().equals(owner) || !visit.spotKey().equals(key)
                    || (share != null && share.isFor(id) && settings.isVisitorCameraEnabled())) {
                continue;
            }
            plugin.getTour().leave(visitor);
            if (plugin.getMovementGuard().returnToBody(visitor, data)) {
                settings.getBackSound().play(visitor);
            }
            messages.sendConfiguredMessage(visitor, "visit-ended", "{owner}", visit.ownerName(),
                    "{spot}", visit.spotName());
        }
    }
}
