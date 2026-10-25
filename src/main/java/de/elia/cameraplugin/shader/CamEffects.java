package de.elia.cameraplugin.shader;

import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The effects of {@code shader-effects} over the picture of a camera player:
 * the camera switching on and off and a nudge, each an animation, and the
 * effects that come in stages - the last seconds before the end, a body low
 * on health, the border close by.
 *
 * <p>An animation lays one post effect over the screen each tick and takes
 * the one before off; a new one takes the place of one still running. The
 * camera switching off begins before the player is put back at the body and
 * goes on after camera mode, over the picture there. The effects in stages
 * are looked at four times a second while camera mode runs; the time left,
 * for {@code end-warning}, is handed over by the time limit and the battery,
 * see {@link de.elia.cameraplugin.timelimit.EndWarning}.</p>
 *
 * <p>Only for a player whose client has CamFly's shader, see
 * {@link CamResourcePack#hasShader(Player)}, and only on Paper, see
 * {@link PostEffects#direct()}. Nobody else sees anything of them: unlike the
 * noise of a weak signal they have no effect of the game to fall back on, and
 * they say nothing the sounds and the action bar do not say already.</p>
 *
 * <p>The server keeps the post effects of a player and saves them with the
 * player. So they come off as the player leaves, and whatever of CamFly's a
 * player still has on joining - after a crash - comes off then.</p>
 */
public final class CamEffects implements Listener {

    /** How often the effects in stages are looked at, in ticks. */
    private static final long INTERVAL = 4L;

    /**
     * How far past the border to the next stage the cause has to get before
     * the stage changes, as a share of the whole way from none to the last
     * stage. Without it a camera hovering at that border would make the stage
     * jump back and forth, and every jump is a packet.
     */
    private static final double STAGE_MARGIN = 0.05;

    /**
     * How much farther than {@code within-blocks} the camera has to get before
     * the effect of the border goes again, in blocks, for the same reason.
     */
    private static final double BORDER_MARGIN = 0.5;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    /** What lies over the screen of each player, by player id. */
    private final Map<UUID, Screen> screens = new HashMap<>();

    /** What lies over the screen of one player. */
    private static final class Screen {
        /** The look at the effects in stages while camera mode runs, or {@code null}. */
        BukkitRunnable watch;
        /** The stage of each effect in stages on the screen; one that is not there is not shown. */
        final Map<ShaderEffect, Integer> stages = new EnumMap<>(ShaderEffect.class);
        /** The animation running, or {@code null}. */
        Animation animation;
        /** The post effect of that animation on the screen now, or {@code null}. */
        String frame;

        boolean idle() {
            return watch == null && animation == null && stages.isEmpty();
        }
    }

    public CamEffects(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
    }

    /** Camera mode has just started: the camera switches on, and the effects in stages are looked at. */
    public void startFor(Player player) {
        Screen screen = screen(player);
        stopWatch(player, screen);
        play(player, screen, ShaderEffect.START);
        if (!settings.getShaderEffect(ShaderEffect.LOW_HEALTH).enabled()
                && !settings.getShaderEffect(ShaderEffect.BORDER).enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        BukkitRunnable watch = new BukkitRunnable() {
            @Override
            public void run() {
                CameraData data = plugin.getCameraPlayers().get(id);
                if (!player.isOnline() || data == null) {
                    // Camera mode ended without saying so; stopFor tidies up
                    // whatever is still there.
                    stopFor(player);
                    return;
                }
                look(player, data, screen);
            }
        };
        screen.watch = watch;
        watch.runTaskTimer(plugin, INTERVAL, INTERVAL);
    }

    /**
     * Camera mode is ending, for this reason: the camera switches off as the
     * sound of the end goes - {@code end}, {@code end-body} or
     * {@code end-forced}. Called before the player is put back at the body,
     * so that the first picture already lies over the move.
     */
    public void ending(Player player, EndReason reason) {
        ShaderEffect effect = ShaderEffect.ending(reason);
        if (effect != null && !plugin.isShuttingDown()) {
            play(player, screen(player), effect);
        }
    }

    /** Camera mode has ended: the effects in stages come off. An animation still running goes on. */
    public void stopFor(Player player) {
        Screen screen = screens.get(player.getUniqueId());
        if (screen != null) {
            stopWatch(player, screen);
            forgetIfIdle(player, screen);
        }
    }

    /** Somebody has nudged the body of this camera player. */
    public void nudged(Player owner) {
        play(owner, screen(owner), ShaderEffect.NUDGE);
    }

    /**
     * The time left before the time limit or the battery ends camera mode,
     * for {@code end-warning}: in its last {@code seconds} the stages grow.
     *
     * @param left the time left, in seconds
     */
    public void timeLeft(Player player, double left) {
        if (!plugin.isInCameraMode(player)) {
            return;
        }
        EffectSettings warning = settings.getShaderEffect(ShaderEffect.END_WARNING);
        double window = settings.getEffectWarningSeconds();
        int stage = 0;
        if (warning.enabled() && left > 0.0 && left <= window && shows(player)) {
            stage = stageAt(1.0 - left / window, warning.steps());
        }
        Screen screen = screen(player);
        setStage(player, screen, ShaderEffect.END_WARNING, stage);
        forgetIfIdle(player, screen);
    }

    public void onDisable() {
        for (Map.Entry<UUID, Screen> entry : screens.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            clear(player, entry.getValue());
        }
        screens.clear();
    }

    /** Whatever of CamFly's the player still has from before - a crash, say - comes off. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PostEffects.removeAll(event.getPlayer(), ShaderPack.NAMESPACE);
    }

    /** Off before the server saves the player, or it would put them back on at the next join. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Screen screen = screens.remove(event.getPlayer().getUniqueId());
        if (screen != null) {
            clear(event.getPlayer(), screen);
        }
    }

    /** One look at the effects in stages: the health of the body and the border. */
    private void look(Player player, CameraData data, Screen screen) {
        boolean shows = shows(player);
        EffectSettings lowHealth = settings.getShaderEffect(ShaderEffect.LOW_HEALTH);
        int stage = 0;
        double below = settings.getEffectLowHealthBelow();
        if (lowHealth.enabled() && shows && player.getHealth() < below) {
            stage = stageAt(1.0 - player.getHealth() / below, lowHealth.steps());
        }
        setStage(player, screen, ShaderEffect.LOW_HEALTH, stage);

        EffectSettings border = settings.getShaderEffect(ShaderEffect.BORDER);
        int current = screen.stages.getOrDefault(ShaderEffect.BORDER, 0);
        stage = 0;
        if (border.enabled() && shows) {
            double within = settings.getEffectBorderWithin();
            double reach = current > 0 ? within + BORDER_MARGIN : within;
            double distance = borderDistance(player, data, reach);
            if (distance < reach) {
                stage = held(current, 1.0 - distance / within, border.steps());
            }
        }
        setStage(player, screen, ShaderEffect.BORDER, stage);
    }

    /**
     * How far the camera is from the border, in blocks: from
     * {@code max-distance} under {@code max-distance-mode: fixed}, and from
     * the forbidden biomes and structures {@code cam-area} keeps it out of.
     * Only borders that hold count, the ones that stop the camera.
     *
     * @return the distance, or {@link Double#POSITIVE_INFINITY} for none
     *         within {@code reach}
     */
    private double borderDistance(Player player, CameraData data, double reach) {
        Location at = player.getLocation();
        double best = Double.POSITIVE_INFINITY;
        if (settings.limitsDistance()) {
            Location anchor = plugin.getMovementGuard().distanceAnchor(data, at);
            if (anchor != null) {
                best = Math.max(0.0, settings.getMaxDistance() - at.distance(anchor));
            }
        }
        CamAreaRules rules = settings.getCamAreaRules();
        if (best > 0.0 && settings.keepsOutOfAreas() && rules.forbidsBlocks()) {
            best = Math.min(best, rules.distanceToForbidden(at.getWorld(), player.getBoundingBox(),
                    Math.min(reach, best)));
        }
        return best;
    }

    /** Puts the effect on the screen at this stage, or takes it off with 0. */
    private static void setStage(Player player, Screen screen, ShaderEffect effect, int stage) {
        int current = screen.stages.getOrDefault(effect, 0);
        if (current == stage) {
            return;
        }
        if (current > 0) {
            PostEffects.remove(player, ShaderPack.effectKey(effect, current));
        }
        if (stage > 0) {
            PostEffects.add(player, ShaderPack.effectKey(effect, stage));
            screen.stages.put(effect, stage);
        } else {
            screen.stages.remove(effect);
        }
    }

    /**
     * The stage for this depth, 0 being where the effect starts and 1 the
     * worst: the first stage right from the start, the last at the worst.
     */
    static int stageAt(double depth, int stages) {
        return Math.max(1, Math.min(stages, (int) Math.ceil(depth * stages)));
    }

    /**
     * The stage for this depth, keeping the current one until the depth is
     * {@link #STAGE_MARGIN} past the border to the next.
     *
     * @param current the stage on the screen now, 0 for none
     */
    static int held(int current, double depth, int stages) {
        int next = stageAt(depth, stages);
        if (current < 1 || next == current) {
            return next;
        }
        int kept = stageAt(next > current ? depth - STAGE_MARGIN : depth + STAGE_MARGIN, stages);
        return kept == current ? current : next;
    }

    /** Starts an animation, in place of one still running - the first picture in this very tick. */
    private void play(Player player, Screen screen, ShaderEffect effect) {
        stopAnimation(player, screen);
        EffectSettings animation = settings.getShaderEffect(effect);
        if (!animation.enabled() || !shows(player)) {
            forgetIfIdle(player, screen);
            return;
        }
        Animation running = new Animation(player, screen, effect, animation.steps());
        screen.animation = running;
        running.advance();
        running.runTaskTimer(plugin, 1L, 1L);
    }

    /** One animation on the screen of one player, a post effect each tick. */
    private final class Animation extends BukkitRunnable {

        private final Player player;
        private final Screen screen;
        private final ShaderEffect effect;
        private final int frames;
        private int frame;

        Animation(Player player, Screen screen, ShaderEffect effect, int frames) {
            this.player = player;
            this.screen = screen;
            this.effect = effect;
            this.frames = frames;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                // The quit took it off already.
                cancel();
                return;
            }
            advance();
            if (screen.animation != this) {
                cancel();
                forgetIfIdle(player, screen);
            }
        }

        /** The next picture, or the end of it all after the last. */
        void advance() {
            frame++;
            String next = frame <= frames ? ShaderPack.effectKey(effect, frame) : null;
            if (screen.frame != null) {
                PostEffects.remove(player, screen.frame);
            }
            if (next != null) {
                PostEffects.add(player, next);
            } else {
                screen.animation = null;
            }
            screen.frame = next;
        }
    }

    private static void stopAnimation(Player player, Screen screen) {
        if (screen.animation != null) {
            screen.animation.cancel();
            screen.animation = null;
        }
        if (screen.frame != null) {
            if (player.isOnline()) {
                PostEffects.remove(player, screen.frame);
            }
            screen.frame = null;
        }
    }

    private static void stopWatch(Player player, Screen screen) {
        if (screen.watch != null) {
            screen.watch.cancel();
            screen.watch = null;
        }
        if (player.isOnline()) {
            for (ShaderEffect effect : new ArrayList<>(screen.stages.keySet())) {
                setStage(player, screen, effect, 0);
            }
        }
        screen.stages.clear();
    }

    /** Stops everything on this screen and takes it off, as far as the player is still there. */
    private static void clear(Player player, Screen screen) {
        if (player == null) {
            if (screen.watch != null) {
                screen.watch.cancel();
            }
            if (screen.animation != null) {
                screen.animation.cancel();
            }
            return;
        }
        stopWatch(player, screen);
        stopAnimation(player, screen);
    }

    /** Whether this player sees CamFly's shader at all. */
    private boolean shows(Player player) {
        return plugin.getResourcePack().hasShader(player);
    }

    private Screen screen(Player player) {
        return screens.computeIfAbsent(player.getUniqueId(), id -> new Screen());
    }

    private void forgetIfIdle(Player player, Screen screen) {
        if (screen.idle()) {
            screens.remove(player.getUniqueId(), screen);
        }
    }
}
