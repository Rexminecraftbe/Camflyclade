package de.elia.cameraplugin.resume;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.spot.JumpCheck;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
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
 * <p>Where each flight ends is noted when camera mode ends, whatever ends it.</p>
 *
 * <p>The camera is put down there only where it could fly to, see
 * {@link JumpCheck}: in the world of the body, within {@code max-distance} of
 * the body - the new one, where he stands now -, where the signal reaches, where {@code cam-area} lets it, out of shut lava,
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
    private final JumpCheck jumpCheck;
    /** Where the last flight of each player ended, on {@code storage: memory}. */
    private final Map<UUID, LastFlight> inMemory = new HashMap<>();

    public CamResume(CameraPlugin plugin, PlayerStore store, JumpCheck jumpCheck) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
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
        LastFlight flight = LastFlight.at(cameraAt);
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
     * @return where the camera goes on, or {@code null} when it may not; he
     *         has been told why
     */
    private Location planned(Player player, CameraData data, LastFlight flight) {
        World home = data != null ? data.getBody().getWorld() : player.getWorld();
        Location target = flight.toLocation();
        if (target == null || !target.getWorld().equals(home)) {
            messages.sendConfiguredMessage(player, "resume-other-world");
            return null;
        }
        Location anchor = data != null ? data.getBody().getLocation() : player.getLocation();
        Location signalFrom = data != null ? data.getBody().getEyeLocation() : player.getEyeLocation();
        if (!jumpCheck.mayGo(player, anchor, signalFrom, target, "resume")) {
            return null;
        }
        return target;
    }

    /** Puts the camera down where the flight ended. */
    private void go(Player player, CameraData data, LastFlight flight) {
        Location target = planned(player, data, flight);
        if (target == null) {
            return;
        }
        // A jump of his own takes him off a tour he follows.
        plugin.getTour().leave(player);
        if (!plugin.getMovementGuard().jumpTo(player, data, target)) {
            return;
        }
        // Where his last flight ended is no spot he jumped to.
        plugin.getSpots().dropLook(player);
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
