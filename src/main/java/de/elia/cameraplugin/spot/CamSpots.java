package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Spots, {@code /cam spot}: places the camera jumps to, each looking a way of
 * its own - for a build looked at from the same spot over and over.
 *
 * <p>A spot is saved where the player is, on foot or as a camera, so only a
 * place he has been to himself becomes one. The camera jumps to it only
 * where it would be allowed to fly, see {@link JumpCheck}: in the world of its
 * body, within {@code max-distance} of it - or of the spot it visits -, where
 * the signal reaches, outside the areas {@code cam-area} keeps it out of, out
 * of shut lava, water and powder snow and not into a block that has been put
 * there since.</p>
 *
 * <p>Each spot of his is private, or - with {@code viewpoints.sharing} -
 * public, {@code /cam spot share <spot> all}: then everybody finds it among
 * the public spots, and may like it, {@code /cam spot like}. He may also
 * offer it to single players, {@code /cam spot share <spot> <player>}; it is
 * theirs once they accept it, {@code /cam spot accept}, and they may turn it
 * down or drop it again, {@code /cam spot decline}, see {@link SharedSpots}.
 * Others jump to a spot they may use as to one of their own,
 * {@code /cam spot Owner:spot} - by the same rules, held from their own body
 * -, and with {@code visitor-camera} they visit it from wherever they are,
 * {@code /cam visit}.</p>
 *
 * <p>Where they are kept is {@code viewpoints.storage}: in the file of the
 * player, or in memory only, which {@code /cam reload} and a restart
 * forget.</p>
 */
public final class CamSpots {

    /** What a name of a spot is made of: letters, digits, - and _, one to sixteen of them. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    /** The words after {@code /cam spot} that are no spot. */
    private static final Set<String> WORDS = Set.of("set", "delete", "list", "share", "unshare", "accept",
            "decline", "like");

    /** The word that makes a spot public, {@code /cam spot share <spot> all}. */
    private static final String EVERYBODY = "all";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final SharedSpots shared;
    private final JumpCheck jumpCheck;
    /** The spots of each player, by {@link Spot#key()}, on {@code storage: memory}. */
    private final Map<UUID, Map<String, Spot>> inMemory = new HashMap<>();

