package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The tour, {@code /cam tour}: one camera player shows others round, and
 * their cameras fly along with his - for a build its builder shows to his
 * friends himself.
 *
 * <p>Who may come along is kept fair: only a player who is in camera mode
 * already and whose camera is close to the guide's, {@code tour.radius},
 * and only once he has accepted the invitation. On the tour his camera keeps
 * every limit of its own - {@code max-distance} and the signal from his own
 * body, the wall, the areas, the shut lava, water and powder snow -: the
 * guide steers it, but no further than it could fly by itself. Whoever the
 * guide leaves behind that way, his camera stops at its own border, and he
 * and the guide are told in the action bar that the tour ends for him in
 * {@code tour.out-of-reach-seconds}; should the guide come back in time, the
 * tour goes on.</p>
 *
 * <p>The camera of a guest is not teleported along every tick: it is given
 * the speed that takes it to the guide, and flies there like any camera -
 * through the checks of a step, with the wall in its way. Its own keys move
 * it no longer, its fly speed is 0 while it follows. It looks round as it
 * likes; at the start it looks the way the guide looks. Only when it falls
 * far behind - the guide jumped to a spot, say - is it put next to him, if
 * he is within its reach.</p>
 *
 * <p>A jump of his own - {@code /cam back}, a spot, a visit, {@code resume} -
 * takes a guest off the tour, as does the end of his camera mode. The end of
 * the guide's camera mode ends the whole tour.</p>
 */
public final class CamTour implements Listener {

