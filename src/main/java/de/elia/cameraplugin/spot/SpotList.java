package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The list of {@link CamSpots} in the chat, {@code /cam spot list} - or
 * {@code /cam spot} alone -: his own spots, what others share with him by
 * name, the public spots of others and what is offered to him, each line
 * with its buttons. The buttons of an offer, see {@link SpotSharing}, are
 * made the same way, see {@link #button}.
 */
final class SpotList {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The spots, asked what is his, what is shared with him and how a look is written out. */
    private final CamSpots spots;

    SpotList(CameraPlugin plugin, CamSpots spots) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.spots = spots;
    }

    /**
     * Lists his spots, each with a button that jumps to it, one that puts the
     * command to share it into his chat line and one that puts the command to
     * delete it there: deleting takes one key more than a click, so that a
     * slip of the mouse loses nothing. Below them what others share with him
     * by name, the public spots of others, and what is offered to him, with
     * the buttons that go there, like, accept and turn down.
     */
    void show(Player player) {
        Map<String, Spot> own = spots.spotsOf(player);
        if (own.isEmpty()) {
            messages.sendConfiguredMessage(player, "spot-list-empty");
        } else {
            messages.sendConfiguredMessage(player, settings.spots().limited() ? "spot-list-header"
                    : "spot-list-header-unlimited", "{count}", String.valueOf(own.size()),
                    "{max}", String.valueOf(settings.spots().max()));
            if (messages.isMessageEnabled("spot-list-entry")) {
                for (Spot spot : own.values()) {
                    player.spigot().sendMessage(ownLine(player, spot));
                }
            }
        }
        listShares(player, spots.accepted(player), "spot-list-shared-header", "spot-list-shared-entry", false);
        List<Share> others = new ArrayList<>();
        for (Share share : spots.publicSpots()) {
            if (!share.getOwner().equals(player.getUniqueId())) {
                others.add(share);
            }
        }
        listShares(player, others, "spot-list-public-header", "spot-list-public-entry", true);
        List<Share> offers = spots.offers(player);
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
            line.addAll(button(player, "spot-button-accept", share.label(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot accept " + share.label())));
            line.addAll(button(player, "spot-button-decline", share.label(),
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
                    .replace("{likes}", String.valueOf(share.likes())) + lookSuffix(share.getSpot());
            List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
            line.addAll(button(player, "spot-button-go", share.label(),
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cam spot " + share.label())));
            if (likes) {
                line.addAll(button(player, share.isLikedBy(player.getUniqueId()) ? "spot-button-unlike"
                        : "spot-button-like", share.label(), new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/cam spot like " + share.label())));
                line.addAll(mapButton(player, share.label(), share.getSpot()));
            }
            player.spigot().sendMessage(line.toArray(new BaseComponent[0]));
        }
    }

    /** One of his own spots in the list: where it is, whether it is public, whom it is shared with, and its buttons. */
    private BaseComponent[] ownLine(Player player, Spot spot) {
        String text = fillPlace(messages.getMessage("spot-list-entry"), spot) + lookSuffix(spot);
        Share share = spots.shareOf(player, spot);
        if (share != null && share.isEveryone() && messages.isMessageEnabled("spot-list-public")) {
            text += messages.getMessage("spot-list-public").replace("{likes}", String.valueOf(share.likes()));
        }
        if (share != null && !share.getPlayers().isEmpty() && messages.isMessageEnabled("spot-list-shared")) {
            text += messages.getMessage("spot-list-shared").replace("{players}", spots.whom(share));
        }
        if (share != null && !share.getPending().isEmpty() && messages.isMessageEnabled("spot-list-offered")) {
            text += messages.getMessage("spot-list-offered").replace("{players}", spots.offeredTo(share));
        }
        List<BaseComponent> line = new ArrayList<>(Arrays.asList(TextComponent.fromLegacyText(text)));
        line.addAll(button(player, "spot-button-go", spot.name(),
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, jumpCommand(player, spot))));
        if (settings.spots().sharing()) {
            line.addAll(button(player, "spot-button-share", spot.name(),
                    new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cam spot share " + spot.name() + " ")));
        }
        line.addAll(button(player, "spot-button-delete", spot.name(),
                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/cam spot delete " + spot.name())));
        if (share != null && share.isEveryone()) {
            line.addAll(mapButton(player, spot.name(), spot));
        }
        return line.toArray(new BaseComponent[0]);
    }

    /**
     * The button that opens the web map at a public spot, while
     * {@code web-map.link} gives its address - nothing otherwise. Only for a
     * public spot: the others are not on the map.
     */
    private List<BaseComponent> mapButton(Player to, String label, Spot spot) {
        String address = plugin.getWebMap().linkTo(spot);
        return address == null ? List.of() : button(to, "spot-button-map", label,
                new ClickEvent(ClickEvent.Action.OPEN_URL, address));
    }

    /** What stands behind a spot in the list while it shows a look of its own, {@code spot-list-look}. */
    private String lookSuffix(Spot spot) {
        String described = spots.describe(spot.look());
        if (described.isEmpty() || !messages.isMessageEnabled("spot-list-look")) {
            return "";
        }
        return messages.getMessage("spot-list-look").replace("{look}", described);
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
     * text of the same name with {@code -hover} after it - on Bedrock with
     * the command written out, see {@link de.elia.cameraplugin.bedrock.BedrockPlayers#chatButton}.
     * Nothing when the text is switched off or empty.
     */
    List<BaseComponent> button(Player to, String key, String spot, ClickEvent click) {
        if (!messages.isMessageEnabled(key)) {
            return List.of();
        }
        String text = messages.getMessage(key).replace("{spot}", spot);
        if (text.isEmpty()) {
            return List.of();
        }
        return plugin.getBedrock().chatButton(to, text, messages.getMessage(key + "-hover").replace("{spot}", spot),
                click);
    }

    /**
     * The command that jumps to one of his spots: {@code /cam spot <name>},
     * or - when the name begins with a word of the command, such as
     * {@code list}, which the command would take for that word -
     * {@code /cam spot <he>:<name>}, as others reach it.
     */
    static String jumpCommand(Player player, Spot spot) {
        int space = spot.name().indexOf(' ');
        String first = (space < 0 ? spot.name() : spot.name().substring(0, space)).toLowerCase(Locale.ROOT);
        return "/cam spot " + (CamSpots.WORDS.contains(first) ? player.getName() + ":" : "") + spot.name();
    }
}
