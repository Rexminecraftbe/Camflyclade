package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.ClientFrost;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import de.elia.cameraplugin.visibility.VisibilityMode;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tour, {@code /cam follow}: one camera player shows others round, and
 * their cameras fly along with his - for a build its builder shows to his
 * friends himself.
 *
 * <p>With {@code follow.lead-without-cam} the guide need not be in camera mode
 * himself: the cameras of his guests follow him while he walks round and
 * builds, and his guests see his hearts and the items in his hands on their
 * own screen, see {@link GuideMirror}. The names of the tour stand over him as
 * they would over his camera, see
 * {@link de.elia.cameraplugin.display.CamNameTag}. Nobody leads a tour in
 * spectator mode, which flies through walls; nor once he dies.</p>
 *
 * <p>Who may come along is kept fair: a player in camera mode whose camera
 * is close to the guide's, {@code follow.radius}, or one out of camera mode
 * who stands close to it, {@code follow.start-distance} - his camera mode
 * starts where he stands once he accepts, with every check and cost of a
 * start of {@code /cam} -, and only once he has accepted the invitation. On the tour his camera keeps
 * every limit of its own - {@code max-distance} and the signal from his own
 * body, the wall, the areas, the shut lava, water and powder snow -: the
 * guide steers it, but no further than it could fly by itself. Whoever the
 * guide leaves behind that way, his camera stops at its own border, and he
 * and the guide are told in the action bar that the tour ends for him in
 * {@code follow.out-of-reach-seconds}; should the guide come back in time, the
 * tour goes on.</p>
 *
 * <p>The camera of a guest is not teleported along every tick: it is given
 * the speed that takes it to the guide, and flies there like any camera -
 * through the checks of a step, with the wall in its way. Its own keys move
 * it no longer, its fly speed is 0 while it follows. Only when it falls far
 * behind - the guide jumped to a spot, say - is it put next to him, if he is
 * within its reach.</p>
 *
 * <p>What the guest sees: with {@code follow.guide-view}, on Paper, the world
 * through the eyes of the guide, as smoothly as spectator mode shows another
 * player, see {@link GuideView}; while the guide is out of his reach, his own
 * camera again, which stands at its border. Otherwise - on Spigot, or after
 * {@code /cam follow view} - he looks round as he likes from his own camera,
 * which starts looking the way the guide looks.</p>
 *
 * <p>Those on the same tour - the guide and his guests - are mostly at the
 * same spot, so they do not see each other's cameras: neither the player,
 * nor his name, nor his particles, see {@link #hidesCamera}. A guest who
 * sees through the guide's eyes keeps the guide himself: his client needs
 * him as its camera, and draws no camera it sees through anyway. The players
 * stay in each other's tab list, see {@link OutOfSight}; where that does not
 * work, they are hidden with {@code hidePlayer}, which takes them out of
 * it.</p>
 *
 * <p>Outside the tour, only the guide is seen: his guests fly where he
 * flies, and another client would draw them trailing behind him, a moment
 * late, and jumping after him. So nobody sees the camera of a guest who
 * follows him closely - neither the player nor his particles -, and his name
 * stands over the guide's camera, stacked over the guide's own, see
 * {@link #stackedOver}. A guest left behind at the border of his reach is
 * seen at his own camera again, with his own name.</p>
 *
 * <p>A jump of his own - {@code /cam back}, a spot, a visit, {@code resume} -
 * takes a guest off the tour, as does the end of his camera mode. The end of
 * the guide's camera mode ends the whole tour.</p>
 */
public final class CamTour implements Listener {

    /** Paper's event at the end of every tick, see {@link #watchTickEnd()}. */
    private static final String TICK_END = "com.destroystokyo.paper.event.server.ServerTickEndEvent";

    /** Why the server kicks a player who hits or clicks his own player, see {@link #onSelfInteraction}. */
    private static final String SELF_INTERACTION = "Cannot interact with self!";

    /**
     * How far a guest may fall behind before his camera is put next to the
     * guide, in blocks. Flying on the speed it is given, it trails a block or
     * two at most; beyond this, something else moved one of the two.
     */
    private static final double CATCH_UP = 6.0;

    /**
     * How much of the gap to the guide the speed of a guest closes each tick,
     * on top of the step the guide took. The client is a tick or two behind
     * what the server knows, so a whole gap would make the camera overshoot
     * and swing.
     */
    private static final double PULL = 0.3;

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
    private static final int FROST_REFRESH = 5;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** Every tour that has guests, by the id of its guide. */
    private final Map<UUID, Tour> tours = new LinkedHashMap<>();
    /** The guide each guest follows. */
    private final Map<UUID, UUID> guideOf = new HashMap<>();
    /** The invitations each player has, by the id of the guide, until when they hold. */
    private final Map<UUID, Map<UUID, Long>> invitations = new HashMap<>();
    /**
     * When each player was last invited, by the id of the guide, while that
     * guide may not invite him again, see {@code follow.invite-cooldown-seconds}.
     */
    private final Map<UUID, Map<UUID, Long>> lastInvited = new HashMap<>();
    /** What moves the guests, while there is a tour at all. */
    private BukkitRunnable task;
    /** Sends what the API does not offer, through the internals of the server. */
    private final PacketSender sender = new PacketSender();
    /** The internals that let a guest see through the eyes of the guide. */
    private final GuideView view = new GuideView(sender);
    /** The internals that hide players from a client and leave them in its tab list. */
    private final OutOfSight outOfSight = new OutOfSight(sender);
    /** What the guests of a guide out of camera mode see of him on their own screen. */
    private final GuideMirror mirror;
    /** The internals that swing a guest's arm on his own client as his guide swings his. */
    private final GuideSwing swing = new GuideSwing(sender);
    /** Who looks round freely on tours instead of through the guide's eyes, while he is online. */
    private final Set<UUID> freeLook = new HashSet<>();
    /** The players the tour hid from each viewer with {@code hidePlayer}, see {@link #syncVisibility}. */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    /** The players the client of each viewer was told are gone, see {@link OutOfSight}. */
    private final Map<UUID, Set<UUID>> forgotten = new HashMap<>();
    /** Whether the end of every tick is heard, see {@link #watchTickEnd()}. */
    private boolean tickEnd;

    /** One tour: its guide's guests, and where the guide was last tick. */
    private static final class Tour {
        final Map<UUID, Guest> guests = new LinkedHashMap<>();
        Location last;
        /** The seconds the warning in the guide's action bar showed last, -1 while it shows none. */
        long shownSeconds = -1L;
        /** Whom that warning named. */
        List<String> shownNames = List.of();
    }

    /** One guest on a tour. */
    private static final class Guest {
        /** Since when the guide is out of his reach, 0 while he is within it. */
        long outSince;
        /** The seconds his warning showed last, -1 while it shows none. */
        long shownSeconds = -1L;
        /** Whether he was last sent no speed at all, so standing still is not sent again every tick. */
        boolean resting;
        /**
         * Whether his client may see through the eyes of the guide: from the
         * first time the guide's view went out until his own camera is sent
         * back.
         */
        boolean viewing;
        /** Ticks since the guide's view was last sent. */
        int sinceView;
        /** Where he is held turned while he sees through the guide's eyes, see {@link #holdTurned}. */
        float yaw;
        float pitch;
        /** Ticks he was held turned before seeing through the guide's eyes, see {@link #CENTER_TICKS}. */
        int centered;
        /** Ticks since his frost was last sent to his client. */
        int sinceFrost;
    }

    public CamTour(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.mirror = new GuideMirror(settings);
    }

    /** Whether {@code /cam follow} is there at all. */
    public boolean isEnabled() {
        return settings.isTourEnabled();
    }

    /**
     * At the start and after a reload: looks up what the guide's view needs.
     * Where it is switched on and does not work, the console hears why.
     */
    public void load(ConsoleLog log) {
        view.load(settings.isTourEnabled() && settings.isTourGuideView() ? log : null);
        outOfSight.load(settings.isTourEnabled() ? log : null);
        swing.load(settings.isTourEnabled() && settings.isTourMirrorSwing() ? log : null);
    }

    /**
     * Listens to the end of every tick, where the server has Paper's event:
     * the clients are told once more who is gone, see {@link OutOfSight},
     * after the server has sent everything of that tick. A player it paired
     * with a client anew during the tick is gone again before the client
     * draws him. Without the event, the tour's own tick does it, at the start
     * of the next one.
     */
    public void watchTickEnd() {
        Class<? extends Event> event;
        try {
            event = Class.forName(TICK_END).asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException ex) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvent(event, new Listener() {
        }, EventPriority.MONITOR, (listener, fired) -> {
            if (event.isInstance(fired)) {
                forgetAgain();
            }
        }, plugin);
        tickEnd = true;
    }

    /** Whether a guest can see through the eyes of his guide here: switched on, and on a server it works on. */
    public boolean viewWorks() {
        return settings.isTourGuideView() && view.isAvailable();
    }

    /** Whether he sees through the eyes of his guide on tours, rather than looking round freely. */
    public boolean seesThroughGuide(Player guest) {
        return viewWorks() && !freeLook.contains(guest.getUniqueId());
    }

    /**
     * {@code /cam follow view}: switches between the guide's view and looking
     * round freely, for the tour he follows and the ones after it.
     */
    public void toggleView(Player guest) {
        if (!viewWorks()) {
            messages.sendConfiguredMessage(guest, "tour-view-unavailable");
            return;
        }
        UUID id = guest.getUniqueId();
        if (!freeLook.remove(id)) {
            freeLook.add(id);
            Guest state = stateOf(id);
            if (state != null) {
                unview(guest, state);
            }
        }
        // The guide hidden from a guest who now looks round freely, or
        // shown again to one who now sees through his eyes - before the
        // next tick sends him the guide's view.
        syncVisibility();
        messages.sendConfiguredMessage(guest, seesThroughGuide(guest) ? "tour-view-guide" : "tour-view-free");
    }

    /**
     * Whether a tour keeps the camera of one player out of the sight of
     * another: the two are on the same tour, guide and guest or two guests -
     * or the other one is a guest who follows his guide closely, whom nobody
     * sees, see {@link #followsClosely}. The viewer sees neither his name nor
     * his particles then.
     */
    public boolean hidesCamera(Player viewer, Player other) {
        if (viewer.equals(other)) {
            return false;
        }
        UUID tour = tourOf(viewer.getUniqueId());
        return tour != null && tour.equals(tourOf(other.getUniqueId())) || followsClosely(other);
    }

    /**
     * Whether a tour keeps the player himself from the viewer, as
     * {@link #hidesCamera} keeps his camera: all but the guide from a guest
     * who sees through the guide's eyes.
     */
    private boolean keepsApart(Player viewer, Player other) {
        return hidesCamera(viewer, other) && !(other.getUniqueId().equals(guideOf.get(viewer.getUniqueId()))
                && seesThroughGuide(viewer));
    }

    /**
     * Whether a tour hides the player from the viewer with {@code hidePlayer},
     * where {@link OutOfSight} does not work - visibility leaves the two alone
     * then. Where it works, the server goes on showing him, and visibility
     * goes on as for anybody.
     */
    public boolean hidesPlayer(Player viewer, Player other) {
        return !outOfSight.isAvailable() && keepsApart(viewer, other);
    }

    /** The guide of the tour he is on, as guide or as guest, or {@code null}. */
    private UUID tourOf(UUID player) {
        return tours.containsKey(player) ? player : guideOf.get(player);
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
     */
    private void syncVisibility() {
        Map<UUID, Set<UUID>> wanted = new HashMap<>();
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Map.Entry<UUID, Tour> tour : tours.entrySet()) {
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
                        viewer.hidePlayer(plugin, other);
                        viewer.showPlayer(plugin, other);
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
     * Tells every client once more who is gone: the server sends a player
     * anew whenever it pairs him with a client again, see
     * {@link OutOfSight}. Only those the server shows him count, in his
     * world.
     */
    private void forgetAgain() {
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

    /** The state of a guest on the tour he follows, or {@code null}. */
    private Guest stateOf(UUID guest) {
        UUID guide = guideOf.get(guest);
        Tour tour = guide == null ? null : tours.get(guide);
        return tour == null ? null : tour.guests.get(guest);
    }

    // ------------------------------------------------------------ who is where

    /** Whether he shows others round right now. */
    public boolean isGuiding(Player player) {
        return tours.containsKey(player.getUniqueId());
    }

    /**
     * Whether he could show others round from where he is, follows no tour
     * aside: in camera mode, or - with {@code follow.lead-without-cam} - out of
     * it, as long as he is alive and not in spectator mode.
     */
    public boolean canLead(Player player) {
        if (cameraPlayers.contains(player.getUniqueId())) {
            return true;
        }
        return settings.isTourLeadWithoutCam() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR;
    }

    /** Whether he shows others round right now out of camera mode, while he walks round and builds, say. */
    public boolean guidesWithoutCam(Player player) {
        return isGuiding(player) && !cameraPlayers.contains(player.getUniqueId());
    }

    /** The guide he follows, or {@code null}. */
    public Player guideOf(Player player) {
        UUID guide = guideOf.get(player.getUniqueId());
        return guide == null ? null : Bukkit.getPlayer(guide);
    }

    /** His guests, in the order they joined. */
    public List<Player> guestsOf(Player guide) {
        List<Player> guests = new ArrayList<>();
        Tour tour = tours.get(guide.getUniqueId());
        if (tour != null) {
            for (UUID id : tour.guests.keySet()) {
                Player guest = Bukkit.getPlayer(id);
                if (guest != null) {
                    guests.add(guest);
                }
            }
        }
        return guests;
    }

    /** Whether he follows a tour, as a guest. */
    public boolean isFollowing(Player player) {
        return guideOf.containsKey(player.getUniqueId());
    }

    /** Whether his tour takes no more guests: {@code follow.max-guests}, 0 for no limit. */
    public boolean isFull(Player guide) {
        Tour tour = tours.get(guide.getUniqueId());
        int max = settings.getTourMaxGuests();
        return max > 0 && tour != null && tour.guests.size() >= max;
    }

    /**
     * The guests whose names stand over the guide's camera, stacked over his
     * own, in the order they joined: those who follow him closely, see
     * {@link #followsClosely}.
     */
    public List<Player> stackedOver(Player guide) {
        List<Player> stacked = new ArrayList<>();
        Tour tour = tours.get(guide.getUniqueId());
        if (tour == null) {
            return stacked;
        }
        for (UUID id : tour.guests.keySet()) {
            Player guest = Bukkit.getPlayer(id);
            if (guest != null && followsClosely(guest)) {
                stacked.add(guest);
            }
        }
        return stacked;
    }

    /**
     * Whether he follows his guide closely right now: within his reach, in
     * the guide's world. Nobody sees his camera then, and his name stands
     * over the guide's. A guest left behind at the border of his reach does
     * not.
     */
    public boolean followsClosely(Player guest) {
        Guest state = stateOf(guest.getUniqueId());
        if (state == null || state.outSince != 0L) {
            return false;
        }
        Player guide = Bukkit.getPlayer(guideOf.get(guest.getUniqueId()));
        return guide != null && guest.getWorld().equals(guide.getWorld());
    }

    /** The guides whose invitations he has and that still hold, by name. */
    public List<Player> invitationsOf(Player player) {
        List<Player> guides = new ArrayList<>();
        Map<UUID, Long> own = invitations.get(player.getUniqueId());
        if (own == null) {
            return guides;
        }
        long now = System.currentTimeMillis();
        own.values().removeIf(until -> until < now);
        for (UUID id : own.keySet()) {
            Player guide = Bukkit.getPlayer(id);
            if (guide != null) {
                guides.add(guide);
            }
        }
        guides.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return guides;
    }

    /** Whether the guide has invited him and the invitation still holds. */
    public boolean hasInvited(Player guide, Player other) {
        return invitationLeft(guide, other) > 0L;
    }

    /** For how many seconds still the guide's invitation of him holds, 0 if there is none. */
    public long invitationLeft(Player guide, Player other) {
        Map<UUID, Long> own = invitations.get(other.getUniqueId());
        Long until = own == null ? null : own.get(guide.getUniqueId());
        return until == null ? 0L : seconds(until - System.currentTimeMillis());
    }

    /**
     * For how many seconds still the guide may not invite him again, see
     * {@code follow.invite-cooldown-seconds}; 0 once he may.
     */
    public long inviteWait(Player guide, Player other) {
        Map<UUID, Long> own = lastInvited.get(other.getUniqueId());
        Long at = own == null ? null : own.get(guide.getUniqueId());
        return at == null ? 0L
                : seconds(at + settings.getTourInviteCooldownSeconds() * 1000L - System.currentTimeMillis());
    }

    /** Milliseconds left as whole seconds, rounded up; 0 for none left. */
    private static long seconds(long millis) {
        return millis <= 0L ? 0L : (millis + 999L) / 1000L;
    }

    /**
     * Whom he could invite now, by name: players in camera mode whose camera
     * is within {@code follow.radius} of his, and players out of camera mode
     * within {@code follow.start-distance} of it - on no tour, and able to
     * follow him from where they are. Nobody while he follows a tour himself
     * or cannot lead one, see {@link #canLead}.
     */
    public List<Player> candidates(Player guide) {
        List<Player> found = new ArrayList<>();
        if (!canLead(guide) || guideOf.containsKey(guide.getUniqueId())) {
            return found;
        }
        Tour tour = tours.get(guide.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(guide) && (tour == null || !tour.guests.containsKey(other.getUniqueId()))
                    && seen(guide, other) && mayJoin(guide, other)) {
                found.add(other);
            }
        }
        found.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return found;
    }

    /**
     * Whether a player could come along on the guide's tour from where he is:
     * in camera mode, on no tour, close and within reach; out of camera mode,
     * within {@code follow.start-distance} of the guide's camera, which his
     * camera could reach once it starts where he stands.
     */
    private boolean mayJoin(Player guide, Player other) {
        if (cameraPlayers.contains(other.getUniqueId())) {
            return free(other) && near(guide, other)
                    && inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation());
        }
        return distanceOutside(guide, other) <= settings.getTourStartDistance() && reachableFrom(other, guide);
    }

    /**
     * How far a player out of camera mode stands from the guide's camera, in
     * blocks; endlessly far in another world.
     */
    private static double distanceOutside(Player guide, Player other) {
        Location a = guide.getLocation();
        Location b = other.getLocation();
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) ? a.distance(b) : Double.MAX_VALUE;
    }

    /**
     * Whether a camera that starts where a player out of camera mode stands
     * could reach the guide: within {@code max-distance}, or where its signal
     * reaches.
     */
    private boolean reachableFrom(Player other, Player guide) {
        Location where = guide.getLocation();
        Location body = other.getLocation();
        if (!where.getWorld().equals(body.getWorld())) {
            return false;
        }
        double max = settings.getMaxDistance();
        if (settings.limitsDistance() && where.distanceSquared(body) > max * max) {
            return false;
        }
        return !settings.usesSignal() || plugin.getSignal().reaches(other, other.getEyeLocation(), where);
    }

    /** Whether he is in camera mode and on no tour: neither following one nor showing one. */
    private boolean free(Player player) {
        return cameraPlayers.contains(player.getUniqueId()) && !guideOf.containsKey(player.getUniqueId())
                && !tours.containsKey(player.getUniqueId());
    }

    /** Whether the cameras of the two are within {@code follow.radius} of each other. */
    private boolean near(Player guide, Player other) {
        Location a = guide.getLocation();
        Location b = other.getLocation();
        double radius = settings.getTourRadius();
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= radius * radius;
    }

    /**
     * Whether the guide would see the other one but for camera mode: a
     * player a vanish plugin hides is no one to invite. A guide out of camera
     * mode does not see a camera player under
     * {@code player_visibility_mode: cam}, which is camera mode as well.
     */
    private boolean seen(Player guide, Player other) {
        return guide.canSee(other) || plugin.getPhotoMode().hides(guide, other)
                || plugin.getScoutGuard().hides(guide, other)
                || settings.getPlayerVisibilityMode() == VisibilityMode.CAM
                && cameraPlayers.contains(other.getUniqueId()) && !cameraPlayers.contains(guide.getUniqueId());
    }

    /**
     * Whether a spot lies within the reach of a guest's camera: where it may
     * fly by itself, held from its body - or from the spot it visits, or the
     * portal it came out of -, within {@code max-distance}, or where its
     * signal reaches.
     */
    private boolean inReach(Player guest, CameraData data, Location where) {
        if (data == null) {
            return false;
        }
        CamMovementGuard guard = plugin.getMovementGuard();
        Location anchor = guard.distanceAnchor(data, where);
        if (anchor == null) {
            return false;
        }
        double max = settings.getMaxDistance();
        if (settings.limitsDistance() && where.distanceSquared(anchor) > max * max) {
            return false;
        }
        if (settings.usesSignal()) {
            Location from = anchor.getWorld().equals(data.getBody().getWorld()) ? guard.homeEyes(guest, data)
                    : anchor.clone().add(0.0, guest.getEyeHeight(), 0.0);
            return plugin.getSignal().reaches(guest, from, where);
        }
        return true;
    }

    // ------------------------------------------------------------ commands

    /** {@code /cam follow}, with what follows it. */
    public void command(Player player, String[] args) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(player, "tour-off");
            return;
        }
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && word.equals("invite")) {
            invite(player, Bukkit.getPlayerExact(args[1]), args[1]);
        } else if (args.length == 2 && word.equals("accept")) {
            accept(player, Bukkit.getPlayerExact(args[1]), args[1]);
        } else if (args.length == 2 && word.equals("decline")) {
            decline(player, Bukkit.getPlayerExact(args[1]), args[1]);
        } else if (args.length == 2 && word.equals("kick")) {
            kick(player, Bukkit.getPlayerExact(args[1]), args[1]);
        } else if (args.length == 1 && word.equals("leave")) {
            if (guideOf.containsKey(player.getUniqueId())) {
                leave(player);
            } else {
                messages.sendConfiguredMessage(player, "tour-not-following");
            }
        } else if (args.length == 1 && word.equals("end")) {
            end(player);
        } else if (args.length == 1 && word.equals("view")) {
            toggleView(player);
        } else {
            messages.sendConfiguredMessage(player, "tour-usage");
        }
    }

    /**
     * Invites a player to his tour: one in camera mode, close to him, on no
     * tour, who could follow him from where he is. He hears about it with
     * the buttons to accept and to turn it down.
     */
    public void invite(Player guide, Player other, String name) {
        if (!checkGuide(guide)) {
            return;
        }
        if (other == null || !seen(guide, other)) {
            messages.sendConfiguredMessage(guide, "tour-unknown-player", "{player}", name);
            return;
        }
        if (other.equals(guide)) {
            messages.sendConfiguredMessage(guide, "tour-self");
            return;
        }
        Tour tour = tours.get(guide.getUniqueId());
        if (tour != null && tour.guests.containsKey(other.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-already-guest", "{player}", other.getName());
            return;
        }
        long wait = inviteWait(guide, other);
        if (wait > 0L) {
            messages.sendConfiguredMessage(guide, "tour-invite-wait", "{player}", other.getName(),
                    "{seconds}", String.valueOf(wait));
            return;
        }
        if (!plugin.getTourModeration().checkGuest(guide, other)) {
            return;
        }
        if (isFull(guide)) {
            messages.sendConfiguredMessage(guide, "tour-full-guide", "{max}",
                    String.valueOf(settings.getTourMaxGuests()));
            return;
        }
        boolean inCam = cameraPlayers.contains(other.getUniqueId());
        if (!inCam && settings.getTourStartDistance() <= 0.0) {
            messages.sendConfiguredMessage(guide, "tour-other-not-in-cam", "{player}", other.getName());
            return;
        }
        if (inCam && !free(other)) {
            messages.sendConfiguredMessage(guide, "tour-other-busy", "{player}", other.getName());
            return;
        }
        if (inCam ? !near(guide, other) : distanceOutside(guide, other) > settings.getTourStartDistance()) {
            messages.sendConfiguredMessage(guide, "tour-too-far", "{player}", other.getName());
            return;
        }
        if (inCam ? !inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation())
                : !reachableFrom(other, guide)) {
            messages.sendConfiguredMessage(guide, "tour-out-of-reach", "{player}", other.getName());
            return;
        }
        long now = System.currentTimeMillis();
        int holds = settings.getTourInvitationSeconds();
        invitations.computeIfAbsent(other.getUniqueId(), id -> new HashMap<>())
                .put(guide.getUniqueId(), now + holds * 1000L);
        forgetWaits(now);
        lastInvited.computeIfAbsent(other.getUniqueId(), id -> new HashMap<>()).put(guide.getUniqueId(), now);
        messages.sendConfiguredMessage(guide, "tour-invited", "{player}", other.getName(),
                "{seconds}", String.valueOf(holds));
        tellInvited(other, guide, holds);
        settings.getTourInviteSound().play(other);
    }

    /**
     * Whether he may show others round: no restriction keeps him from it, he
     * can lead one from where he is - in camera mode, or out of it with
     * {@code follow.lead-without-cam} -, and he follows no tour himself. If not,
     * he has been told why.
     */
    private boolean checkGuide(Player guide) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(guide, "tour-off");
            return false;
        }
        if (!plugin.getTourModeration().check(guide, TourBans.What.LEAD)) {
            return false;
        }
        if (!canLead(guide)) {
            messages.sendConfiguredMessage(guide, settings.isTourLeadWithoutCam() ? "tour-guide-spectator"
                    : "tour-not-in-cam");
            return false;
        }
        if (guideOf.containsKey(guide.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-busy-following");
            return false;
        }
        return true;
    }

    /**
     * Forgets whom the guides invited when they may invite them again
     * anyway: kept no longer than needed, even of players long gone.
     */
    private void forgetWaits(long now) {
        long cooldown = settings.getTourInviteCooldownSeconds() * 1000L;
        for (Map<UUID, Long> own : lastInvited.values()) {
            own.values().removeIf(at -> at + cooldown <= now);
        }
        lastInvited.values().removeIf(Map::isEmpty);
    }

    /**
     * Tells a player that he is invited, and for how many seconds, with the
     * buttons to accept and to turn it down.
     */
    private void tellInvited(Player other, Player guide, int seconds) {
        if (!messages.isMessageEnabled("tour-invitation")) {
            return;
        }
        String text = messages.getMessage("tour-invitation").replace("{player}", guide.getName())
                .replace("{seconds}", String.valueOf(seconds));
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        line.addAll(button("tour-button-accept", guide.getName(), "/cam follow accept " + guide.getName()));
        line.addAll(button("tour-button-decline", guide.getName(), "/cam follow decline " + guide.getName()));
        other.spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    /**
     * One button behind the invitation, running its command - also while
     * only the menu goes, as an answer to the invitation needs no menu -,
     * with the text of the same name with {@code -hover} after it on hovering.
     */
    private List<BaseComponent> button(String key, String guide, String command) {
        if (!settings.getMenuAccess().allowsMenu() && !settings.getMenuAccess().allowsCommands()
                || !messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{player}", guide);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{player}", guide))));
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        List<BaseComponent> parts = new ArrayList<>();
        parts.add(new TextComponent(" "));
        for (BaseComponent part : TextComponent.fromLegacyText(text)) {
            part.setHoverEvent(hover);
            part.setClickEvent(click);
            parts.add(part);
        }
        return parts;
    }

    /**
     * Accepts an invitation: everything the invitation asked is asked once
     * more - the two may have moved since. Out of camera mode, it starts
     * first, where he stands, with every check and cost of a start of
     * {@code /cam}. Then his camera goes to the guide, looking his way, and
     * follows him from now on.
     */
    public void accept(Player guest, Player guide, String name) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(guest, "tour-off");
            return;
        }
        if (!plugin.getTourModeration().check(guest, TourBans.What.JOIN)) {
            return;
        }
        if (guide == null || !hasInvited(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-no-invitation", "{player}", guide == null ? name
                    : guide.getName());
            return;
        }
        if (!canLead(guide) || guideOf.containsKey(guide.getUniqueId())) {
            invitations.get(guest.getUniqueId()).remove(guide.getUniqueId());
            messages.sendConfiguredMessage(guest, settings.isTourLeadWithoutCam() ? "tour-guide-cannot-lead"
                    : "tour-guide-gone", "{player}", guide.getName());
            return;
        }
        if (isFull(guide)) {
            // The invitation stays: once a guest leaves, there is room again.
            messages.sendConfiguredMessage(guest, "tour-full", "{player}", guide.getName());
            return;
        }
        if (!cameraPlayers.contains(guest.getUniqueId())) {
            startThenJoin(guest, guide);
            return;
        }
        if (!free(guest)) {
            messages.sendConfiguredMessage(guest, "tour-busy");
            return;
        }
        if (!near(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-too-far-guest", "{player}", guide.getName());
            return;
        }
        join(guest, guide);
    }

    /**
     * Accepts an invitation out of camera mode: if he stands within
     * {@code follow.start-distance} of the guide's camera, which his camera
     * could reach from there, camera mode starts where he stands, and he
     * joins once it has.
     */
    private void startThenJoin(Player guest, Player guide) {
        double reach = settings.getTourStartDistance();
        if (reach <= 0.0) {
            messages.sendConfiguredMessage(guest, "tour-not-in-cam");
            return;
        }
        double distance = distanceOutside(guide, guest);
        if (distance != Double.MAX_VALUE && distance > reach) {
            messages.sendConfiguredMessage(guest, "tour-start-too-far", "{player}", guide.getName(),
                    "{blocks}", String.valueOf(CamSpots.blocksTooFar(distance, reach)));
            return;
        }
        // In another world as well: there is nothing to measure.
        if (distance == Double.MAX_VALUE || !reachableFrom(guest, guide)) {
            messages.sendConfiguredMessage(guest, "tour-guide-out-of-reach", "{player}", guide.getName());
            return;
        }
        plugin.getStarter().startThen(guest, () -> {
            if (cameraPlayers.contains(guest.getUniqueId()) && hasInvited(guide, guest)
                    && canLead(guide) && !guideOf.containsKey(guide.getUniqueId())) {
                join(guest, guide);
            }
        });
    }

    /**
     * Takes a guest in camera mode along, once the invitation and where the
     * two are have been looked at: if the guide is within his reach and has
     * room for him, his camera goes to the guide, looking his way, and
     * follows him from now on.
     */
    private void join(Player guest, Player guide) {
        TourModeration moderation = plugin.getTourModeration();
        if (!moderation.may(guest, TourBans.What.JOIN) || !moderation.may(guide, TourBans.What.LEAD)) {
            // A restriction came while his camera mode started; it told him.
            return;
        }
        if (isFull(guide)) {
            // Somebody else came first while his camera mode started.
            messages.sendConfiguredMessage(guest, "tour-full", "{player}", guide.getName());
            return;
        }
        CameraData data = cameraPlayers.get(guest.getUniqueId());
        if (!inReach(guest, data, guide.getLocation())) {
            messages.sendConfiguredMessage(guest, "tour-guide-out-of-reach", "{player}", guide.getName());
            return;
        }
        invitations.get(guest.getUniqueId()).remove(guide.getUniqueId());
        if (!plugin.getMovementGuard().jumpTo(guest, data, guide.getLocation())) {
            return;
        }
        Tour tour = tours.computeIfAbsent(guide.getUniqueId(), id -> new Tour());
        tour.guests.put(guest.getUniqueId(), new Guest());
        guideOf.put(guest.getUniqueId(), guide.getUniqueId());
        plugin.getFlightSpeed().hold(guest);
        syncVisibility();
        messages.sendConfiguredMessage(guest, "tour-joined", "{player}", guide.getName());
        messages.sendConfiguredMessage(guide, "tour-joined-guide", "{player}", guest.getName());
        startTask();
    }

    /** Turns an invitation down; the guide hears about it. */
    public void decline(Player guest, Player guide, String name) {
        if (guide == null || !hasInvited(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-no-invitation", "{player}", guide == null ? name
                    : guide.getName());
            return;
        }
        invitations.get(guest.getUniqueId()).remove(guide.getUniqueId());
        messages.sendConfiguredMessage(guest, "tour-declined", "{player}", guide.getName());
        messages.sendConfiguredMessage(guide, "tour-declined-guide", "{player}", guest.getName());
    }

    /**
     * Takes him off the tour he follows, if he follows one: from
     * {@code /cam follow leave}, the menu, or a jump of his own. The guide
     * hears about it.
     */
    public void leave(Player guest) {
        UUID guideId = guideOf.get(guest.getUniqueId());
        if (guideId == null) {
            return;
        }
        Player guide = Bukkit.getPlayer(guideId);
        drop(guideId, guest.getUniqueId());
        messages.sendConfiguredMessage(guest, "tour-left", "{player}", guide == null ? "" : guide.getName());
        if (guide != null) {
            messages.sendConfiguredMessage(guide, "tour-left-guide", "{player}", guest.getName());
        }
    }

    /** {@code /cam follow kick <player>}: sends one of his guests away. */
    public void kick(Player guide, Player guest, String name) {
        Tour tour = tours.get(guide.getUniqueId());
        if (guest == null || tour == null || !tour.guests.containsKey(guest.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-not-guest", "{player}", guest == null ? name
                    : guest.getName());
            return;
        }
        drop(guide.getUniqueId(), guest.getUniqueId());
        messages.sendConfiguredMessage(guest, "tour-kicked", "{player}", guide.getName());
        messages.sendConfiguredMessage(guide, "tour-kicked-guide", "{player}", guest.getName());
    }

    /** {@code /cam follow end}: ends his tour for every guest. */
    public void end(Player guide) {
        if (!tours.containsKey(guide.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-not-guiding");
            return;
        }
        endTour(guide.getUniqueId(), guide.getName());
        messages.sendConfiguredMessage(guide, "tour-ended-guide");
    }

    /** Ends a tour for every guest, each told by whom. */
    private void endTour(UUID guideId, String guideName) {
        Tour tour = tours.get(guideId);
        if (tour == null) {
            return;
        }
        for (UUID id : new ArrayList<>(tour.guests.keySet())) {
            drop(guideId, id);
            Player guest = Bukkit.getPlayer(id);
            if (guest != null) {
                messages.sendConfiguredMessage(guest, "tour-ended", "{player}", guideName);
            }
        }
    }

    /**
     * Takes a guest off a tour: his own keys move his camera again, at the
     * speed he flew before, and a tour without guests is over.
     */
    private void drop(UUID guideId, UUID guestId) {
        Tour tour = tours.get(guideId);
        if (tour != null) {
            tour.guests.remove(guestId);
            if (tour.guests.isEmpty()) {
                tours.remove(guideId);
                Player guide = Bukkit.getPlayer(guideId);
                if (guide != null && tour.shownSeconds >= 0L) {
                    plugin.getActionBar().clear(guide);
                }
            }
        }
        guideOf.remove(guestId);
        Player guest = Bukkit.getPlayer(guestId);
        if (guest == null) {
            mirror.forget(guestId);
        } else {
            // His own hearts and hands back on his screen.
            mirror.clear(guest);
            // His own camera back, whatever the tour thought his client saw:
            // a client left seeing through a guide would stay there, through
            // the end of camera mode and the guide leaving, until he joins
            // anew.
            if (view.isAvailable()) {
                view.show(guest, guest);
            }
            plugin.getFlightSpeed().release(guest);
            guest.setVelocity(new Vector());
            // Whatever frost his client counted up by itself since the last send.
            ClientFrost.resend(guest);
        }
        syncVisibility();
        if (tours.isEmpty()) {
            stopTask();
        }
    }

    /**
     * The end of his camera mode, or he left the server: off the tour he
     * follows, and the end of the tour he shows. His invitations go with it,
     * those he has and those he gave.
     */
    public void stopFor(Player player) {
        UUID id = player.getUniqueId();
        UUID guideId = guideOf.get(id);
        if (guideId != null) {
            drop(guideId, id);
            Player guide = Bukkit.getPlayer(guideId);
            if (guide != null) {
                messages.sendConfiguredMessage(guide, "tour-left-guide", "{player}", player.getName());
            }
        }
        if (tours.containsKey(id)) {
            endTour(id, player.getName());
        }
        invitations.remove(id);
        for (Map<UUID, Long> own : invitations.values()) {
            own.remove(id);
        }
    }

    /**
     * After a restriction of the tours, as far as it keeps him now: off the
     * tour he follows, with the invitations he has, when he may not take part
     * any more; the end of the tour he shows, with the invitations he gave,
     * when he may not lead any more.
     */
    public void keepOut(Player player) {
        TourModeration moderation = plugin.getTourModeration();
        UUID id = player.getUniqueId();
        if (!moderation.may(player, TourBans.What.JOIN)) {
            UUID guideId = guideOf.get(id);
            if (guideId != null) {
                drop(guideId, id);
                Player guide = Bukkit.getPlayer(guideId);
                if (guide != null) {
                    messages.sendConfiguredMessage(guide, "tour-left-guide", "{player}", player.getName());
                }
            }
            invitations.remove(id);
        }
        if (!moderation.may(player, TourBans.What.LEAD)) {
            if (tours.containsKey(id)) {
                endTour(id, player.getName());
            }
            for (Map<UUID, Long> own : invitations.values()) {
                own.remove(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteract(PlayerInteractEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteractEntity(PlayerInteractEntityEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInteractAtEntity(PlayerInteractAtEntityEvent event) {
        mirrorAgain(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInventoryClick(InventoryClickEvent event) {
        mirrorAgain(event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuestInventoryDrag(InventoryDragEvent event) {
        mirrorAgain(event.getWhoClicked());
    }

    /**
     * A guide out of camera mode swings his arm - hits, digs, places a
     * block -: his guests who are shown his items see the arm that holds them
     * swing, with {@code follow.mirror-swing}, see {@link GuideSwing}. Their
     * clients answer with a swing of their own, which leads no tour.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuideSwing(PlayerAnimationEvent event) {
        Player guide = event.getPlayer();
        Tour tour = tours.get(guide.getUniqueId());
        if (tour == null || cameraPlayers.contains(guide.getUniqueId()) || !settings.isTourMirrorSwing()) {
            return;
        }
        boolean offHand = event.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING;
        for (UUID id : tour.guests.keySet()) {
            Player guest = Bukkit.getPlayer(id);
            if (guest != null && mirror.isShown(id)) {
                swing.swing(guest, offHand);
            }
        }
    }

    /**
     * Keeps a guest on the server whose client hits or clicks his own player.
     * Seeing through the guide's eyes, he aims from them - and his own
     * player stands right there, around them: his client finds itself under
     * the crosshair, and a click goes to himself. The server answers that
     * with a kick, "Cannot interact with self!", after asking the plugins;
     * turned down here, the click is only lost, as any click of a guest is.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelfInteraction(PlayerKickEvent event) {
        if (guideOf.containsKey(event.getPlayer().getUniqueId()) && SELF_INTERACTION.equals(event.getReason())) {
            event.setCancelled(true);
        }
    }

    /**
     * Sends the guide's items and hearts to this guest again at the next
     * tick of the tour, if he is shown them.
     *
     * <p>What he holds is only a picture of the guide's items, see
     * {@link GuideMirror}, and his client uses it like his own. Whatever the
     * server turns down of that - a click on a block, on a body, into his
     * inventory - it answers with his own empty hands, and the pictures would
     * be gone until their next refresh, flickering away. Paper handles what
     * the clients sent at the start of a tick, before the tour's own tick, so
     * they go out again in that very tick.</p>
     */
    private void mirrorAgain(HumanEntity guest) {
        if (guideOf.containsKey(guest.getUniqueId())) {
            mirror.again(guest.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFor(event.getPlayer());
        mirror.forget(event.getPlayer().getUniqueId());
        freeLook.remove(event.getPlayer().getUniqueId());
        hidden.remove(event.getPlayer().getUniqueId());
        forgotten.remove(event.getPlayer().getUniqueId());
    }

    /** After a reload or when the plugin is switched off: nobody is on a tour any more. */
    public void onDisable() {
        for (UUID guide : new ArrayList<>(tours.keySet())) {
            Player player = Bukkit.getPlayer(guide);
            endTour(guide, player == null ? "" : player.getName());
        }
        invitations.clear();
        lastInvited.clear();
        stopTask();
    }

    // ------------------------------------------------------------ following

    private void startTask() {
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

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Every tick: each guest after his guide, or the countdown while the guide is out of his reach. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Tour> entry : new ArrayList<>(tours.entrySet())) {
            UUID guideId = entry.getKey();
            Tour tour = entry.getValue();
            Player guide = Bukkit.getPlayer(guideId);
            if (guide == null || !canLead(guide)) {
                endTour(guideId, guide == null ? "" : guide.getName());
                continue;
            }
            boolean mirrored = !cameraPlayers.contains(guideId) && mirror.isOn();
            Location at = guide.getLocation();
            Vector step = tour.last != null && tour.last.getWorld().equals(at.getWorld())
                    ? at.toVector().subtract(tour.last.toVector()) : new Vector();
            tour.last = at.clone();
            List<String> away = new ArrayList<>();
            long soonest = Long.MAX_VALUE;
            Iterator<Map.Entry<UUID, Guest>> guests = new ArrayList<>(tour.guests.entrySet()).iterator();
            while (guests.hasNext()) {
                Map.Entry<UUID, Guest> next = guests.next();
                Player guest = Bukkit.getPlayer(next.getKey());
                CameraData data = cameraPlayers.get(next.getKey());
                if (guest == null || data == null) {
                    drop(guideId, next.getKey());
                    continue;
                }
                Guest state = next.getValue();
                Long left = follow(guide, guest, data, state, at, step, now);
                if (left != null) {
                    away.add(guest.getName());
                    soonest = Math.min(soonest, left);
                }
                if (!tour.guests.containsKey(next.getKey())) {
                    continue;
                }
                if (mirrored) {
                    mirror.update(guide, guest);
                } else {
                    // Nothing to see of him: a guide who started camera mode
                    // holds nothing any more.
                    mirror.clear(guest);
                }
                if (++state.sinceFrost >= FROST_REFRESH) {
                    state.sinceFrost = 0;
                    ClientFrost.resend(guest);
                }
            }
            if (!tours.containsKey(guideId)) {
                continue;
            }
            warnGuide(guide, tour, away, soonest);
            if (!cameraPlayers.contains(guideId)) {
                // Over a guide out of camera mode only while guests follow him closely.
                plugin.getNameTag().keepOverGuide(guide);
            }
        }
        syncVisibility();
        if (!tickEnd) {
            forgetAgain();
        }
    }

    /**
     * Moves one guest after his guide, or counts down while the guide is out
     * of his reach - and drops him from the tour once the time is up.
     *
     * @return the seconds he has left while the guide is out of his reach,
     *         or {@code null} while the guide is within it, or he is gone
     */
    private Long follow(Player guide, Player guest, CameraData data, Guest state, Location at, Vector step,
                        long now) {
        if (!inReach(guest, data, at)) {
            if (state.outSince == 0L) {
                state.outSince = now;
                state.shownSeconds = -1L;
                state.resting = true;
                guest.setVelocity(new Vector());
                // Back to his own camera, which stands at its border: he sees
                // no further than he could fly himself.
                unview(guest, state);
                settings.getTourReachSound().play(guest);
                settings.getTourReachSound().play(guide);
            }
            long left = settings.getTourOutOfReachSeconds() * 1000L - (now - state.outSince);
            if (left <= 0L) {
                drop(guide.getUniqueId(), guest.getUniqueId());
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
            settings.getSignalBackSound().play(guest);
        }
        Location from = guest.getLocation();
        Vector gap = at.toVector().subtract(from.toVector());
        if (!from.getWorld().equals(at.getWorld()) || gap.lengthSquared() > CATCH_UP * CATCH_UP) {
            Location target = at.clone();
            target.setYaw(from.getYaw());
            target.setPitch(from.getPitch());
            plugin.getMovementGuard().jumpTo(guest, data, target);
            guest.setVelocity(new Vector());
            state.resting = true;
            // After the jump the client may have to find the guide anew: the
            // guide's view goes out again the next tick he follows him. Until
            // then his client may still see through the guide, so the tour
            // goes on thinking it does - and gives him his own camera back
            // when it ends.
            state.sinceView = VIEW_REFRESH;
            return null;
        }
        Vector speed = step.clone().add(gap.multiply(PULL));
        if (speed.lengthSquared() > MAX_SPEED * MAX_SPEED) {
            speed.normalize().multiply(MAX_SPEED);
        }
        boolean still = speed.lengthSquared() < 1.0E-4;
        if (!still || !state.resting) {
            guest.setVelocity(still ? new Vector() : speed);
        }
        state.resting = still;
        updateView(guide, guest, state);
        return null;
    }

    /**
     * Lets a guest within reach see through the eyes of his guide, while he
     * wants to and his client knows the guide - one a vanish plugin or photo
     * mode hides from him it does not -; sent again now and then, in case the
     * client lost sight of the guide for a moment. Otherwise his own camera.
     */
    private void updateView(Player guide, Player guest, Guest state) {
        boolean wanted = seesThroughGuide(guest) && guest.canSee(guide)
                && guest.getWorld().equals(guide.getWorld());
        if (!wanted) {
            unview(guest, state);
            return;
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
     * would hang askew in the guide's view.
     */
    private static void holdTurned(Player guest, Guest state) {
        guest.setRotation(state.yaw, state.pitch);
    }

    /** Gives a guest who sees through the eyes of his guide his own camera back. */
    private void unview(Player guest, Guest state) {
        state.centered = 0;
        if (state.viewing) {
            view.show(guest, guest);
            state.viewing = false;
        }
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
