package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sharing of the spots of {@link CamSpots}, with {@code viewpoints.sharing}:
 * {@code /cam spot share} and {@code unshare} - with a player or with
 * everybody -, {@code accept}, {@code decline} and {@code like}, from the
 * command and from the menu alike. Who shares what with whom is kept by
 * {@link SharedSpots}; a like goes into the guestbook of the owner, see
 * {@link SpotGuestbook}.
 */
final class SpotSharing {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final SharedSpots shared;
    private final SpotGuestbook guestbook;
    /** The spots, asked which of his he has and which he accepted. */
    private final CamSpots spots;
    /** The list in the chat, whose buttons an offer shows as well. */
    private final SpotList list;

    SpotSharing(CameraPlugin plugin, SharedSpots shared, SpotGuestbook guestbook, CamSpots spots, SpotList list) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.shared = shared;
        this.guestbook = guestbook;
        this.spots = spots;
        this.list = list;
    }

    /**
     * {@code /cam spot share <spot> <player|all>}: makes one of his spots
     * public, or offers it to a player - one who has been on the server,
     * online or not -, who has it once he accepts it. A player who is online
     * hears about it, with the buttons to accept it and to turn it down.
     */
    void share(Player player, String name, String with) {
        if (!settings.spots().sharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Spot spot = spots.spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (with.equalsIgnoreCase(CamSpots.EVERYBODY)) {
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
    void shareWith(Player player, Spot spot, OfflinePlayer other) {
        if (!plugin.getSpotModeration().checkNew(player, SpotBans.Kind.SHARED)) {
            return;
        }
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
     * makes the spot private again.
     */
    void unshare(Player player, String name, String with) {
        if (!settings.spots().sharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Spot spot = spots.spotsOf(player).get(Spot.key(name));
        if (spot == null) {
            messages.sendConfiguredMessage(player, "spot-unknown", "{spot}", name);
            return;
        }
        if (with.equalsIgnoreCase(CamSpots.EVERYBODY)) {
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
    void unshareWith(Player player, Spot spot, UUID otherId, String otherName) {
        boolean removed = shared.unshare(player.getUniqueId(), spot.key(), otherId);
        messages.sendConfiguredMessage(player, removed ? "spot-unshared" : "spot-not-shared",
                "{spot}", spot.name(), "{player}", otherName);
    }

    /**
     * Makes one of his spots public, or private again, from
     * {@code /cam spot share|unshare <spot> all} or the menu. A spot made
     * private keeps whom it is shared with by name, and its likes.
     */
    void makePublic(Player player, Spot spot, boolean everybody) {
        if (!settings.spots().sharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        if (everybody) {
            if (!plugin.getSpotModeration().checkNew(player, SpotBans.Kind.PUBLIC)) {
                return;
            }
            shared.share(player.getUniqueId(), player.getName(), spot, null, null);
            messages.sendConfiguredMessage(player, "spot-shared-all", "{spot}", spot.name());
        } else {
            boolean removed = shared.unshare(player.getUniqueId(), spot.key(), null);
            messages.sendConfiguredMessage(player, removed ? "spot-unshared-all" : "spot-not-shared-all",
                    "{spot}", spot.name());
        }
    }

    /** {@code /cam spot accept Owner:spot}: takes a spot offered to him. */
    void accept(Player player, String label) {
        if (!settings.spots().sharing()) {
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
    void accept(Player player, Share share) {
        if (!share.isOfferedTo(player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", shown(share));
            return;
        }
        shared.accept(share, player.getUniqueId(), player.getName());
        messages.sendConfiguredMessage(player, "spot-accepted", "{spot}", shown(share));
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
    void decline(Player player, String label) {
        if (!settings.spots().sharing()) {
            messages.sendConfiguredMessage(player, "spot-share-off");
            return;
        }
        Share share = shared.findOffer(player.getUniqueId(), label);
        if (share == null) {
            for (Share own : spots.accepted(player)) {
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
    void decline(Player player, Share share) {
        boolean offer = share.isOfferedTo(player.getUniqueId());
        if (!shared.decline(share, player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-offer-unknown", "{spot}", shown(share));
            return;
        }
        messages.sendConfiguredMessage(player, offer ? "spot-declined" : "spot-removed", "{spot}", shown(share));
        Player owner = Bukkit.getPlayer(share.getOwner());
        if (owner != null && offer) {
            messages.sendConfiguredMessage(owner, "spot-declined-owner", "{player}", player.getName(),
                    "{spot}", share.getSpot().name());
        }
    }

    /** {@code /cam spot like Owner:spot}: likes a public spot of another player, or no longer does. */
    void like(Player player, String label) {
        if (!settings.spots().sharing()) {
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
    void like(Player player, Share share) {
        if (share.getOwner().equals(player.getUniqueId())) {
            messages.sendConfiguredMessage(player, "spot-like-own");
            return;
        }
        if (!share.isEveryone()) {
            messages.sendConfiguredMessage(player, "spot-public-unknown", "{spot}", shown(share));
            return;
        }
        boolean likes = shared.toggleLike(share, player.getUniqueId());
        messages.sendConfiguredMessage(player, likes ? "spot-liked" : "spot-unliked", "{spot}", shown(share),
                "{likes}", String.valueOf(share.likes()));
        guestbook.liked(share.getOwner(), share.getSpot(), player, likes);
    }

    /**
     * How a message names a spot of another player, {@code spot-name-other}:
     * {@code Haus (Steve)}. {@link Share#label} stays for the commands and
     * for {@code /cam spot list}, which shows what to type.
     */
    String shown(Share share) {
        return messages.getMessage("spot-name-other").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
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

    /**
     * Tells a player online that a spot is offered to him, with the buttons to
     * accept it and to turn it down. They run their command while only the
     * menu goes as well - {@link de.elia.cameraplugin.camfly2.CamCommand}
     * lets it through -, so a click answers right away; on Bedrock, where
     * nothing in the chat can be clicked, the line says then that he answers
     * in the menu. On Bedrock, with
     * {@code bedrock.invitation-forms}, a window asks him as well - while
     * there are buttons at all.
     */
    private void tellOffered(Player other, Share share) {
        if (!messages.isMessageEnabled("spot-offered-to-you")) {
            return;
        }
        String text = messages.getMessage("spot-offered-to-you")
                .replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        boolean answerable = settings.menu().access().allowsMenu() || settings.menu().access().allowsCommands();
        if (answerable && plugin.getBedrock().answersInMenu(other)) {
            // He cannot click them, and typing is not what the menu is for.
            line.addAll(plugin.getBedrock().menuHint());
        } else if (answerable) {
            // The hover names the spot as the line does; only the click
            // takes the command's Owner:spot.
            line.addAll(list.button(other, "spot-button-accept", shown(share),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot accept " + share.label())));
            line.addAll(list.button(other, "spot-button-decline", shown(share),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot decline " + share.label())));
        }
        other.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        if (answerable && plugin.getBedrock().invitationForms(other)) {
            plugin.getBedrock().forms().question(other, messages.getMessage("bedrock-form-spot-offer-title"), text,
                    messages.getMessage("bedrock-form-spot-offer-accept"),
                    messages.getMessage("bedrock-form-spot-offer-decline"), yes -> {
                        if (!settings.spots().sharing()) {
                            messages.sendConfiguredMessage(other, "spot-share-off");
                        } else if (yes) {
                            accept(other, share);
                        } else {
                            decline(other, share);
                        }
                    }, null);
        }
    }
}