    public CamSpots(CameraPlugin plugin, PlayerStore store, SharedSpots shared, JumpCheck jumpCheck) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
        this.shared = shared;
        this.jumpCheck = jumpCheck;
    }

    /** Whether {@code /cam spot} is there at all. */
    public boolean isEnabled() {
        return settings.isViewpointsEnabled();
    }

    private boolean inFile() {
        return settings.getViewpointsStorage() == StorageMode.FILE;
    }

    /** The spots of a player, by {@link Spot#key()}, in the order he saved them; not to be changed. */
    private Map<String, Spot> spotsOf(Player player) {
        if (inFile()) {
            return store.get(player.getUniqueId()).getSpots();
        }
        return Collections.unmodifiableMap(inMemory.getOrDefault(player.getUniqueId(), Map.of()));
    }

    /** His own spots, private and public, in the order he saved them. */
    public Collection<Spot> own(Player player) {
        return spotsOf(player).values();
    }

    /** The share of one of his spots, or {@code null} while it is shared with nobody. */
    public Share shareOf(Player player, Spot spot) {
        return settings.isViewpointsSharing() ? shared.get(player.getUniqueId(), spot.key()) : null;
    }

    /** What others share with him: the public spots and those he accepted, for the tab completion. */
    public List<Share> sharedWith(Player player) {
        return settings.isViewpointsSharing() ? shared.sharedWith(player.getUniqueId()) : List.of();
    }

    /** Every public spot, his own among them, for the page of the public spots. */
    public List<Share> publicSpots() {
        return settings.isViewpointsSharing() ? shared.publicSpots() : List.of();
    }

    /** What others offered him and he has not accepted yet. */
    public List<Share> offers(Player player) {
        return settings.isViewpointsSharing() ? shared.offeredTo(player.getUniqueId()) : List.of();
    }

    /** What others share with him by name and he accepted, for the page of the shared spots. */
    public List<Share> accepted(Player player) {
        return settings.isViewpointsSharing() ? shared.acceptedBy(player.getUniqueId()) : List.of();
    }

    /** The names of his spots, for the tab completion. */
    public List<String> names(Player player) {
        List<String> names = new ArrayList<>();
        for (Spot spot : spotsOf(player).values()) {
            names.add(spot.name());
        }
        return names;
    }

    /** {@code Owner:spot} of every spot he may use, for the tab completion. */
    public List<String> sharedLabels(Player player) {
        return labels(sharedWith(player));
    }

    /** {@code Owner:spot} of what was offered to him, for the tab completion of {@code accept}. */
    public List<String> offerLabels(Player player) {
        return labels(offers(player));
    }

    /** {@code Owner:spot} of what he may decline: the offers and what he accepted. */
    public List<String> declinableLabels(Player player) {
        List<String> labels = labels(offers(player));
        labels.addAll(labels(accepted(player)));
        return labels;
    }

    /** {@code Owner:spot} of the public spots of others, for the tab completion of {@code like}. */
    public List<String> likeableLabels(Player player) {
        List<String> labels = new ArrayList<>();
        for (Share share : publicSpots()) {
            if (!share.getOwner().equals(player.getUniqueId())) {
                labels.add(share.label());
            }
        }
        return labels;
    }

    private static List<String> labels(List<Share> shares) {
        List<String> labels = new ArrayList<>();
        for (Share share : shares) {
            labels.add(share.label());
        }
        return labels;
    }

    /**
     * Whom one of his spots is shared with, for the tab completion of
     * {@code /cam spot unshare}: {@code all} while it is public, and the
     * names, accepted or offered.
     */
    public List<String> sharedNames(Player player, String name) {
        Share share = settings.isViewpointsSharing() ? shared.get(player.getUniqueId(), Spot.key(name)) : null;
        List<String> names = new ArrayList<>();
        if (share != null) {
            if (share.isEveryone()) {
                names.add(EVERYBODY);
            }
            names.addAll(share.getPlayers().values());
            names.addAll(share.getPending().values());
        }
        return names;
    }

    /** {@code /cam spot}, with what follows it. */
    public void command(Player player, String[] args) {
        if (!isEnabled()) {
            messages.sendConfiguredMessage(player, "spot-off");
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list(player);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && first.equals("set")) {
            set(player, args[1]);
        } else if (args.length == 2 && first.equals("delete")) {
            delete(player, args[1]);
        } else if (args.length == 3 && first.equals("share")) {
            share(player, args[1], args[2]);
        } else if (args.length == 3 && first.equals("unshare")) {
            unshare(player, args[1], args[2]);
        } else if (args.length == 2 && first.equals("accept")) {
            accept(player, args[1]);
        } else if (args.length == 2 && first.equals("decline")) {
            decline(player, args[1]);
        } else if (args.length == 2 && first.equals("like")) {
            like(player, args[1]);
        } else if (args.length == 1 && !WORDS.contains(first)) {
            jump(player, args[0]);
        } else {
            messages.sendConfiguredMessage(player, "spot-usage");
        }
    }

    /**
     * Saves the spot where he is, looking the way he looks. A spot of the
     * same name moves here and keeps the day it was first saved.
     *
     * @return the spot, or {@code null} when it was not saved; he has been told why
     */
    public Spot set(Player player, String name) {
        if (!NAME.matcher(name).matches() || WORDS.contains(Spot.key(name))) {
            messages.sendConfiguredMessage(player, "spot-bad-name");
            return null;
        }
        Map<String, Spot> spots = spotsOf(player);
        Spot before = spots.get(Spot.key(name));
        if (before == null && spots.size() >= settings.getViewpointsMax()) {
            messages.sendConfiguredMessage(player, "spot-full", "{max}",
                    String.valueOf(settings.getViewpointsMax()));
            return null;
        }
        // Under the name he gave it now, capitals and all.
        Spot spot = before == null ? Spot.at(name, player.getLocation())
                : Spot.at(name, player.getLocation(), before.created());
        if (inFile()) {
            store.get(player.getUniqueId()).putSpot(player.getName(), spot);
            store.save(player.getUniqueId());
        } else {
            inMemory.computeIfAbsent(player.getUniqueId(), id -> new LinkedHashMap<>()).put(spot.key(), spot);
        }
        // Whoever it is shared with finds it where it is now.
        shared.moved(player.getUniqueId(), player.getName(), spot);
        messages.sendConfiguredMessage(player, before != null ? "spot-moved" : "spot-saved", "{spot}", name);
        return spot;
    }

    public void delete(Player player, String name) {
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (inFile()) {
            PlayerRecord record = store.get(player.getUniqueId());
            record.removeSpot(spot.key());
            store.save(player.getUniqueId());
        } else {
            inMemory.get(player.getUniqueId()).remove(spot.key());
        }
        // A spot that is gone is shared with nobody, and nobody visits it.
        shared.deleted(player.getUniqueId(), spot.key());
        plugin.getVisit().checkVisitors(player.getUniqueId(), spot.key());
        messages.sendConfiguredMessage(player, "spot-deleted", "{spot}", spot.name());
    }

    /**
     * {@code /cam spot share <spot> <player|all>}: makes one of his spots
     * public, or offers it to a player - one who has been on the server,
     * online or not -, who has it once he accepts it. A player who is online
     * hears about it, with the buttons to accept it and to turn it down.
     */
    public void share(Player player, String name, String with) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (with.equalsIgnoreCase(EVERYBODY)) {
            makePublic(player, spot, true);
            return;
        }
        OfflinePlayer other = findPlayer(with);
        if (other == null) {
            messages.sendConfiguredMessage(player, "spot-share-unknown-player", "{player}", with);
            return;
        }
        shareWith(player, spot, other);
    }

    /**
     * Offers one of his spots to a player, from {@code /cam spot share} or
     * the menu. One who accepted it already keeps it as it is.
     */
    public void shareWith(Player player, Spot spot, OfflinePlayer other) {
        if (other.getUniqueId().equals(player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-share-self");
            return;
        }
        String otherName = other.getName() == null ? other.getUniqueId().toString() : other.getName();
        boolean added = shared.share(player.getUniqueId(), player.getName(), spot, other.getUniqueId(), otherName);
        Share share = shared.get(player.getUniqueId(), spot.key());
        if (share != null && share.isAcceptedBy(other.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-shared-already", "{spot}", spot.name(),
                    "{player}", otherName);
            return;
        }
        messages.sendConfiguredMessage(player, "spot-offered", "{spot}", spot.name(), "{player}", otherName);
        if (added && other.getPlayer() != null && share != null) {
            tellOffered(other.getPlayer(), share);
        }
    }

    /**
     * {@code /cam spot unshare <spot> <player|all>}: takes a share back -
     * from a player, accepted or still offered, or from everybody, which
     * makes the spot private again. Whoever visits the spot and may no
     * longer is taken back to his body.
     */
    public void unshare(Player player, String name, String with) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Spot spot = spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (with.equalsIgnoreCase(EVERYBODY)) {
            makePublic(player, spot, false);
            return;
        }
        UUID otherId = null;
        String otherName = with;
        Share share = shared.get(player.getUniqueId(), spot.key());
        if (share != null) {
            Map<UUID, String> whom = new LinkedHashMap<>(share.getPending());
            whom.putAll(share.getPlayers());
            for (Map.Entry<UUID, String> entry : whom.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(with)) {
                    otherId = entry.getKey();
                    otherName = entry.getValue();
                }
            }
        }
        if (otherId == null) {
            OfflinePlayer other = findPlayer(with);
            if (other == null) {
                messages.sendConfiguredMessage(player, "spot-share-unknown-player", "{player}", with);
                return;
            }
            otherId = other.getUniqueId();
            otherName = other.getName() == null ? with : other.getName();
        }
        unshareWith(player, spot, otherId, otherName);
    }

    /** Takes a share back from a player, accepted or offered, from {@code /cam spot unshare} or the menu. */
    public void unshareWith(Player player, Spot spot, UUID otherId, String otherName) {
        boolean removed = shared.unshare(player.getUniqueId(), spot.key(), otherId);
        messages.sendConfiguredMessage(player, removed ? "spot-unshared" : "spot-not-shared",
                "{spot}", spot.name(), "{player}", otherName);
        plugin.getVisit().checkVisitors(player.getUniqueId(), spot.key());
    }

    /**
     * Makes one of his spots public, or private again, from
     * {@code /cam spot share|unshare <spot> all} or the menu. A spot made
     * private keeps whom it is shared with by name, and its likes.
     */
    public void makePublic(Player player, Spot spot, boolean everybody) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        if (everybody) {
            shared.share(player.getUniqueId(), player.getName(), spot, null, null);
            messages.sendConfiguredMessage(player, "spot-shared-all", "{spot}", spot.name());
        } else {
            boolean removed = shared.unshare(player.getUniqueId(), spot.key(), null);
            messages.sendConfiguredMessage(player, removed ? "spot-unshared-all" : "spot-not-shared-all",
                    "{spot}", spot.name());
            plugin.getVisit().checkVisitors(player.getUniqueId(), spot.key());
        }
    }

    /** {@code /cam spot accept Owner:spot}: takes a spot offered to him. */
    public void accept(Player player, String label) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Share share = shared.findOffer(player.getUniqueId(), label);
        if (share == null) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", label);
            return;
        }
        accept(player, share);
    }

    /** Takes a spot offered to him, from {@code /cam spot accept} or the menu; its owner hears about it. */
    public void accept(Player player, Share share) {
        if (!share.isOfferedTo(player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", share.label());
            return;
        }
        shared.accept(share, player.getUniqueId(), player.getName());
        messages.sendConfiguredMessage(player, "spot-accepted", "{spot}", share.label());
        Player owner = Bukkit.getPlayer(share.getOwner());
        if (owner != null) {
            messages.sendConfiguredMessage(owner, "spot-accepted-owner", "{player}", player.getName(),
                    "{spot}", share.getSpot().name());
        }
    }

    /**
     * {@code /cam spot decline Owner:spot}: turns down a spot offered to him,
     * or drops one he accepted - one that is public stays among the public
     * spots all the same.
     */
    public void decline(Player player, String label) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Share share = shared.findOffer(player.getUniqueId(), label);
        if (share == null) {
            for (Share own : accepted(player)) {
                if (own.label().equalsIgnoreCase(label)) {
                    share = own;
                }
            }
        }
        if (share == null) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", label);
            return;
        }
        decline(player, share);
    }

    /** Turns down an offer or drops a spot he accepted, from {@code /cam spot decline} or the menu. */
    public void decline(Player player, Share share) {
        boolean offer = share.isOfferedTo(player.getUniqueId());
        if (!shared.decline(share, player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", share.label());
            return;
        }
        plugin.getVisit().checkVisitors(share.getOwner(), share.getSpot().key());
        messages.sendConfiguredMessage(player, offer ? "spot-declined" : "spot-removed", "{spot}", share.label());
        Player owner = Bukkit.getPlayer(share.getOwner());
        if (owner != null && offer) {
            messages.sendConfiguredMessage(owner, "spot-declined-owner", "{player}", player.getName(),
                    "{spot}", share.getSpot().name());
        }
    }

    /** {@code /cam spot like Owner:spot}: likes a public spot of another player, or no longer does. */
    public void like(Player player, String label) {
        if (!settings.isViewpointsSharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Share share = shared.findPublic(label);
        if (share == null) {
            messages.sendConfiguredMessage(player, "spot-public-unknown", "{spot}", label);
            return;
        }
        like(player, share);
    }

    /** Likes a public spot, or no longer does, from {@code /cam spot like} or the menu. */
    public void like(Player player, Share share) {
        if (share.getOwner().equals(player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-like-own");
            return;
        }
        if (!share.isEveryone()) {
            messages.sendConfiguredMessage(player, "spot-public-unknown", "{spot}", share.label());
            return;
        }
        boolean likes = shared.toggleLike(share, player.getUniqueId());
        messages.sendConfiguredMessage(player, likes ? "spot-liked" : "spot-unliked", "{spot}", share.label(),
                "{likes}", String.valueOf(share.likes()));
    }

    /**
     * A player by name: online, or one who has been on the server before.
     * Looked up only among those the server knows, so a name is never asked
     * of Mojang - that would hold up the server.
     */
    private static OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer known : Bukkit.getOfflinePlayers()) {
            if (known.getName() != null && known.getName().equalsIgnoreCase(name)) {
                return known;
            }
        }
        return null;
    }

    /** Tells a player online that a spot is offered to him, with the buttons to accept it and to turn it down. */
    private void tellOffered(Player other, Share share) {
        if (!messages.isMessageEnabled("spot-offered-to-you")) {
            return;
        }
        String text = messages.getMessage("spot-offered-to-you")
                .replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        line.addAll(commandButton("spot-button-accept", share.label(), "/cam spot accept " + share.label()));
        line.addAll(commandButton("spot-button-decline", share.label(), "/cam spot decline " + share.label()));
        other.spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    /** A button that runs a command - or opens the menu, while only the menu goes - or nothing at all. */
    private List<BaseComponent> commandButton(String key, String spot, String command) {
        String run = plugin.commandOrMenu(command);
        return run == null ? List.of() : button(key, spot, new ClickEvent(ClickEvent.Action.RUN_COMMAND, run));
    }

    /**
     * Lists his spots, each with a button that jumps to it, one that puts the
     * command to share it into his chat line and one that puts the command to
     * delete it there: deleting takes one key more than a click, so that a
     * slip of the mouse loses nothing. Below them what others share with him
     * by name, the public spots of others, and what is offered to him, with
     * the buttons that go there, like, accept and turn down.
     */
    private void list(Player player) {
        Map<String, Spot> spots = spotsOf(player);
        if (spots.isEmpty()) {
            messages.sendConfiguredMessage(player, "spot-list-empty");
        } else {
            messages.sendConfiguredMessage(player, "spot-list-header", "{count}", String.valueOf(spots.size()),
                    "{max}", String.valueOf(settings.getViewpointsMax()));
            if (messages.isMessageEnabled("spot-list-entry")) {
                for (Spot spot : spots.values()) {
                    player.spigot().sendMessage(ownLine(player, spot));
                }
            }
        }
        listShares(player, accepted(player), "spot-list-shared-header", "spot-list-shared-entry", false);
        List<Share> others = new ArrayList<>();
        for (Share share : publicSpots()) {
            if (!share.getOwner().equals(player.getUniqueId())) {
                others.add(share);
            }
        }
        listShares(player, others, "spot-list-public-header", "spot-list-public-entry", true);
        List<Share> offers = offers(player);
        if (offers.isEmpty()) {
            return;
        }
        messages.sendConfiguredMessage(player, "spot-list-offers-header", "{count}", String.valueOf(offers.size()));
        if (!messages.isMessageEnabled("spot-list-offers-entry")) {
            return;
        }
        for (Share share : offers) {
            String text = fillPlace(messages.getMessage("spot-list-offers-entry"), share.getSpot())
                    .replace("{owner}", share.getOwnerName());
            List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
            line.addAll(button("spot-button-accept", share.label(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot accept " + share.label())));
            line.addAll(button("spot-button-decline", share.label(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot decline " + share.label())));
            player.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        }
    }

    /** One part of the list below his own spots: a header and a line for each, with its buttons. */
    private void listShares(Player player, List<Share> shares, String header, String entry, boolean likes) {
        if (shares.isEmpty()) {
            return;
        }
        messages.sendConfiguredMessage(player, header, "{count}", String.valueOf(shares.size()));
        if (!messages.isMessageEnabled(entry)) {
            return;
        }
        for (Share share : shares) {
            String text = fillPlace(messages.getMessage(entry), share.getSpot())
                    .replace("{owner}", share.getOwnerName())
                    .replace("{likes}", String.valueOf(share.likes()));
            List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
            line.addAll(button("spot-button-go", share.label(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot " + share.label())));
            if (settings.isVisitorCameraEnabled()) {
                line.addAll(button("spot-button-visit", share.label(),
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam visit " + share.label())));
            }
            if (likes) {
                line.addAll(button(share.isLikedBy(player.getUniqueId()) ? "spot-button-unlike" : "spot-button-like",
                        share.label(), new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/cam spot like " + share.label())));
            }
            player.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        }
    }

    /** One of his own spots in the list: where it is, whether it is public, whom it is shared with, and its buttons. */
    private BaseComponent[] ownLine(Player player, Spot spot) {
        String text = fillPlace(messages.getMessage("spot-list-entry"), spot);
        Share share = shareOf(player, spot);
        if (share != null && share.isEveryone() && messages.isMessageEnabled("spot-list-public")) {
            text += messages.getMessage("spot-list-public").replace("{likes}", String.valueOf(share.likes()));
        }
        if (share != null && !share.getPlayers().isEmpty() && messages.isMessageEnabled("spot-list-shared")) {
            text += messages.getMessage("spot-list-shared").replace("{players}", whom(share));
        }
        if (share != null && !share.getPending().isEmpty() && messages.isMessageEnabled("spot-list-offered")) {
            text += messages.getMessage("spot-list-offered").replace("{players}", offeredTo(share));
        }
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        line.addAll(button("spot-button-go", spot.name(),
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot " + spot.name())));
        if (settings.isViewpointsSharing()) {
            line.addAll(button("spot-button-share", spot.name(),
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cam spot share " + spot.name() + " ")));
        }
        line.addAll(button("spot-button-delete", spot.name(),
                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cam spot delete " + spot.name())));
        return line.toArray(new BaseComponent[0]);
    }

    /** Who accepted a share, written out. */
    public String whom(Share share) {
        return String.join(", ", share.getPlayers().values());
    }

    /** Whom a share is offered to and who has not accepted it yet, written out. */
    public String offeredTo(Share share) {
        return String.join(", ", share.getPending().values());
    }

    /** {@code {spot}}, {@code {x}}, {@code {y}}, {@code {z}} and {@code {world}} of a line of the list. */
    private static String fillPlace(String text, Spot spot) {
        return text.replace("{spot}", spot.name())
                .replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                .replace("{world}", spot.world());
    }

    /**
     * One button behind a line of the list: its text, and on hovering the
     * text of the same name with {@code -hover} after it. Nothing when the
     * text is switched off or empty.
     */
    private List<BaseComponent> button(String key, String spot, ClickEvent click) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{spot}", spot);
        if (text.isEmpty()) {
            return List.of();
        }
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(
                messages.getMessage(key + "-hover").replace("{spot}", spot))));
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
     * Puts the camera down at one of his spots, or at one shared with him -
     * {@code Owner:spot} -, if it may be there.
     */
    public void jump(Player player, String name) {
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        if (data == null) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        Spot spot;
        String label;
        if (name.indexOf(':') >= 0) {
            Share share = settings.isViewpointsSharing() ? shared.find(player.getUniqueId(), name) : null;
            if (share == null) {
                messages.sendConfiguredMessage(player, "spot-shared-unknown", "{spot}", name);
                return;
            }
            spot = share.getSpot();
            label = share.label();
        } else {
            spot = spotsOf(player).get(Spot.key(name));
            if (spot == null) {
                messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
                return;
            }
            label = spot.name();
        }
        Location target = spot.toLocation();
        if (target == null || !target.getWorld().equals(data.getBody().getWorld())) {
            messages.sendConfiguredMessage(player, "spot-other-world", "{spot}", label);
            return;
        }
        CamMovementGuard guard = plugin.getMovementGuard();
        if (!jumpCheck.mayGo(player, guard.home(data), guard.homeEyes(player, data), target, "spot",
                "{spot}", label)) {
            return;
        }
        // A jump of his own takes him off a tour he follows.
        plugin.getTour().leave(player);
        if (!guard.jumpTo(player, data, target)) {
            return;
        }
        settings.getSpotSound().play(player);
        if (messages.isMessageEnabled("spot-jumped")) {
            plugin.getActionBar().flash(player, messages.getMessage("spot-jumped").replace("{spot}", label));
        }
    }

    /** After a reload: on {@code storage: memory} every spot is forgotten. */
    public void onReload() {
        inMemory.clear();
    }
}
