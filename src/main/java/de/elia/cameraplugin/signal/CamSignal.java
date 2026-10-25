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
 * camera takes {@code signal.block-cost} blocks of that away, or what
 * {@code signal.block-costs} says for it, see {@link SignalPath} and
 * {@link SignalCosts} - under the ground and behind walls the camera gets
 * nowhere near as far as in the open air, and through bedrock not at all. The action bar shows how strong it
 * is, the way the game shows a connection: the ping icon of the tab list, one
 * bar to five or a red cross, and a word for it.</p>
 *
 * <p>Once it is gone, the camera has {@code signal.lost-seconds} to fly back
 * into it, counted down in the action bar, with {@code sounds.signal-lost} as
 * it goes and {@code sounds.signal-back} should it come back in time. After
 * that it goes back to its body, or camera mode ends - {@code signal.lost}.</p>
 *
 * <p>In a world the body is not in, the signal comes from the portal the
 * camera came out of there, the spot {@code max-distance} is measured from
 * as well. In a world neither of them is in, there is no signal at all.</p>
 */
public final class CamSignal {

    /** How often the signal is measured, in ticks: as often as the action bar shows the distance. */
    private static final long INTERVAL = 4L;

    /** The bars of {@code {bars}} in the action bar, one for each quarter of the signal. */
    private static final String[] BARS = {"▂", "▄", "▆", "█"};

    /**
     * The pictures of {@code {icon}}, by {@link #level}: the ping icons of the
     * game's gui, which the tab list shows for a connection - the red cross
     * for none, then one bar to five.
     */
    private static final String[] ICONS = {"icon/ping_unknown", "icon/ping_1", "icon/ping_2", "icon/ping_3",
            "icon/ping_4", "icon/ping_5"};

    /** The words of {@code {quality}}, by {@link #level}, as the language file names them. */
    private static final String[] QUALITIES = {"signal-quality-none", "signal-quality-very-weak",
            "signal-quality-weak", "signal-quality-medium", "signal-quality-good", "signal-quality-excellent"};

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
                    links.remove(id, link);
                    return;
                }
                update(player, data, link);
            }
        };
        links.put(id, link);
        link.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
    }

    /** Stops measuring for a player leaving camera mode. */
    public void stopFor(Player player) {
        Link link = links.remove(player.getUniqueId());
        if (link != null) {
            link.task.cancel();
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
     * to go behind the distance to the body. {@code {icon}} is left in it:
     * a picture, which only the action bar itself can put in, see
     * {@link #icon(Player)}.
     *
     * @return the part, or {@code null} when there is no signal to show or
     *         its switch is off
     */
    public String actionBarPart(Player player) {
        Link link = links.get(player.getUniqueId());
        if (link == null || !messages.isMessageEnabled("actionbar-signal")) {
            return null;
        }
        return messages.getMessage("actionbar-signal")
                .replace("{bars}", bars(link.strength))
                .replace("{quality}", messages.getMessage(QUALITIES[level(link.strength)]))
                .replace("{percent}", String.valueOf(percent(link.strength)));
    }

    /**
     * The picture of {@code {icon}}: the ping icon of the game's gui for how
     * strong the signal is, out of the atlas {@code minecraft:gui}.
     *
     * @return its name, e.g. {@code icon/ping_3}, or {@code null} when there
     *         is no signal to show
     */
    public String icon(Player player) {
        Link link = links.get(player.getUniqueId());
        return link == null ? null : ICONS[level(link.strength)];
    }

    /**
     * {@code {icon}} written in letters, where the server cannot show a
     * picture in the action bar - on Spigot: the bars of {@code {bars}}.
     */
    public String iconLetters(Player player) {
        Link link = links.get(player.getUniqueId());
        return link == null ? "" : bars(link.strength);
    }

    /**
     * How strong a signal is in steps, as the icons and words go: 0 when it is
     * gone, 5 from the percent {@code signal.levels.excellent} names, 4 from
     * {@code good}, 3 from {@code medium}, 2 from {@code weak}, and 1 below.
     * Measured on the percent {@code {percent}} shows, so that both agree.
     */
    private int level(double strength) {
        if (strength <= 0.0) {
            return 0;
        }
        int percent = percent(strength);
        double[] levels = settings.getSignalLevels();
        for (int i = 0; i < levels.length; i++) {
            if (percent >= levels[i]) {
                return ICONS.length - 1 - i;
            }
        }
        return 1;
    }

    /** The strength in whole percent, rounded up: a signal that is there at all shows at least 1. */
    private static int percent(double strength) {
        return (int) Math.ceil(strength * 100.0);
    }

    /** Four bars, as many of them lit as the signal is strong, green, yellow or red. */
    private static String bars(double strength) {
        int lit = (int) Math.ceil(strength * BARS.length);
        ChatColor color = strength >= 0.5 ? ChatColor.GREEN : strength >= 0.25 ? ChatColor.YELLOW : ChatColor.RED;
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < BARS.length; i++) {
            bars.append(i < lit ? color : ChatColor.DARK_GRAY).append(BARS[i]);
        }
        return bars.toString();
    }

    /** One measurement, and what follows from it: the countdown, the end of the flight. */
    private void update(Player player, CameraData data, Link link) {
        link.strength = measure(player, data);
        if (link.strength > 0.0) {
            if (link.lostSince != 0L) {
                // Back in the signal within lost-seconds. Not after going
                // back to the body, which starts afresh, see lose().
                link.lostSince = 0L;
                settings.getSignalBackSound().play(player);
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
        return SignalPath.strength(from, eyes, settings.getMaxDistance(), settings.getSignalCosts());
    }

    /**
     * Whether the signal reaches a spot the camera is to be put down at, a
     * spot of {@code /cam spot}: measured the way {@link #measure} measures
     * it at the camera, from the eyes of the body to the eyes of the camera
     * there. Only in the world of the body, the one a spot is jumped to.
     *
     * @param feet where the feet of the camera would be
     */
    public boolean reaches(Player player, CameraData data, Location feet) {
        LivingEntity body = data.getBody();
        if (!body.getWorld().equals(feet.getWorld())) {
            return false;
        }
        Location eyes = feet.clone().add(0.0, player.getEyeHeight(), 0.0);
        return SignalPath.strength(body.getEyeLocation(), eyes, settings.getMaxDistance(),
                settings.getSignalCosts()) > 0.0;
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
}
