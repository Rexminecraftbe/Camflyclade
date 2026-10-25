package de.elia.cameraplugin.signal;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.shader.PostEffects;
import de.elia.cameraplugin.shader.ShaderLook;
import de.elia.cameraplugin.shader.ShaderPack;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
 * is, and below {@code signal.noise-below} percent a post effect disturbs the
 * picture, {@code signal.noise}: CamFly's own shader, which grows worse in
 * stages the weaker the signal gets, see
 * {@link de.elia.cameraplugin.shader.ShaderPack} - or any other effect, one
 * of the game's or one of a resource pack.</p>
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
     * How far past the border to the next stage of the shader the signal has
     * to get, in percent, before the stage changes. Without it the picture
     * would jump between two stages while the camera hovers at the border,
     * and every jump is a new list of effects sent to the client.
     */
    private static final double STAGE_MARGIN = 1.5;

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
        /** The post effect of this class he wears, or {@code null}. */
        String effect;
        /** The stage of CamFly's shader he sees, 0 while he sees none. */
        int stage;
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

    /** Stops measuring for a player leaving camera mode, and takes the noise off his screen. */
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
        noise(player, link, link.strength * 100.0);
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
     * Lays the noise over his screen, changes its stage, or takes it off:
     * below {@code noise-below} percent it comes, and it goes again only once
     * the signal is {@link #NOISE_MARGIN} stronger than that.
     */
    private void noise(Player player, Link link, double percent) {
        int below = settings.getSignalNoiseBelow();
        boolean on = link.effect != null
                ? percent < Math.min(below + NOISE_MARGIN, 100.0)
                : percent < below;
        String wanted = on ? noiseEffect(player, link, percent, below) : null;
        if (Objects.equals(wanted, link.effect)) {
            return;
        }
        if (link.effect != null) {
            PostEffects.remove(player, link.effect);
        }
        if (wanted != null) {
            PostEffects.add(player, wanted);
        }
        link.effect = wanted;
        if (wanted == null) {
            link.stage = 0;
        }
    }

    /**
     * The effect of {@code signal.noise} for him: the one written there, or a
     * stage of CamFly's shader - as long as his client has the shader at
     * all, and {@code noise-fallback} otherwise, which it does know.
     */
    private String noiseEffect(Player player, Link link, double percent, int below) {
        String noise = settings.getSignalNoise();
        if (!CamSettings.CAMFLY_NOISE.equals(noise)) {
            return noise;
        }
        if (!plugin.getResourcePack().hasShader(player)) {
            link.stage = 0;
            return settings.getSignalNoiseFallback();
        }
        link.stage = stage(link.stage, percent, below, settings.getShaderLook().stages());
        return ShaderPack.effectKey(link.stage);
    }

    /**
     * The stage of the shader for this strength: 1 just under
     * {@code noise-below}, the last one when the signal is gone. A stage is
     * kept until the signal is {@link #STAGE_MARGIN} past the border to the
     * next one.
     *
     * @param current the stage he sees now, 0 for none
     */
    static int stage(int current, double percent, int below, int stages) {
        int next = stageAt(percent, below, stages);
        if (current < 1 || next == current) {
            return next;
        }
        int held = stageAt(next > current ? percent + STAGE_MARGIN : percent - STAGE_MARGIN, below, stages);
        return held == current ? current : next;
    }

    private static int stageAt(double percent, int below, int stages) {
        // 0 at noise-below, 1 with no signal at all.
        double depth = 1.0 - Math.max(0.0, percent) / below;
        return Math.max(1, Math.min(stages, (int) Math.ceil(depth * stages)));
    }

    /**
     * Takes every post effect this class may have laid over his screen off
     * him, whatever it was: for a player coming back from a crash in camera
     * mode, whose saved list still holds the noise, see
     * {@link de.elia.cameraplugin.crash.CamCrashGuard}.
     */
    public void forgetNoise(Player player) {
        for (int stage = 1; stage <= ShaderLook.MAX_STAGES; stage++) {
            PostEffects.remove(player, ShaderPack.effectKey(stage));
        }
        String noise = settings.getSignalNoise();
        if (noise != null && !noise.equals(CamSettings.CAMFLY_NOISE)) {
            PostEffects.remove(player, noise);
        }
        if (settings.getSignalNoiseFallback() != null) {
            PostEffects.remove(player, settings.getSignalNoiseFallback());
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

    /** Takes the noise of this class off his screen, if he has it. */
    private static void quiet(Player player, Link link) {
        if (link.effect != null) {
            // Nobody to send it to when he is gone: he left camera mode on
            // his way out, and that took it off already.
            if (player.isOnline()) {
                PostEffects.remove(player, link.effect);
            }
            link.effect = null;
            link.stage = 0;
        }
    }
}
