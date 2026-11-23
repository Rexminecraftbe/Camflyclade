package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.ClientFrost;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Takes the cameras of the guests of every tour of {@link CamTour} along
 * after their guide, every tick while there is a tour at all: each flies his
 * way two ticks after him, see {@link TourTrail}, or counts down while the
 * guide is out of its reach, see {@link #follow}. Here, too, a guest sees
 * through the eyes of the guide, see {@link #updateView}, and takes the
 * guide's sky for his own, see {@link #takeLook}.
 */
final class TourFollowing {

    /**
     * How far the camera of a guest may be from where it should be, see
     * {@link TourTrail}, before it is put next to the guide, in blocks. Flying on
     * the speed it is given, it is off by a block at most; beyond this,
     * something else moved one of the two.
     */
    private static final double CATCH_UP = 6.0;

    /**
     * How much of the gap to where his camera should be, see {@link TourTrail},
     * the speed of a guest closes each tick, on top of the step the guide took
     * there: of the gap that is left after what his camera flew already, see
     * {@link #ahead}. That is only a guess - his ping changes, a wall stops
     * his camera -, so a whole gap would make the camera overshoot and swing.
     */
    private static final double PULL = 0.3;

    /**
     * The most ticks the client of a guest is taken to be behind, see
     * {@link #ahead}: a second.
     */
    private static final int MAX_LAG = 20;

    /** The fastest a guest is sent after the guide, in blocks per tick. */
    private static final double MAX_SPEED = 4.0;

    /**
     * How often the guide's view is sent again while it holds, in ticks. A
     * client that lost sight of the guide for a moment - after a jump, say -
     * would otherwise keep looking from its own camera.
     */
    private static final int VIEW_REFRESH = 20;

    /**
     * How long a guest is held turned while he still looks through his own
     * eyes, before he sees through the guide's, in ticks. His client counts
     * his turns on and on - one and a half turns is 540 degrees -, the server
     * as -180 to 180: turned to where the server has him, he is put back by
     * whole turns, and the items in his hands sway after that by a tenth of
     * it. Through his own eyes his client brings them back to the middle,
     * halfway each tick; through the guide's it no longer does. So they are
     * centred first.
     */
    private static final int CENTER_TICKS = 10;

    /**
     * How often a guest's frost is sent to his client again, in ticks: sent
     * after the guide, his camera may have been in powder snow on his client
     * only, see {@link ClientFrost}. Between two sends it counts up to this
     * many ticks of 140 - too little frost to be seen.
     */
    static final int FROST_REFRESH = 5;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The tours, whose guests follow; {@link CamTour} takes them off. */
    private final CamTour tours;
    private final TourVisibility visibility;
    /** What the guests of a guide out of camera mode see of him on their own screen. */
    private final GuideMirror mirror;
    /** The internals that let a guest see through the eyes of the guide. */
    private final GuideView view;
    /** Holds guests who see through the guide's eyes turned between the ticks too. */
    private final FineHold fineHold;
    /** What moves the guests, while there is a tour at all. */
    private BukkitRunnable task;

    TourFollowing(CameraPlugin plugin, CamTour tours, TourVisibility visibility, GuideMirror mirror, GuideView view,
                  FineHold fineHold) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.tours = tours;
        this.visibility = visibility;
        this.mirror = mirror;
        this.view = view;
        this.fineHold = fineHold;
    }

    /** Sets the guests moving every tick, once the first tour has a guest; nothing while they move already. */
    void start() {
        if (task != null) {
            return;
        }
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Stops moving the guests: once the last tour is over, after a reload and when the plugin is switched off. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Every tick: each guest after his guide, or the countdown while the guide is out of his reach. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Tour> entry : new ArrayList<>(tours.all().entrySet())) {
            UUID guideId = entry.getKey();
            Tour tour = entry.getValue();
            Player guide = Bukkit.getPlayer(guideId);
            if (guide == null || !tours.canLead(guide)) {
                tours.endTour(guideId, guide == null ? "" : guide.getName());
                continue;
            }
            boolean guideOut = !cameraPlayers.contains(guideId);
            boolean mirrored = guideOut && mirror.isOn();
            Location at = guide.getLocation();
            boolean along = tour.last != null && tour.last.getWorld().equals(at.getWorld());
            Vector step = along ? at.toVector().subtract(tour.last.toVector()) : new Vector();
            tour.last = at.clone();
            Vector pace = tour.trail.next(at.toVector(), step, along);
            List<String> away = new ArrayList<>();
            long soonest = Long.MAX_VALUE;
            Iterator<Map.Entry<UUID, Guest>> guests = new ArrayList<>(tour.guests.entrySet()).iterator();
            while (guests.hasNext()) {
                Map.Entry<UUID, Guest> next = guests.next();
                Player guest = Bukkit.getPlayer(next.getKey());
                CameraData data = cameraPlayers.get(next.getKey());
                if (guest == null || data == null) {
                    tours.drop(guideId, next.getKey());
                    continue;
                }
                Guest state = next.getValue();
                Long left = follow(guide, guest, data, state, at, tour.trail.behind(), pace, now);
                if (left != null) {
                    away.add(guest.getName());
                    soonest = Math.min(soonest, left);
                }
                if (!tour.guests.containsKey(next.getKey())) {
                    continue;
                }
                takeLook(guide, guest);
                // A guest with Geyser's camera is sent the guide's hands empty in any case, see GuideMirror.
                if (mirrored || guideOut && tours.geyserCamera(guest)) {
                    mirror.update(guide, guest);
                } else {
                    // Nothing to see of him: a guide who started camera mode
                    // holds nothing any more.
                    mirror.clear(guest);
                }
                if (++state.sinceFrost >= FROST_REFRESH) {
                    state.sinceFrost = 0;
                    // Shown his guide's frost, he is sent that one, see GuideMirror.
                    if (!mirror.showsFrost(next.getKey())) {
                        ClientFrost.resend(guest);
                    }
                }
            }
            if (!tours.all().containsKey(guideId)) {
                continue;
            }
            warnGuide(guide, tour, away, soonest);
            if (!cameraPlayers.contains(guideId)) {
                // Over a guide out of camera mode only while guests follow him closely.
                plugin.getNameTag().keepOverGuide(guide);
            }
        }
        visibility.sync();
        if (!tours.hearsTickEnd()) {
            tours.endOfTick();
        }
    }

    /**
     * Lets the guide's sky stand in for a guest's own, every tick, as far as
     * {@code follow.guide-time}, {@code -weather} and {@code -night-vision}
     * say: what the guide has in camera mode, the time and weather of the
     * world and no night vision while he leads out of it. Only a change goes
     * to the client.
     */
    void takeLook(Player guide, Player guest) {
        plugin.getSky().showGuide(guest, guide);
        plugin.getNightVision().showGuide(guest, guide);
    }

    /**
     * Moves one guest after his guide, or counts down while the guide is out
     * of his reach - and drops him from the tour once the time is up.
     *
     * @param at     where the guide is now
     * @param behind where the camera of the guest should be, see {@link TourTrail}
     * @param pace   how fast it should go on from there
     * @return the seconds he has left while the guide is out of his reach,
     *         or {@code null} while the guide is within it, or he is gone
     */
    private Long follow(Player guide, Player guest, CameraData data, Guest state, Location at, Vector behind,
                        Vector pace, long now) {
        if (!tours.inReach(guest, data, at)) {
            if (state.outSince == 0L) {
                state.outSince = now;
                state.shownSeconds = -1L;
                state.resting = true;
                guest.setVelocity(new Vector());
                state.sent.clear();
                // Back to his own camera, which stands at its border: he sees
                // no further than he could fly himself.
                unview(guest, state);
                settings.sounds().followReach().play(guest);
                settings.sounds().followReach().play(guide);
            }
            long left = settings.follow().outOfReachSeconds() * 1000L - (now - state.outSince);
            if (left <= 0L) {
                tours.drop(guide.getUniqueId(), guest.getUniqueId());
                plugin.getActionBar().clear(guest);
                messages.sendConfiguredMessage(guest, "tour-reach-lost", "{player}", guide.getName());
                messages.sendConfiguredMessage(guide, "tour-reach-lost-guide", "{player}", guest.getName());
                return null;
            }
            long seconds = (left + 999L) / 1000L;
            if (seconds != state.shownSeconds) {
                state.shownSeconds = seconds;
                if (messages.isMessageEnabled("tour-reach-warning")) {
                    plugin.getActionBar().warn(guest, messages.getMessage("tour-reach-warning")
                            .replace("{player}", guide.getName())
                            .replace("{time}", CamTimeLimit.formatDuration(seconds)));
                }
            }
            return seconds;
        }
        if (state.outSince != 0L) {
            state.outSince = 0L;
            if (state.shownSeconds >= 0L) {
                plugin.getActionBar().clear(guest);
            }
            state.shownSeconds = -1L;
            settings.sounds().signalBack().play(guest);
        }
        Location from = guest.getLocation();
        Vector gap = behind.clone().subtract(from.toVector()).subtract(ahead(guest, state));
        if (!from.getWorld().equals(at.getWorld()) || gap.lengthSquared() > CATCH_UP * CATCH_UP) {
            // Next to the guide himself, whom the reach was measured to.
            Location target = at.clone();
            target.setYaw(from.getYaw());
            target.setPitch(from.getPitch());
            plugin.getMovementGuard().jumpTo(guest, data, target);
            guest.setVelocity(new Vector());
            state.resting = true;
            // Put where the server knows he is: nothing he was sent before
            // takes him any further.
            state.sent.clear();
            // After the jump the client may have to find the guide anew: the
            // guide's view goes out again the next tick he follows him. Until
            // then his client may still see through the guide, so the tour
            // goes on thinking it does - and gives him his own camera back
            // when it ends.
            state.sinceView = VIEW_REFRESH;
            return null;
        }
        Vector speed = pace.clone().add(gap.multiply(PULL));
        if (speed.lengthSquared() > MAX_SPEED * MAX_SPEED) {
            speed.normalize().multiply(MAX_SPEED);
        }
        boolean still = speed.lengthSquared() < 1.0E-4;
        if (!still || !state.resting) {
            guest.setVelocity(still ? new Vector() : speed);
        }
        state.resting = still;
        state.sent.addLast(still ? new Vector() : speed);
        if (state.sent.size() > MAX_LAG) {
            state.sent.removeFirst();
        }
        countWay(data, at, behind, pace);
        // He watches what the guide shows him, even while his own keys do
        // nothing - and through the guide's eyes his client sends no steps:
        // no time without doing anything, for statistics.skip-afk-time.
        data.noteActive(now, settings.statistics().afkMillis());
        updateView(guide, guest, state);
        return null;
    }

    /**
     * Counts the way the camera of a guest is taken along this tick for his
     * statistics, with {@code statistics.count-follow-distance}: the step of
     * the guide it flies after him, from where it should be, see
     * {@link TourTrail}. A jump after the guide is no flight and is not counted,
     * see {@link #CATCH_UP} - nor is a jump of the guide himself, which
     * {@link TourTrail} leaves out of the pace.
     *
     * <p>His own steps do not count meanwhile, see
     * {@link de.elia.cameraplugin.session.SessionTracker}: through the
     * guide's eyes his client sends none, and looking round freely they would
     * be this way twice. So it counts the same either way.</p>
     *
     * @param at     where the guide is now, in the world of the guest
     * @param behind where the camera of the guest should be
     * @param pace   how far it goes on from there this tick
     */
    private void countWay(CameraData data, Location at, Vector behind, Vector pace) {
        if (!settings.statistics().countFollowDistance()) {
            return;
        }
        data.addDistanceCounted(pace.length());
        Location body = data.getBody().getLocation();
        if (body.getWorld() != null && body.getWorld().equals(at.getWorld())) {
            Vector home = body.toVector();
            data.noteStep(home.distance(behind), home.distance(behind.clone().add(pace)));
        }
    }

    /**
     * How far the camera of a guest has flown beyond where the server knows
     * it is: the speeds it was sent in as many of the last ticks as its client
     * is behind - one, and its ping on top.
     *
     * <p>The server learns where the camera is only once its client says so,
     * a ping and a tick after the camera was sent its speed. Pulled by the gap
     * to where it was then, it would be sent after the guide once more for
     * what it flew already: when he stopped, it shot past him - the further,
     * the higher its ping -, and with a high ping it swung to and fro after
     * him, now and then even back a little. Should the camera not have flown
     * all of it - a wall stopped it, say -, its client says so a ping later,
     * and {@link #PULL} makes up for it.</p>
     */
    private static Vector ahead(Player guest, Guest state) {
        int lag = Math.min(MAX_LAG, 1 + (int) Math.round(Math.max(0, guest.getPing()) / 50.0));
        Vector flown = new Vector();
        Iterator<Vector> last = state.sent.descendingIterator();
        for (int i = 0; i < lag && last.hasNext(); i++) {
            flown.add(last.next());
        }
        return flown;
    }

    /**
     * Lets a guest within reach see through the eyes of his guide, while he
     * wants to and his client knows the guide - one a vanish plugin or photo
     * mode hides from him it does not -; sent again now and then, in case the
     * client lost sight of the guide for a moment. Otherwise his own camera.
     */
    private void updateView(Player guide, Player guest, Guest state) {
        boolean wanted = tours.seesThroughGuide(guest) && guest.canSee(guide)
                && guest.getWorld().equals(guide.getWorld());
        if (!wanted) {
            unview(guest, state);
            return;
        }
        if (tours.turnsAlong(guest)) {
            if (state.viewing && !state.turning) {
                // From the guide's camera to turning along: his own camera back first.
                view.show(guest, guest);
            }
            state.viewing = true;
            state.turning = true;
            Location look = guide.getLocation();
            guest.setRotation(look.getYaw(), look.getPitch());
            return;
        }
        if (state.turning) {
            // From turning along to the guide's camera: it goes out below.
            state.turning = false;
            state.viewing = false;
            state.centered = 0;
        }
        if (!state.viewing) {
            // Centred first, while he still looks through his own eyes, see
            // CENTER_TICKS - then through the guide's.
            if (state.centered == 0) {
                Location at = guest.getLocation();
                state.yaw = at.getYaw();
                state.pitch = at.getPitch();
            }
            holdTurned(guest, state);
            if (++state.centered < CENTER_TICKS) {
                return;
            }
        } else if (++state.sinceView < VIEW_REFRESH) {
            holdTurned(guest, state);
            return;
        }
        // A send that fails changes nothing on the client: whatever it saw
        // through before, it still does.
        if (view.show(guest, guide)) {
            state.viewing = true;
            // The hotbar there is the guide's now, see GuideMirror.
            mirror.viewed(guest.getUniqueId());
        }
        state.sinceView = 0;
        holdTurned(guest, state);
    }

    /**
     * Turns a guest who sees through the guide's eyes back to where he is
     * held, every tick. His mouse still turns him on his client, which nobody
     * sees - but his client draws the items in his hands after it, see
     * {@link GuideMirror}, and while he does not look through his own eyes,
     * it lets them sway by every turn without ever bringing them back: they
     * would hang askew in the guide's view. Turned back every tick, they still
     * tremble while he moves the mouse - less so held between the ticks too,
     * see {@link FineHold}. Not on Bedrock, which is shown no hands there.
     */
    private void holdTurned(Player guest, Guest state) {
        if (plugin.getBedrock().adapts(guest)) {
            // On Bedrock, Geyser shows the guide's view through a camera of
            // its own, which neither draws his hands nor turns with him: a
            // turn back would only reach his client as a jump of his player,
            // every tick, and make the view stutter.
            fineHold.release(guest);
            return;
        }
        guest.setRotation(state.yaw, state.pitch);
        if (state.viewing && settings.follow().fineHold()) {
            fineHold.hold(guest, state.yaw, state.pitch);
        }
    }

    /** Gives a guest who sees through the eyes of his guide his own camera back. */
    void unview(Player guest, Guest state) {
        fineHold.release(guest);
        state.centered = 0;
        if (state.viewing && !state.turning) {
            view.show(guest, guest);
        }
        state.viewing = false;
        state.turning = false;
    }

    /**
     * The guide's action bar while guests are out of his reach: who, and
     * when the tour ends for the first of them. Taken away once all of them
     * are back.
     */
    private void warnGuide(Player guide, Tour tour, List<String> away, long soonest) {
        if (away.isEmpty()) {
            if (tour.shownSeconds >= 0L) {
                tour.shownSeconds = -1L;
                plugin.getActionBar().clear(guide);
            }
            return;
        }
        if (soonest == tour.shownSeconds && away.equals(tour.shownNames)) {
            return;
        }
        tour.shownSeconds = soonest;
        tour.shownNames = away;
        if (messages.isMessageEnabled("tour-reach-warning-guide")) {
            plugin.getActionBar().warn(guide, messages.getMessage("tour-reach-warning-guide")
                    .replace("{players}", String.join(", ", away))
                    .replace("{time}", CamTimeLimit.formatDuration(soonest)));
        }
    }
}
