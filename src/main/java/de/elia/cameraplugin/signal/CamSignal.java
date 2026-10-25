package de.elia.cameraplugin.signal;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The radio signal of {@code camera-mode.max-distance-mode: signal}: the
 * camera is a drone, and its body sends the signal it flies on.
 *
 * <p>How far the signal reaches is {@code max-distance} in the open. Every
 * block it has to get through on the straight way from the body to the
 * camera takes {@code signal.block-cost} blocks of that away, see
 * {@link SignalPath} - under the ground and behind walls the camera gets
 * nowhere near as far as in the open air. The action bar shows how strong it
 * is, and below {@code signal.noise-below} percent the picture goes dark in
 * waves, as near a warden.</p>
 *
 * <p>Once it is gone, the camera has {@code signal.lost-seconds} to fly back
 * into it, counted down in the action bar. After that it goes back to its
 * body, or camera mode ends - {@code signal.lost}.</p>
 *
 * <p>In a world the body is not in, the signal comes from the portal the
 * camera came out of there, the spot {@code max-distance} is measured from
 * as well. In a world neither of them is in, there is no signal at all.</p>
 */
public final class CamSignal {

    /** How often the signal is measured, in ticks: as often as the action bar shows the distance. */
    private static final long INTERVAL = 4L;

    /**
     * How much stronger than {@code noise-below} the signal has to get again,
     * in percent, before the noise stops. Without that margin it would come
     * and go over and over while the camera flies along the edge.
     */
    private static final double NOISE_MARGIN = 5.0;

    /** The bars of {@code {bars}} in the action bar, one for each quarter of the signal. */
    private static final String[] BARS = {"▂", "▄", "▆", "█"};

    /**
     * The noise: darkness, the effect of the warden, without particles and
     * without an icon. Endless, and taken off again once the signal is back -
     * one that runs out would have to be given anew, and every time the
     * picture would go dark from the start.
     */
    private static final PotionEffect NOISE = new PotionEffect(
            PotionEffectType.DARKNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false);

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The signal of every camera player, by player id. */
    private final Map<UUID, Link> links = new HashMap<>();

    /** What is known about the signal of one camera player. */
    private static final class Link {
        BukkitRunnable task;
        /** How strong it was last measured, 0 to 1. */
        double strength = 1.0;
        /** Since when it is gone, as {@link System#currentTimeMillis()} counts, 0 while it is there. */
        long lostSince;
        /** The seconds the countdown showed last, -1 when it has not shown any. */
        long shownSeconds = -1L;
        /** Whether he wears the darkness of this class. */
        boolean noise;
    }

    public CamSignal(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Starts measuring the signal of a player who has just entered camera mode. */
    public void startFor(Player player) {
        stopFor(player);
        if (!settings.usesSignal()) {
            return;
        }
        UUID id = player.getUniqueId();
        Link link = new Link();
        link.task = new BukkitRunnable() {
            @Override
            public void run() {
                CameraData data = cameraPlayers.get(id);
                if (!player.isOnline() || data == null) {
                    this.cancel();
                    if (links.remove(id, link)) {
                        quiet(player, link);
                    }
                    return;
                }
                update(player, data, link);
            }
        };
        links.put(id, link);
        link.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
    }

    /**
     * Stops measuring for a player leaving camera mode, and takes the noise
     * off him. Called before his paused effects are given back, so that
     * darkness among them is not taken along.
     */
    public void stopFor(Player player) {
        Link link = links.remove(player.getUniqueId());
        if (link != null) {
            link.task.cancel();
            quiet(player, link);
        }
    }

    public void onDisable() {
        for (Link link : links.values()) {
            link.task.cancel();
        }
        links.clear();
    }

    /**
     * The part of the action bar that shows the signal, {@code actionbar-signal},
     * to go behind the distance to the body.
     *
     * @return the part, or {@code null} when there is no signal to show or
     *         its switch is off
     */
    public String actionBarPart(Player player) {
        Link link = links.get(player.getUniqueId());
        if (link == null || !messages.isMessageEnabled("actionbar-signal")) {
            return null;
        }
        double strength = link.strength;
        int lit = (int) Math.ceil(strength * BARS.length);
        ChatColor color = strength >= 0.5 ? ChatColor.GREEN : strength >= 0.25 ? ChatColor.YELLOW : ChatColor.RED;
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < BARS.length; i++) {
            bars.append(i < lit ? color : ChatColor.DARK_GRAY).append(BARS[i]);
        }
        return messages.getMessage("actionbar-signal")
                .replace("{bars}", bars)
                .replace("{percent}", String.valueOf((int) Math.ceil(strength * 100.0)));
    }

