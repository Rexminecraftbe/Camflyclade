package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.ClientFrost;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * the speed that takes it along the way of the guide, two ticks after him,
 * see {@link TourTrail}, and flies there like any camera - through the checks
 * of a step, with the wall in its way. Its own keys move it no longer, its
 * fly speed is 0 while it follows. Only when it falls far behind - the guide
 * jumped to a spot, say - is it put next to him, if he is within its
 * reach.</p>
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
 * <p>What a guest sees of the sky is the guide's: with
 * {@code follow.guide-time}, {@code -weather} and {@code -night-vision}, the
 * time of day, the weather and the night vision the guide has in camera mode
 * stand in for his own, see {@link TourFollowing#takeLook}; while the guide leads out of
 * camera mode, the time and weather of the world and no night vision - the
 * guide sees no other. A guest may pick his own all the same: they are kept,
 * but he sees the guide's until the tour is over, and his own after it.</p>
 *
 * <p>With {@code follow.invite-all} a guide invites everybody he could invite
 * at once, {@code /cam follow invite all}, see {@link #inviteAll}.</p>
 *
 * <p>For his statistics, the way the guide takes the camera of a guest along
 * counts as flown, as long as {@code statistics.count-follow-distance} says
 * so - with the guide's view and without it alike, see {@link TourFollowing#countWay}.</p>
 *
 * <p>A jump of his own - {@code /cam back}, a spot, {@code resume} -
 * takes a guest off the tour, as does the end of his camera mode. The end of
 * the guide's camera mode ends the whole tour.</p>
 *
 * <p>This class keeps who is on which tour and decides who may come along;
 * the rest is done by its parts, each in a class of its own:
 * {@link TourInvitations} for the invitations, {@link TourFollowing} for the
 * cameras that follow every tick, {@link TourVisibility} for who sees whom,
 * and {@link TourGuestEvents} for what guests and guides do with the guide's
 * items. They read the tours; only this class changes them.</p>
 */
public final class CamTour implements Listener {

    /** Paper's event at the end of every tick, see {@link #watchTickEnd()}. */
    private static final String TICK_END = "com.destroystokyo.paper.event.server.ServerTickEndEvent";

    /**
     * The word after {@code /cam follow invite} that invites everybody he
     * could invite, see {@link #inviteAll}. A player of that name is invited
     * with it only while {@code follow.invite-all} is off.
     */
    public static final String ALL = "all";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** Every tour that has guests, by the id of its guide. */
    private final Map<UUID, Tour> tours = new LinkedHashMap<>();
    /** The guide each guest follows. */
    private final Map<UUID, UUID> guideOf = new HashMap<>();
    /** Sends what the API does not offer, through the internals of the server. */
    private final PacketSender sender = new PacketSender();
    /** The internals that let a guest see through the eyes of the guide. */
    private final GuideView view = new GuideView(sender);
    /** What the guests of a guide out of camera mode see of him on their own screen. */
    private final GuideMirror mirror;
    /** The internals that swing a guest's arm on his own client as his guide swings his. */
    private final GuideSwing swing = new GuideSwing(sender);
    /** Holds guests who see through the guide's eyes turned between the ticks too. */
    private final FineHold fineHold = new FineHold(sender);
    /** The internals that show a guest the frost of his guide as his own. */
    private final GuideFrost frost = new GuideFrost(sender);
    /** The internals that put the guide's items into the hotbar of a guest on Bedrock, see {@link GuideMirror}. */
    private final GuideSlots slots = new GuideSlots(sender);
    /** The internals that change the guide's hands on their way to a guest, see {@link GuideMirror}. */
    private final GuideHands hands = new GuideHands(sender);
    /** The internals that show a guest hearts without a hit, see {@link GuideMirror}. */
    private final QuietHearts quietHearts = new QuietHearts(sender);
    /** Who looks round freely on tours instead of through the guide's eyes, while he is online. */
    private final Set<UUID> freeLook = new HashSet<>();
    /** Whether the end of every tick is heard, see {@link #watchTickEnd()}. */
    private boolean tickEnd;
    /** The tours as the parts see them: they read, only this class changes them. */
    private final Map<UUID, Tour> readOnlyTours = Collections.unmodifiableMap(tours);
    /** Who on a tour sees whom. */
    private final TourVisibility visibility;
    /** The invitations of the tours. */
    private final TourInvitations invites;
    /** Takes the cameras of the guests along after their guides, every tick. */
    private final TourFollowing following;
    /** What guests and guides do with the guide's items, see {@link #guestEvents()}. */
    private final TourGuestEvents guestEvents;

    public CamTour(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.mirror = new GuideMirror(settings, frost, hands, quietHearts,
                new NamespacedKey(plugin, GuideMirror.MARK), this::geyserCamera, slots,
                guest -> plugin.getBedrock().adapts(guest));
        this.visibility = new TourVisibility(plugin, this, sender);
        this.invites = new TourInvitations(plugin, this);
        this.following = new TourFollowing(plugin, this, visibility, mirror, view, fineHold);
        this.guestEvents = new TourGuestEvents(plugin, this, mirror, swing);
    }

    /**
     * What guests and guides do with the guide's items, as a listener of its
     * own: registered next to this one.
     */
    public TourGuestEvents guestEvents() {
        return guestEvents;
    }

    /** Whether {@code /cam follow} is there at all. */
    public boolean isEnabled() {
        return settings.follow().enabled();
    }

    /**
     * At the start and after a reload: looks up what the guide's view needs.
     * Where it is switched on and does not work, the console hears why.
     */
    public void load(ConsoleLog log) {
        view.load(settings.follow().enabled() && settings.follow().guideView() ? log : null);
        visibility.load(settings.follow().enabled() ? log : null);
        swing.load(settings.follow().enabled() && settings.follow().mirrorSwing() ? log : null);
        fineHold.load(settings.follow().enabled() && settings.follow().guideView() && settings.follow().fineHold()
                ? log : null);
        frost.load(settings.follow().enabled() && settings.follow().mirrorFrost() ? log : null);
        quietHearts.load(settings.follow().enabled() && settings.follow().mirrorHealth() ? log : null);
        slots.load(settings.follow().enabled()
                && (settings.follow().mirrorMainHand() || settings.follow().mirrorOffHand()) ? log : null);
        boolean hides = !settings.follow().mirrorCount() || !settings.follow().mirrorEnchantments()
                || !settings.follow().mirrorDurability() || !settings.follow().mirrorName();
        hands.load(settings.follow().enabled() && hides ? log : null);
    }

    /**
     * Listens to the end of every tick, where the server has Paper's event:
     * the clients are told once more who is gone, see {@link OutOfSight},
     * after the server has sent everything of that tick. A player it paired
     * with a client anew during the tick is gone again before the client
     * draws him. The guide's effects and frost go to his guests there too,
     * see {@link GuideMirror#tickEnd}. Without the event, the tour's own tick
     * does both, at its end.
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
                endOfTick();
            }
        }, plugin);
        tickEnd = true;
    }

    /**
     * What comes at the end of every tick: the clients are told once more who
     * is gone, see {@link TourVisibility#forgetAgain}, and the guide's effects
     * and frost go to his guests, see {@link GuideMirror#tickEnd}. On Paper
     * at the very end of the tick, see {@link #watchTickEnd()}; otherwise at
     * the end of the tour's own tick, see {@link TourFollowing}.
     */
    void endOfTick() {
        visibility.forgetAgain();
        mirror.tickEnd();
    }

    /** Whether {@link #endOfTick()} comes by itself at the end of every tick, see {@link #watchTickEnd()}. */
    boolean hearsTickEnd() {
        return tickEnd;
    }

    /** Whether a guest can see through the eyes of his guide here: switched on, and on a server it works on. */
    public boolean viewWorks() {
        return settings.follow().guideView() && view.isAvailable();
    }

    /** Whether he sees through the eyes of his guide on tours, rather than looking round freely. */
    public boolean seesThroughGuide(Player guest) {
        return viewWorks() && !freeLook.contains(guest.getUniqueId());
    }

    /**
     * Whether a guest on Bedrock sees through the eyes of his guide by
     * looking himself where the guide looks, every tick, from his own camera
     * right behind him, {@code bedrock.follow-view: turn}: the guide's items
     * are then in his own hands, see {@link GuideMirror}, and turn with his
     * view. Geyser's camera, which shows the guide's view otherwise, draws no
     * hands.
     */
    boolean turnsAlong(Player guest) {
        return seesThroughGuide(guest) && plugin.getBedrock().adapts(guest) && settings.bedrock().followTurn();
    }

    /**
     * Whether a guest on Bedrock sees through the eyes of his guide with
     * Geyser's camera, {@code bedrock.follow-view: camera}: it follows the
     * guide's view smoothly, but draws no hands - he is shown the guide's
     * items in his hotbar alone, see {@link GuideMirror}, and nothing swings.
     */
    boolean geyserCamera(Player guest) {
        return seesThroughGuide(guest) && plugin.getBedrock().adapts(guest) && !settings.bedrock().followTurn();
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
                following.unview(guest, state);
            }
        }
        // The guide hidden from a guest who now looks round freely, or
        // shown again to one who now sees through his eyes - before the
        // next tick sends him the guide's view.
        visibility.sync();
        messages.sendConfiguredMessage(guest, seesThroughGuide(guest) ? "tour-view-guide" : "tour-view-free");
    }

    /**
     * Whether a tour keeps the camera of one player out of the sight of
     * another, see {@link TourVisibility#hidesCamera}.
     */
    public boolean hidesCamera(Player viewer, Player other) {
        return visibility.hidesCamera(viewer, other);
    }

    /**
     * Whether a tour hides the player from the viewer with
     * {@code hidePlayer}, see {@link TourVisibility#hidesPlayer}.
     */
    public boolean hidesPlayer(Player viewer, Player other) {
        return visibility.hidesPlayer(viewer, other);
    }

    /** Every tour that has guests, by the id of its guide, for the parts to read. */
    Map<UUID, Tour> all() {
        return readOnlyTours;
    }

    /** The id of the guide he follows, or {@code null}. */
    UUID guideIdOf(UUID player) {
        return guideOf.get(player);
    }

    /**
     * Whether he follows a tour, as a guest, by his id - for a
     * {@link org.bukkit.entity.HumanEntity} as well, see {@link TourGuestEvents}.
     */
    boolean follows(UUID player) {
        return guideOf.containsKey(player);
    }

    /** The guide of the tour he is on, as guide or as guest, or {@code null}. */
    UUID tourOf(UUID player) {
        return tours.containsKey(player) ? player : guideOf.get(player);
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
        return settings.follow().leadWithoutCam() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR;
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

    /**
     * The guide whose eyes this guest sees through right now, or {@code null}
     * while he sees through his own camera. Meanwhile his client tells the
     * server nothing of where his own camera is - the tour drags it after the
     * guide, and the server learns where only now and then, see
     * {@link TourFollowing#CATCH_UP} -, nor which way he looks, as he is held turned. What
     * is measured from where he sees, the way to his body or his signal, is
     * measured from the guide.
     */
    public Player seenThrough(Player guest) {
        UUID guideId = guideOf.get(guest.getUniqueId());
        Tour tour = guideId == null ? null : tours.get(guideId);
        Guest state = tour == null ? null : tour.guests.get(guest.getUniqueId());
        return state != null && state.viewing ? Bukkit.getPlayer(guideId) : null;
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
        int max = settings.follow().maxGuests();
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
        return invites.invitationsOf(player);
    }

    /** Whether the guide has invited him and the invitation still holds. */
    public boolean hasInvited(Player guide, Player other) {
        return invites.hasInvited(guide, other);
    }

    /** For how many seconds still the guide's invitation of him holds, 0 if there is none. */
    public long invitationLeft(Player guide, Player other) {
        return invites.invitationLeft(guide, other);
    }

    /** For how many seconds still the guide may not invite him again; 0 once he may. */
    public long inviteWait(Player guide, Player other) {
        return invites.inviteWait(guide, other);
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
    boolean mayJoin(Player guide, Player other) {
        if (cameraPlayers.contains(other.getUniqueId())) {
            return free(other) && near(guide, other)
                    && inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation());
        }
        return !busy(other) && distanceOutside(guide, other) <= settings.follow().startDistance()
                && reachableFrom(other, guide);
    }

    /**
     * How far a player out of camera mode stands from the guide's camera, in
     * blocks; endlessly far in another world.
     */
    static double distanceOutside(Player guide, Player other) {
        Location a = guide.getLocation();
        Location b = other.getLocation();
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) ? a.distance(b) : Double.MAX_VALUE;
    }

    /**
     * Whether a camera that starts where a player out of camera mode stands
     * could reach the guide: within {@code max-distance}, or where its signal
     * reaches.
     */
    boolean reachableFrom(Player other, Player guide) {
        Location where = guide.getLocation();
        Location body = other.getLocation();
        if (!where.getWorld().equals(body.getWorld())) {
            return false;
        }
        double max = settings.cameraMode().maxDistance();
        if (settings.cameraMode().limitsDistance() && where.distanceSquared(body) > max * max) {
            return false;
        }
        return !settings.cameraMode().usesSignal() || plugin.getSignal().reaches(other, other.getEyeLocation(), where);
    }

    /** Whether he is in camera mode and on no tour: neither following one nor showing one. */
    boolean free(Player player) {
        return cameraPlayers.contains(player.getUniqueId()) && !busy(player);
    }

    /**
     * Whether he is on a tour, in camera mode or not: following one, or
     * showing one - out of camera mode as well, with
     * {@code follow.lead-without-cam}.
     */
    boolean busy(Player player) {
        return guideOf.containsKey(player.getUniqueId()) || tours.containsKey(player.getUniqueId());
    }

    /** Whether the cameras of the two are within {@code follow.radius} of each other. */
    boolean near(Player guide, Player other) {
        Location a = guide.getLocation();
        Location b = other.getLocation();
        double radius = settings.follow().radius();
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= radius * radius;
    }

    /**
     * Whether the guide would see the other one but for camera mode: a
     * player a vanish plugin hides is no one to invite. A guide out of camera
     * mode does not see a camera player under
     * {@code player_visibility_mode: cam}, which is camera mode as well.
     */
    boolean seen(Player guide, Player other) {
        return guide.canSee(other) || plugin.getPhotoMode().hides(guide, other)
                || plugin.getScoutGuard().hides(guide, other)
                || settings.cameraMode().visibilityMode() == VisibilityMode.CAM
                && cameraPlayers.contains(other.getUniqueId()) && !cameraPlayers.contains(guide.getUniqueId());
    }

    /**
     * Whether a spot lies within the reach of a guest's camera: where it may
     * fly by itself, held from its body - or from the portal it came out of -,
     * within {@code max-distance}, or where its
     * signal reaches.
     */
    boolean inReach(Player guest, CameraData data, Location where) {
        if (data == null) {
            return false;
        }
        CamMovementGuard guard = plugin.getMovementGuard();
        Location anchor = guard.distanceAnchor(data, where);
        if (anchor == null) {
            return false;
        }
        double max = settings.cameraMode().maxDistance();
        if (settings.cameraMode().limitsDistance() && where.distanceSquared(anchor) > max * max) {
            return false;
        }
        if (settings.cameraMode().usesSignal()) {
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
        if (args.length == 2 && word.equals("invite") && args[1].equalsIgnoreCase(ALL)
                && settings.follow().inviteAll()) {
            inviteAll(player);
        } else if (args.length == 2 && word.equals("invite")) {
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

    /** {@code /cam follow invite <player>}, see {@link TourInvitations#invite}. */
    public void invite(Player guide, Player other, String name) {
        invites.invite(guide, other, name);
    }

    /** {@code /cam follow invite all} and the button of the menu, see {@link TourInvitations#inviteAll}. */
    public void inviteAll(Player guide) {
        invites.inviteAll(guide);
    }

    /** Whom {@link #inviteAll} would invite now, see {@link TourInvitations#invitable}. */
    public List<Player> invitable(Player guide) {
        return invites.invitable(guide);
    }

    /** {@code /cam follow accept <player>}, see {@link TourInvitations#accept}. */
    public void accept(Player guest, Player guide, String name) {
        invites.accept(guest, guide, name);
    }

    /**
     * Takes a guest in camera mode along, once the invitation and where the
     * two are have been looked at: if the guide is within his reach and has
     * room for him, his camera goes to the guide, looking his way, and
     * follows him from now on.
     */
    void join(Player guest, Player guide) {
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
        invites.accepted(guest.getUniqueId(), guide.getUniqueId());
        if (!plugin.getMovementGuard().jumpTo(guest, data, guide.getLocation())) {
            return;
        }
        // Following somebody, he no longer looks at the spot he jumped to.
        plugin.getSpots().dropLook(guest);
        Tour tour = tours.computeIfAbsent(guide.getUniqueId(), id -> new Tour());
        tour.guests.put(guest.getUniqueId(), new Guest());
        guideOf.put(guest.getUniqueId(), guide.getUniqueId());
        plugin.getFlightSpeed().hold(guest);
        // From the first moment on, not only from the next tick of the tour.
        following.takeLook(guide, guest);
        visibility.sync();
        // How to stop: with the command, or while only the menu goes, there.
        messages.sendConfiguredMessage(guest, settings.menu().access().allowsCommands() ? "tour-joined"
                : "tour-joined-menu", "{player}", guide.getName());
        messages.sendConfiguredMessage(guide, "tour-joined-guide", "{player}", guest.getName());
        following.start();
    }

    /** {@code /cam follow decline <player>}: turns an invitation down; the guide hears about it. */
    public void decline(Player guest, Player guide, String name) {
        invites.decline(guest, guide, name);
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
    void endTour(UUID guideId, String guideName) {
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
    void drop(UUID guideId, UUID guestId) {
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
            // His own time, weather and night vision again.
            plugin.getSky().leaveGuide(guest);
            plugin.getNightVision().leaveGuide(guest);
        }
        visibility.sync();
        if (tours.isEmpty()) {
            following.stop();
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
        invites.forgetReceived(id);
        invites.forgetGiven(id);
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
            invites.forgetReceived(id);
        }
        if (!moderation.may(player, TourBans.What.LEAD)) {
            if (tours.containsKey(id)) {
                endTour(id, player.getName());
            }
            invites.forgetGiven(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFor(event.getPlayer());
        mirror.forget(event.getPlayer().getUniqueId());
        freeLook.remove(event.getPlayer().getUniqueId());
        visibility.forget(event.getPlayer().getUniqueId());
    }

    /** After a reload or when the plugin is switched off: nobody is on a tour any more. */
    public void onDisable() {
        for (UUID guide : new ArrayList<>(tours.keySet())) {
            Player player = Bukkit.getPlayer(guide);
            endTour(guide, player == null ? "" : player.getName());
        }
        invites.clear();
        following.stop();
        fineHold.stop();
    }
}
