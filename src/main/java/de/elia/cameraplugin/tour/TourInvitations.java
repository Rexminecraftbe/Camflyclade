package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The invitations of the tours of {@link CamTour}: {@code /cam follow invite},
 * {@code invite all}, {@code accept} and {@code decline}. An invitation holds
 * for {@code follow.invitation-seconds}, and a guide may invite the same
 * player again only after {@code follow.invite-cooldown-seconds} - unless he
 * accepted, see {@link #accepted}. Whoever accepts is taken along by
 * {@link CamTour#join}.
 */
final class TourInvitations {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The tours, asked who is where and who may come along; {@link CamTour#join} takes him along. */
    private final CamTour tours;
    /** The invitations each player has, by the id of the guide, until when they hold. */
    private final Map<UUID, Map<UUID, Long>> invitations = new HashMap<>();
    /**
     * When each player was last invited, by the id of the guide, while that
     * guide may not invite him again, see {@code follow.invite-cooldown-seconds}.
     * Once he accepts, the guide may invite him again straight away - after
     * he left the tour, say -, see {@link #accepted}.
     */
    private final Map<UUID, Map<UUID, Long>> lastInvited = new HashMap<>();

    TourInvitations(CameraPlugin plugin, CamTour tours) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.tours = tours;
    }

    /** The guides whose invitations he has and that still hold, by name. */
    List<Player> invitationsOf(Player player) {
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
    boolean hasInvited(Player guide, Player other) {
        return invitationLeft(guide, other) > 0L;
    }

    /** For how many seconds still the guide's invitation of him holds, 0 if there is none. */
    long invitationLeft(Player guide, Player other) {
        Map<UUID, Long> own = invitations.get(other.getUniqueId());
        Long until = own == null ? null : own.get(guide.getUniqueId());
        return until == null ? 0L : seconds(until - System.currentTimeMillis());
    }

    /**
     * For how many seconds still the guide may not invite him again, see
     * {@code follow.invite-cooldown-seconds}; 0 once he may.
     */
    long inviteWait(Player guide, Player other) {
        Map<UUID, Long> own = lastInvited.get(other.getUniqueId());
        Long at = own == null ? null : own.get(guide.getUniqueId());
        return at == null ? 0L
                : seconds(at + settings.follow().inviteCooldownSeconds() * 1000L - System.currentTimeMillis());
    }

    /** Milliseconds left as whole seconds, rounded up; 0 for none left. */
    private static long seconds(long millis) {
        return millis <= 0L ? 0L : (millis + 999L) / 1000L;
    }

    /**
     * Invites a player to his tour: one in camera mode, close to him, on no
     * tour, who could follow him from where he is. He hears about it with
     * the buttons to accept and to turn it down.
     */
    void invite(Player guide, Player other, String name) {
        if (!checkGuide(guide)) {
            return;
        }
        if (other == null || !tours.seen(guide, other)) {
            messages.sendConfiguredMessage(guide, "tour-unknown-player", "{player}", name);
            return;
        }
        if (other.equals(guide)) {
            messages.sendConfiguredMessage(guide, "tour-self");
            return;
        }
        Tour tour = tours.all().get(guide.getUniqueId());
        if (tour != null && tour.guests.containsKey(other.getUniqueId())) {
            messages.sendConfiguredMessage(guide, "tour-already-guest", "{player}", other.getName());
            return;
        }
        long wait = inviteWait(guide, other);
        if (wait > 0L) {
            messages.sendConfiguredMessage(guide, "tour-invite-wait", "{player}", other.getName(),
                    "{seconds}", String.valueOf(wait), "{time}", CamTimeLimit.formatSeconds(wait));
            return;
        }
        if (!plugin.getTourModeration().checkGuest(guide, other)) {
            return;
        }
        if (tours.isFull(guide)) {
            messages.sendConfiguredMessage(guide, "tour-full-guide", "{max}",
                    String.valueOf(settings.follow().maxGuests()));
            return;
        }
        boolean inCam = cameraPlayers.contains(other.getUniqueId());
        if (!inCam && settings.follow().startDistance() <= 0.0) {
            messages.sendConfiguredMessage(guide, "tour-other-not-in-cam", "{player}", other.getName());
            return;
        }
        // Out of camera mode as well: whoever shows a tour round without it
        // follows none at the same time.
        if (tours.busy(other)) {
            messages.sendConfiguredMessage(guide, "tour-other-busy", "{player}", other.getName());
            return;
        }
        if (inCam ? !tours.near(guide, other)
                : CamTour.distanceOutside(guide, other) > settings.follow().startDistance()) {
            messages.sendConfiguredMessage(guide, "tour-too-far", "{player}", other.getName());
            return;
        }
        if (inCam ? !tours.inReach(other, cameraPlayers.get(other.getUniqueId()), guide.getLocation())
                : !tours.reachableFrom(other, guide)) {
            messages.sendConfiguredMessage(guide, "tour-out-of-reach", "{player}", other.getName());
            return;
        }
        sendInvitation(guide, other);
        messages.sendConfiguredMessage(guide, "tour-invited", "{player}", other.getName(),
                "{seconds}", String.valueOf(settings.follow().invitationSeconds()),
                "{time}", CamTimeLimit.formatSeconds(settings.follow().invitationSeconds()));
    }

    /**
     * {@code /cam follow invite all}, and the button of the menu: invites
     * everybody he could invite now, the players of {@link CamTour#candidates} -
     * all but those he has invited and whose invitation still holds, those he
     * may not invite again yet, and those a restriction keeps from taking
     * part, who are left out without a word. He hears whom he invited, or
     * that there was nobody, and how close they would have to be.
     */
    void inviteAll(Player guide) {
        if (!settings.follow().inviteAll()) {
            messages.sendConfiguredMessage(guide, "tour-invite-all-off");
            return;
        }
        if (!checkGuide(guide)) {
            return;
        }
        if (tours.isFull(guide)) {
            messages.sendConfiguredMessage(guide, "tour-full-guide", "{max}",
                    String.valueOf(settings.follow().maxGuests()));
            return;
        }
        List<String> invited = new ArrayList<>();
        for (Player other : invitable(guide)) {
            sendInvitation(guide, other);
            invited.add(other.getName());
        }
        if (invited.isEmpty()) {
            // How close they would have to be: out of camera mode to him,
            // in camera mode camera to camera.
            double start = settings.follow().startDistance();
            messages.sendConfiguredMessage(guide, start > 0.0 ? "tour-invite-all-none" : "tour-invite-all-none-cam-only",
                    "{start}", String.valueOf(Math.round(start)),
                    "{radius}", String.valueOf(Math.round(settings.follow().radius())));
            return;
        }
        messages.sendConfiguredMessage(guide, "tour-invited-all", "{count}", String.valueOf(invited.size()),
                "{players}", String.join(", ", invited),
                "{seconds}", String.valueOf(settings.follow().invitationSeconds()),
                "{time}", CamTimeLimit.formatSeconds(settings.follow().invitationSeconds()));
    }

    /**
     * Whom {@link #inviteAll} would invite now: the players he could invite,
     * see {@link CamTour#candidates}, but for those already invited, those he may
     * not invite again yet and those a restriction keeps from taking part.
     * Nobody while his tour is full, nor while he may not lead.
     */
    List<Player> invitable(Player guide) {
        List<Player> found = new ArrayList<>();
        if (tours.isFull(guide) || !plugin.getTourModeration().may(guide, TourBans.What.LEAD)) {
            return found;
        }
        for (Player other : tours.candidates(guide)) {
            if (hasInvited(guide, other) || inviteWait(guide, other) > 0L
                    || !plugin.getTourModeration().may(other, TourBans.What.JOIN)
                    || !cameraPlayers.contains(other.getUniqueId()) && settings.follow().startDistance() <= 0.0) {
                continue;
            }
            found.add(other);
        }
        return found;
    }

    /**
     * Invites him, once everything has been asked: the invitation holds for
     * {@code follow.invitation-seconds}, and he hears about it with the
     * buttons to accept and to turn it down.
     */
    private void sendInvitation(Player guide, Player other) {
        long now = System.currentTimeMillis();
        int holds = settings.follow().invitationSeconds();
        invitations.computeIfAbsent(other.getUniqueId(), id -> new HashMap<>())
                .put(guide.getUniqueId(), now + holds * 1000L);
        forgetWaits(now);
        lastInvited.computeIfAbsent(other.getUniqueId(), id -> new HashMap<>()).put(guide.getUniqueId(), now);
        tellInvited(other, guide, holds);
        settings.sounds().followInvite().play(other);
    }

    /**
     * Whether he may show others round: no restriction keeps him from it, he
     * can lead one from where he is - in camera mode, or out of it with
     * {@code follow.lead-without-cam} -, and he follows no tour himself. If not,
     * he has been told why.
     */
    private boolean checkGuide(Player guide) {
        if (!tours.isEnabled()) {
            messages.sendConfiguredMessage(guide, "tour-off");
            return false;
        }
        if (!plugin.getTourModeration().check(guide, TourBans.What.LEAD)) {
            return false;
        }
        if (!tours.canLead(guide)) {
            messages.sendConfiguredMessage(guide, settings.follow().leadWithoutCam() ? "tour-guide-spectator"
                    : "tour-not-in-cam");
            return false;
        }
        if (tours.follows(guide.getUniqueId())) {
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
        long cooldown = settings.follow().inviteCooldownSeconds() * 1000L;
        for (Map<UUID, Long> own : lastInvited.values()) {
            own.values().removeIf(at -> at + cooldown <= now);
        }
        lastInvited.values().removeIf(Map::isEmpty);
    }

    /**
     * Tells a player that he is invited, and for how many seconds, with the
     * buttons to accept and to turn it down - or, on Bedrock while only the
     * menu goes, that he answers there. On Bedrock, with
     * {@code bedrock.invitation-forms}, a window asks him as well - while
     * there are buttons at all.
     */
    private void tellInvited(Player other, Player guide, int seconds) {
        if (!messages.isMessageEnabled("tour-invitation")) {
            return;
        }
        String name = guide.getName();
        String text = messages.getMessage("tour-invitation").replace("{player}", name)
                .replace("{seconds}", String.valueOf(seconds)).replace("{time}", CamTimeLimit.formatSeconds(seconds));
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        if (answerable() && plugin.getBedrock().answersInMenu(other)) {
            // He cannot click them, and typing is not what the menu is for.
            line.addAll(plugin.getBedrock().menuHint());
        } else {
            line.addAll(button(other, "tour-button-accept", name, "/cam follow accept " + name));
            line.addAll(button(other, "tour-button-decline", name, "/cam follow decline " + name));
        }
        other.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        if (answerable() && plugin.getBedrock().invitationForms(other)) {
            UUID guideId = guide.getUniqueId();
            plugin.getBedrock().forms().question(other, messages.getMessage("bedrock-form-follow-title"), text,
                    messages.getMessage("bedrock-form-follow-accept"),
                    messages.getMessage("bedrock-form-follow-decline"), yes -> {
                        // Whoever is still online by then: one who left has no invitation open.
                        Player now = Bukkit.getPlayer(guideId);
                        if (yes) {
                            accept(other, now, name);
                        } else {
                            decline(other, now, name);
                        }
                    }, null);
        }
    }

    /**
     * Whether an invitation can be answered: through the commands or the
     * menu. The buttons run the command either way, as an answer to the
     * invitation needs no menu.
     */
    private boolean answerable() {
        return settings.menu().access().allowsMenu() || settings.menu().access().allowsCommands();
    }

    /**
     * One button behind the invitation, running its command - also while
     * only the menu goes, as an answer to the invitation needs no menu -,
     * with the text of the same name with {@code -hover} after it on hovering,
     * and on Bedrock the command written out, see
     * {@link de.elia.cameraplugin.bedrock.BedrockPlayers#chatButton}.
     */
    private List<BaseComponent> button(Player to, String key, String guide, String command) {
        if (!answerable() || !messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{player}", guide);
        if (text.isEmpty()) {
            return List.of();
        }
        return plugin.getBedrock().chatButton(to, text, messages.getMessage(key + "-hover").replace("{player}", guide),
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
    }

    /**
     * Accepts an invitation: everything the invitation asked is asked once
     * more - the two may have moved since. Out of camera mode, it starts
     * first, where he stands, with every check and cost of a start of
     * {@code /cam}. Then his camera goes to the guide, looking his way, and
     * follows him from now on.
     */
    void accept(Player guest, Player guide, String name) {
        if (!tours.isEnabled()) {
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
        if (!tours.canLead(guide) || tours.follows(guide.getUniqueId())) {
            invitations.get(guest.getUniqueId()).remove(guide.getUniqueId());
            messages.sendConfiguredMessage(guest, settings.follow().leadWithoutCam() ? "tour-guide-cannot-lead"
                    : "tour-guide-gone", "{player}", guide.getName());
            return;
        }
        if (tours.isFull(guide)) {
            // The invitation stays: once a guest leaves, there is room again.
            messages.sendConfiguredMessage(guest, "tour-full", "{player}", guide.getName());
            return;
        }
        // Before camera mode is started for it: a guide out of camera mode
        // shows his tour round and follows none at the same time.
        if (tours.busy(guest)) {
            messages.sendConfiguredMessage(guest, "tour-busy");
            return;
        }
        if (!cameraPlayers.contains(guest.getUniqueId())) {
            startThenJoin(guest, guide);
            return;
        }
        if (!tours.near(guide, guest)) {
            messages.sendConfiguredMessage(guest, "tour-too-far-guest", "{player}", guide.getName());
            return;
        }
        tours.join(guest, guide);
    }

    /**
     * Accepts an invitation out of camera mode: if he stands within
     * {@code follow.start-distance} of the guide's camera, which his camera
     * could reach from there, camera mode starts where he stands, and he
     * joins once it has.
     */
    private void startThenJoin(Player guest, Player guide) {
        double reach = settings.follow().startDistance();
        if (reach <= 0.0) {
            messages.sendConfiguredMessage(guest, "tour-not-in-cam");
            return;
        }
        double distance = CamTour.distanceOutside(guide, guest);
        if (distance != Double.MAX_VALUE && distance > reach) {
            messages.sendConfiguredMessage(guest, "tour-start-too-far", "{player}", guide.getName(),
                    "{blocks}", String.valueOf(CamSpots.blocksTooFar(distance, reach)));
            return;
        }
        // In another world as well: there is nothing to measure.
        if (distance == Double.MAX_VALUE || !tours.reachableFrom(guest, guide)) {
            messages.sendConfiguredMessage(guest, "tour-guide-out-of-reach", "{player}", guide.getName());
            return;
        }
        plugin.getStarter().startThen(guest, () -> {
            if (tours.free(guest) && hasInvited(guide, guest)
                    && tours.canLead(guide) && !tours.follows(guide.getUniqueId())) {
                tours.join(guest, guide);
            }
        });
    }

    /** Turns an invitation down; the guide hears about it. */
    void decline(Player guest, Player guide, String name) {
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
     * He accepted the guide's invitation and is on the tour now: the
     * invitation is used up, and the wait is there against invitations nobody
     * wants - this one was wanted, so the guide may invite him again at once.
     */
    void accepted(UUID guest, UUID guide) {
        invitations.get(guest).remove(guide);
        Map<UUID, Long> invitedBy = lastInvited.get(guest);
        if (invitedBy != null && invitedBy.remove(guide) != null && invitedBy.isEmpty()) {
            lastInvited.remove(guest);
        }
    }

    /** Forgets the invitations he has: he left, say, or may not take part any more. */
    void forgetReceived(UUID player) {
        invitations.remove(player);
    }

    /** Forgets the invitations he gave: he left, say, or may not lead any more. */
    void forgetGiven(UUID guide) {
        for (Map<UUID, Long> own : invitations.values()) {
            own.remove(guide);
        }
    }

    /** After a reload or when the plugin is switched off: no invitation holds, and nobody waits. */
    void clear() {
        invitations.clear();
        lastInvited.clear();
    }
}