    /** One measurement, and what follows from it: the noise, the countdown, the end of the flight. */
    private void update(Player player, CameraData data, Link link) {
        link.strength = measure(player, data);
        double percent = link.strength * 100.0;
        int noiseBelow = settings.getSignalNoiseBelow();
        if (percent < noiseBelow) {
            // Darkness somebody else gave him is left on him, and not taken
            // for this one either: it is not this class's to take away later.
            if (!link.noise && !player.hasPotionEffect(PotionEffectType.DARKNESS)) {
                player.addPotionEffect(NOISE);
                link.noise = true;
            }
        } else if (percent >= Math.min(noiseBelow + NOISE_MARGIN, 100.0)) {
            quiet(player, link);
        }
        if (link.strength > 0.0) {
            if (link.lostSince != 0L) {
                link.lostSince = 0L;
                if (link.shownSeconds >= 0L) {
                    // The countdown would otherwise stand for its three
                    // seconds next to a signal that is back.
                    plugin.getActionBar().clear(player);
                }
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (link.lostSince == 0L) {
            link.lostSince = now;
            link.shownSeconds = -1L;
            settings.getSignalLostSound().play(player);
        }
        long left = settings.getSignalLostSeconds() * 1000L - (now - link.lostSince);
        if (left <= 0L) {
            lose(player, data, link);
            return;
        }
        long seconds = (left + 999L) / 1000L;
        if (seconds != link.shownSeconds) {
            link.shownSeconds = seconds;
            if (messages.isMessageEnabled("signal-lost")) {
                plugin.getActionBar().warn(player, messages.getMessage("signal-lost")
                        .replace("{time}", CamTimeLimit.formatDuration(seconds)));
            }
        }
    }

    /**
     * How strong the signal is at the camera, 0 to 1: from the eyes of the
     * body to the eyes of the camera - or, in another world, from the portal
     * it came out of, at the height of his eyes.
     */
    private double measure(Player player, CameraData data) {
        Location eyes = player.getEyeLocation();
        Location anchor = plugin.getMovementGuard().distanceAnchor(data, eyes);
        if (anchor == null) {
            return 0.0;
        }
        LivingEntity body = data.getBody();
        Location from = body.getWorld().equals(eyes.getWorld())
                ? body.getEyeLocation()
                : anchor.clone().add(0.0, player.getEyeHeight(), 0.0);
        return SignalPath.strength(from, eyes, settings.getMaxDistance(), settings.getSignalBlockCost());
    }

    /**
     * The signal has been gone for {@code lost-seconds}: back to the body, or
     * the end of camera mode.
     */
    private void lose(Player player, CameraData data, Link link) {
        link.lostSince = 0L;
        if (settings.getSignalLoss() == SignalLoss.BACK && plugin.getMovementGuard().returnToBody(player, data)) {
            settings.getBackSound().play(player);
            if (messages.isMessageEnabled("signal-back-to-body")) {
                plugin.getActionBar().flash(player, messages.getMessage("signal-back-to-body"));
            }
            return;
        }
        // Also when the way back was turned down, by another plugin say: the
        // camera would otherwise stay out there without a signal for good.
        plugin.exitCameraMode(player, EndReason.SIGNAL);
        messages.sendMessage(player, "signal-ended");
    }

    /** Takes the darkness of this class off him, if he wears it. */
    private static void quiet(Player player, Link link) {
        if (link.noise) {
            link.noise = false;
            player.removePotionEffect(PotionEffectType.DARKNESS);
        }
    }
}