    /** How long an invitation holds, in milliseconds. */
    private static final long INVITATION_MILLIS = 60_000L;

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
    /** What moves the guests, while there is a tour at all. */
    private BukkitRunnable task;

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
    }

    public CamTour(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Whether {@code /cam tour} is there at all. */
    public boolean isEnabled() {
        return settings.isTourEnabled();
    }

    // ------------------------------------------------------------ who is where

    /** Whether he shows others round right now. */
    public boolean isGuiding(Player player) {
        return tours.containsKey(player.getUniqueId());
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
        Map<UUID, Long> own = invitations.get(other.getUniqueId());
        Long until = own == null ? null : own.get(guide.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    /**
     * Whom he could invite now: players in camera mode whose camera is within
     * {@code tour.radius} of his, who are on no tour and who could follow him
     * from where he is, by name. Nobody while he follows a tour himself or is
     * not in camera mode.
     */
    public List<Player> candidates(Player guide) {
        List<Player> found = new ArrayList<>();
        if (!cameraPlayers.contains(guide.getUniqueId()) || guideOf.containsKey(guide.getUniqueId())) {
            return found;
        }
        Tour tour = tours.get(guide.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(guide) && (tour == null || !tour.guests.containsKey(other.getUniqueId()))
                    && free(other) && near(guide, other) && seen(guide, other)
                    && inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation())) {
                found.add(other);
            }
        }
        found.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return found;
    }

    /** Whether he is in camera mode and on no tour: neither following one nor showing one. */
    private boolean free(Player player) {
        return cameraPlayers.contains(player.getUniqueId()) && !guideOf.containsKey(player.getUniqueId())
                && !tours.containsKey(player.getUniqueId());
    }

    /** Whether the cameras of the two are within {@code tour.radius} of each other. */
    private boolean near(Player guide, Player other) {
        Location a = guide.getLocation();
        Location b = other.getLocation();
        double radius = settings.getTourRadius();
        return a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) <= radius * radius;
    }

    /**
     * Whether the guide would see the other one but for camera mode: a
     * player a vanish plugin hides is no one to invite.
     */
    private boolean seen(Player guide, Player other) {
        return guide.canSee(other) || plugin.getPhotoMode().hides(guide, other)
                || plugin.getScoutGuard().hides(guide, other);
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

    /** {@code /cam tour}, with what follows it. */
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
        if (!cameraPlayers.contains(other.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-other-not-in-cam", "{player}", other.getName());
            return;
        }
        if (!free(other)) {
            messages.sendConfiguredMessage(guide, "tour-other-busy", "{player}", other.getName());
            return;
        }
        if (!near(guide, other)) {
            messages.sendConfiguredMessage(guide, "tour-too-far", "{player}", other.getName(),
                    "{radius}", radiusText());
            return;
        }
        if (!inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation())) {
            messages.sendConfiguredMessage(guide, "tour-out-of-reach", "{player}", other.getName());
            return;
        }
        invitations.computeIfAbsent(other.getUniqueId(), id -> new HashMap<>())
                .put(guide.getUniqueId(), System.currentTimeMillis() + INVITATION_MILLIS);
        messages.sendConfiguredMessage(guide, "tour-invited", "{player}", other.getName());
        tellInvited(other, guide);
        settings.getTourInviteSound().play(other);
    }

    /**
     * Whether he may show others round: in camera mode, and following no
     * tour himself. If not, he has been told why.
     */
    private boolean checkGuide(Player guide) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(guide, "tour-off");
            return false;
        }
        if (!cameraPlayers.contains(guide.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-not-in-cam");
            return false;
        }
        if (guideOf.containsKey(guide.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-busy-following");
            return false;
        }
        return true;
    }

    /** Tells a player that he is invited, with the buttons to accept and to turn it down. */
    private void tellInvited(Player other, Player guide) {
        if (!messages.isMessageEnabled("tour-invitation")) {
            return;
        }
        String text = messages.getMessage("tour-invitation").replace("{player}", guide.getName());
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        line.addAll(button("tour-button-accept", guide.getName(), "/cam tour accept " + guide.getName()));
        line.addAll(button("tour-button-decline", guide.getName(), "/cam tour decline " + guide.getName()));
        other.spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    /**
     * One button behind the invitation, running its command - or opening the
     * menu, while only the menu goes -, with the text of the same name with
     * {@code -hover} after it on hovering.
     */
    private List<BaseComponent> button(String key, String guide, String command) {
        String run = plugin.commandOrMenu(command);
        if (run == null || !messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{player}", guide);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{player}", guide))));
        ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, run);
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
     * more - the two may have moved since -, and then his camera goes to the
     * guide, looking his way, and follows him from now on.
     */
    public void accept(Player guest, Player guide, String name) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(guest, "tour-off");
            return;
        }
        if (guide == null || !hasInvited(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-no-invitation", "{player}", guide == null ? name
                    : guide.getName());
            return;
        }
        if (!cameraPlayers.contains(guest.getUniqueId())) {
            messages.sendConfiguredMessage(guest, "tour-not-in-cam");
            return;
        }
        if (!free(guest)) {
            messages.sendConfiguredMessage(guest, "tour-busy");
            return;
        }
        if (!cameraPlayers.contains(guide.getUniqueId()) || guideOf.containsKey(guide.getUniqueId())) {
            invitations.get(guest.getUniqueId()).remove(guide.getUniqueId());
            messages.sendConfiguredMessage(guest, "tour-guide-gone", "{player}", guide.getName());
            return;
        }
        if (!near(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-too-far", "{player}", guide.getName(),
                    "{radius}", radiusText());
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
     * {@code /cam tour leave}, the menu, or a jump of his own. The guide
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

    /** {@code /cam tour kick <player>}: sends one of his guests away. */
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

    /** {@code /cam tour end}: ends his tour for every guest. */
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
        if (guest != null) {
            plugin.getFlightSpeed().release(guest);
            guest.setVelocity(new Vector());
        }
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFor(event.getPlayer());
    }

    /** After a reload or when the plugin is switched off: nobody is on a tour any more. */
    public void onDisable() {
        for (UUID guide : new ArrayList<>(tours.keySet())) {
            Player player = Bukkit.getPlayer(guide);
            endTour(guide, player == null ? "" : player.getName());
        }
        invitations.clear();
        stopTask();
    }

    private String radiusText() {
        double radius = settings.getTourRadius();
        return radius == Math.rint(radius) ? String.valueOf((long) radius) : String.valueOf(radius);
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
            if (guide == null || !cameraPlayers.contains(guideId)) {
                endTour(guideId, guide == null ? "" : guide.getName());
                continue;
            }
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
                Long left = follow(guide, guest, data, next.getValue(), at, step, now);
                if (left != null) {
                    away.add(guest.getName());
                    soonest = Math.min(soonest, left);
                }
            }
            if (!tours.containsKey(guideId)) {
                continue;
            }
            warnGuide(guide, tour, away, soonest);
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
        return null;
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
