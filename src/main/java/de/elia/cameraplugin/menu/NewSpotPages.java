package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import de.elia.cameraplugin.spot.SpotModeration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static de.elia.cameraplugin.menu.CamMenu.*;

/**
 * The pages of {@code /cam menu} that make a new spot, with its name, its
 * icon and who it is shared with, and the page of the icons.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class NewSpotPages {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    NewSpotPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * A new spot, with {@code viewpoints.create-page}: first what is asked of
     * every new spot - whether it may be saved here, whether a restriction
     * keeps him from it, whether he has room for one more -, then its name,
     * in the chat - on Bedrock with its windows in a line to type into -, and
     * then its page, see {@link #openNewSpot}, on Java and on Bedrock alike.
     */
    void startNewSpot(Player player) {
        CamSpots spots = plugin.getSpots();
        if (!spots.maySetHere(player) || !plugin.getSpotModeration().checkNew(player, SpotBans.Kind.PRIVATE)
                || !spots.hasRoom(player)) {
            return;
        }
        askNewName(player, "menu-spot-name");
    }

    /**
     * Asks for the name of a new spot: it is where he is when he answers,
     * looking the way he looks then - the page that comes next changes
     * nothing of that. A name that does not go - one he has already, one
     * too long - is asked for again right away, {@code menu-spot-name-again}:
     * the next line he writes is the next try, and he need not start anew.
     *
     * @param key the question: {@code menu-spot-name} the first time
     */
    void askNewName(Player player, String key) {
        menu.ask(player, key, typed -> {
            CamSpots spots = plugin.getSpots();
            // He may have left camera mode while he typed.
            if (!spots.maySetHere(player)) {
                return;
            }
            String name = spots.checkName(player, player.getUniqueId(), typed, null);
            if (name == null) {
                askNewName(player, "menu-spot-name-again");
                return;
            }
            menu.newSpots.put(player.getUniqueId(), new NewSpot(name, player.getLocation(), spots.lookOf(player),
                    spots.firstIcon()));
            openNewSpot(player);
        });
    }

    /**
     * The page of a new spot before it is saved: the spot on top as it will
     * be - where it is, what it shows, no views yet, and the likes and whom
     * it is offered to while it is to be shared -, under it its name to
     * change, its icon to pick and whom to share it with, see
     * {@link #openNewShare}, each saying how it is now, and in the last row
     * the buttons that call it off, on the left, and that save it, on the
     * right. Saved, his spots open, with it among them.
     */
    void openNewSpot(Player player) {
        UUID id = player.getUniqueId();
        NewSpot draft = menu.newSpots.get(id);
        if (draft == null || !settings.spots().enabled()) {
            menu.newSpots.remove(id);
            menu.spotPages.openSpots(player, SpotTab.PRIVATE, 0);
            return;
        }
        CamSpots spots = plugin.getSpots();
        Spot preview = draft.spot();
        Material icon = CamSpots.iconOf(preview, Material.FILLED_MAP);
        Page page = menu.page(player, SMALL, messages.getMessage("menu-new-spot-title").replace("{spot}", draft.name));
        // As its page of options will show it: where, what it shows, its views
        // - none yet - and, to be public, its likes, none yet either.
        List<String> previewLore = placeLore(preview);
        previewLore.addAll(menu.spotPages.viewsLore(0L));
        if (draft.open) {
            previewLore.addAll(lines(messages.getMessage("menu-spot-public-lore").replace("{likes}", "0")));
        }
        String invited = String.join(", ", draft.invited.values());
        if (!invited.isEmpty()) {
            previewLore.addAll(lines(messages.getMessage("menu-spot-offered-lore").replace("{players}", invited)));
        }
        menu.put(page, 4, item(icon, messages.getMessage("menu-spot").replace("{spot}", draft.name), previewLore),
                NOTHING);
        Runnable again = () -> openNewSpot(player);
        List<RowButton> row = new ArrayList<>();
        row.add(new RowButton(item(Material.NAME_TAG, messages.getMessage("menu-new-spot-rename"),
                lines(messages.getMessage("menu-new-spot-rename-lore").replace("{spot}", draft.name))),
                click -> menu.act(player, () -> menu.ask(player, "menu-spot-rename", typed -> {
                    NewSpot now = menu.newSpots.get(id);
                    if (now == null) {
                        return;
                    }
                    String name = spots.checkName(player, id, typed, null);
                    if (name != null) {
                        now.name = name;
                    }
                    openNewSpot(player);
                }, again, "{spot}", draft.name))));
        if (spots.icons().size() > 1) {
            // A frame, not the icon itself: that one is the spot on top.
            row.add(new RowButton(item(Material.ITEM_FRAME, messages.getMessage("menu-new-spot-icon")
                    .replace("{icon}", spots.iconName(draft.icon)), lines(messages.getMessage("menu-new-spot-icon-lore"))),
                    click -> menu.later(() -> openIcons(player, draft.name, draft.icon, chosen -> {
                        NewSpot now = menu.newSpots.get(id);
                        if (now != null) {
                            now.icon = chosen;
                        }
                        openNewSpot(player);
                    }, again))));
        }
        if (settings.spots().sharing()) {
            // The same page as for a saved spot; nothing is shared before it is saved.
            List<String> shareLore = new ArrayList<>(lines(messages.getMessage(draft.open
                    ? "menu-new-spot-share-public" : "menu-new-spot-share-private")));
            if (!invited.isEmpty()) {
                shareLore.addAll(lines(messages.getMessage("menu-new-spot-share-invited")
                        .replace("{players}", invited)));
            }
            shareLore.addAll(lines(menu.text(player, "menu-new-spot-share-lore")));
            row.add(new RowButton(shine(item(Material.GLOBE_BANNER_PATTERN, messages.getMessage("menu-new-spot-share"),
                    shareLore), draft.open || !invited.isEmpty()), click -> menu.later(() -> openNewShare(player, 0))));
        }
        putRow(page, row);
        menu.put(page, 18, Material.BARRIER, messages.getMessage("menu-new-spot-cancel"),
                lines(messages.getMessage("menu-new-spot-cancel-lore")), click -> menu.later(() -> {
                    menu.newSpots.remove(id);
                    messages.sendConfiguredMessage(player, "menu-answer-cancelled");
                    menu.spotPages.openSpots(player, SpotTab.PRIVATE, 0);
                }));
        List<String> confirmLore = new ArrayList<>(lines(messages.getMessage(draft.open
                ? "menu-new-spot-confirm-public-lore" : "menu-new-spot-confirm-lore").replace("{spot}", draft.name)));
        if (!invited.isEmpty()) {
            confirmLore.addAll(lines(messages.getMessage("menu-new-spot-confirm-invited-lore")
                    .replace("{players}", invited)));
        }
        menu.put(page, 26, Material.EMERALD, messages.getMessage("menu-new-spot-confirm"), confirmLore,
                click -> menu.later(() -> {
                    NewSpot now = menu.newSpots.remove(id);
                    if (now == null) {
                        menu.spotPages.openSpots(player, SpotTab.PRIVATE, 0);
                        return;
                    }
                    Spot spot = spots.save(player, now.name, now.where, now.look, now.icon);
                    if (spot == null) {
                        // He has been told why: the page stays, to put it right.
                        menu.newSpots.put(id, now);
                        openNewSpot(player);
                        return;
                    }
                    if (now.open) {
                        spots.makePublic(player, spot, true);
                    }
                    // Now they are offered it, and hear about it, as with a spot shared later.
                    for (UUID other : settings.spots().sharing() ? now.invited.keySet() : Set.<UUID>of()) {
                        Player online = Bukkit.getPlayer(other);
                        spots.shareWith(player, spot, online != null ? online : Bukkit.getOfflinePlayer(other));
                    }
                    menu.spotPages.openSpots(player, SpotTab.PRIVATE, 0);
                }));
        // A spot being set up stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /**
     * Whom a new spot is to be shared with, before it is saved: the same page
     * as for a saved one, see {@link SpotPages#sharePage} - the players online and
     * those he picked already. A click picks one or takes him back, the
     * beacon makes it public or private again; what keeps him from offering
     * spots or making them public is asked right away, as it is for a saved
     * one. Nothing is shared yet: once the spot is saved, it is made public
     * and offered to each of them, who hears about it then.
     */
    void openNewShare(Player player, int pageNumber) {
        UUID id = player.getUniqueId();
        NewSpot draft = menu.newSpots.get(id);
        if (draft == null || !settings.spots().sharing()) {
            openNewSpot(player);
            return;
        }
        SpotModeration moderation = plugin.getSpotModeration();
        Map<UUID, String> people = sharePeople(player);
        for (Map.Entry<UUID, String> entry : draft.invited.entrySet()) {
            people.putIfAbsent(entry.getKey(), entry.getValue());
        }
        menu.spotPages.sharePage(player, draft.name, "share-new:" + id, people,
                other -> draft.invited.containsKey(other) ? "invited" : "off",
                (other, name) -> {
                    if (draft.invited.remove(other) == null && moderation.checkNew(player, SpotBans.Kind.SHARED)) {
                        draft.invited.put(other, name);
                    }
                },
                draft.open, everybody -> {
                    if (!everybody || moderation.checkNew(player, SpotBans.Kind.PUBLIC)) {
                        draft.open = everybody;
                    }
                }, "menu-to-spot", () -> openNewSpot(player), number -> openNewShare(player, number), pageNumber);
    }

    /**
     * The icons a spot may have, {@link CamSpots#icons}, to pick one: each as
     * itself, under its name, the one it has now shining. A click picks it.
     *
     * @param now    the icon it has now, {@code null} for none of its own
     * @param choose what picking one does
     * @param back   what the way back does
     */
    void openIcons(Player player, String spot, String now, Consumer<String> choose, Runnable back) {
        CamSpots spots = plugin.getSpots();
        Page page = menu.page(player, LIST, messages.getMessage("menu-icons-title").replace("{spot}", spot));
        List<String> icons = spots.icons();
        for (int slot = 0; slot < icons.size() && slot < ENTRIES; slot++) {
            String key = icons.get(slot);
            boolean current = key.equals(now);
            menu.put(page, slot, shine(item(CamSpots.iconItem(key), messages.getMessage("menu-icon")
                            .replace("{icon}", spots.iconName(key)),
                    lines(messages.getMessage(current ? "menu-state-current" : "menu-icon-lore"))), current),
                    click -> menu.later(() -> choose.accept(key)));
        }
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-spot", click -> menu.later(back));
        menu.show(player, page);
    }

    /**
     * The options of one spot - a right-click on it -, for its owner, or on
     * the admin page of the spots for an admin: the spot on top, under it
     * what may be done with it. Its owner as {@code viewpoints.options} lets
     * him: rename it, give it another icon, share it or make it public, move
     * it to where his camera is - in camera mode only -, delete it. An admin
     * always: rename it, give it another icon, make it private again, delete
     * it. Deleting takes a second click, on a button of its own in the last
     * row, so that neither a slip nor a double click loses a spot.
     *
     * @param admin    whether an admin moderates it, from the admin page of the spots
     * @param deleting whether the second click that deletes it is waited for
     */
    void openSpotOptions(Player player, UUID owner, String ownerName, String key, boolean admin,
                                 boolean deleting) {
        CamSpots spots = plugin.getSpots();
        SpotModeration moderation = plugin.getSpotModeration();
        if (admin && !moderation.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        Runnable back = admin ? () -> menu.spotAdminPages.openSpotsOf(player, owner, ownerName, 0)
                : () -> menu.spotPages.openSpots(player, SpotTab.PRIVATE, 0);
        Spot spot = spots.spotsOf(owner).get(key);
        if (spot == null || !settings.spots().options()) {
            back.run();
            return;
        }
        Runnable again = () -> openSpotOptions(player, owner, ownerName, key, admin, false);
        Share share = spots.shareOf(owner, key);
        boolean shared = share != null
                && (share.isEveryone() || !share.getPlayers().isEmpty() || !share.getPending().isEmpty());
        Page page = menu.page(player, SMALL, messages.getMessage(admin ? "menu-spot-options-admin-title"
                : "menu-spot-options-title").replace("{spot}", spot.name()).replace("{player}", ownerName));
        List<String> lore = menu.spotPages.spotLore(owner, spot);
        lore.addAll(shareLore(share));
        Material icon = CamSpots.iconOf(spot, Material.FILLED_MAP);
        menu.put(page, 4, item(icon, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore), NOTHING);
        List<RowButton> row = new ArrayList<>();
        if (admin || settings.spots().optionRename()) {
            row.add(new RowButton(item(Material.NAME_TAG, messages.getMessage("menu-spot-options-rename"),
                    lines(messages.getMessage("menu-spot-options-rename-lore"))),
                    click -> menu.act(player, () -> menu.ask(player, "menu-spot-rename", typed -> {
                        Spot renamed = admin ? moderation.rename(player, owner, ownerName, spot, typed)
                                : spots.renameOwn(player, key, typed);
                        openSpotOptions(player, owner, ownerName, renamed != null ? renamed.key() : key, admin,
                                false);
                    }, again, "{spot}", spot.name()))));
        }
        if ((admin || settings.spots().optionIcon()) && spots.icons().size() > 1) {
            // A frame, not the icon itself: that one is the spot on top.
            row.add(new RowButton(item(Material.ITEM_FRAME, messages.getMessage("menu-spot-options-icon"),
                    lines(messages.getMessage("menu-spot-options-icon-lore").replace("{icon}",
                            spots.iconName(spot.icon() != null ? spot.icon()
                                    : icon.name().toLowerCase(Locale.ROOT))))),
                    click -> menu.later(() -> openIcons(player, spot.name(), spot.icon(), chosen -> {
                        if (admin) {
                            moderation.changeIcon(player, owner, ownerName, spot, chosen);
                        } else {
                            spots.changeOwnIcon(player, key, chosen);
                        }
                        again.run();
                    }, again))));
        }
        if (!admin && settings.spots().optionShare()) {
            row.add(new RowButton(item(Material.GLOBE_BANNER_PATTERN, messages.getMessage("menu-spot-options-share"),
                    lines(messages.getMessage("menu-spot-options-share-lore"))),
                    click -> menu.later(() -> menu.spotPages.openShare(player, key, 0))));
        }
        if (admin && shared) {
            row.add(new RowButton(item(Material.GRAY_DYE, messages.getMessage("menu-spot-options-take-down"),
                    lines(messages.getMessage("menu-spot-options-take-down-lore"))), click -> menu.later(() -> {
                        moderation.takeDown(player, owner, ownerName, spot);
                        again.run();
                    })));
        }
        if (!admin && settings.spots().optionMove()) {
            boolean inCam = plugin.isInCameraMode(player);
            row.add(new RowButton(item(Material.ENDER_PEARL, messages.getMessage("menu-spot-options-move"),
                    lines(messages.getMessage(inCam ? "menu-spot-options-move-lore"
                            : "menu-spot-options-move-cam-only"))), click -> menu.later(() -> {
                        spots.move(player, key);
                        again.run();
                    })));
        }
        if (admin || settings.spots().optionDelete()) {
            row.add(deleting
                    ? new RowButton(item(Material.BARRIER, messages.getMessage("menu-spot-options-delete-keep"),
                    lines(messages.getMessage("menu-spot-options-delete-keep-lore"))), click -> menu.later(again))
                    : new RowButton(item(Material.BARRIER, messages.getMessage("menu-spot-options-delete"),
                    lines(messages.getMessage("menu-spot-options-delete-lore"))), click -> menu.later(() ->
                    openSpotOptions(player, owner, ownerName, key, admin, true))));
            if (deleting) {
                menu.put(page, 22, shine(item(Material.LAVA_BUCKET, messages.getMessage("menu-spot-options-delete-confirm"),
                        lines(messages.getMessage("menu-spot-options-delete-confirm-lore")
                                .replace("{spot}", spot.name()))), true), click -> {
                    // The second click of a double click on the button above it is no second thought.
                    if (click == ClickType.DOUBLE_CLICK) {
                        return;
                    }
                    menu.later(() -> {
                        if (admin) {
                            moderation.delete(player, owner, ownerName, spot);
                        } else {
                            spots.delete(player, spot.name());
                        }
                        back.run();
                    });
                });
            }
        }
        if (row.isEmpty()) {
            menu.put(page, 13, Material.GRAY_DYE, "menu-spot-options-none", NOTHING);
        } else {
            putRow(page, row);
        }
        menu.put(page, 18, Material.ARROW, admin ? "menu-to-spots-of" : "menu-to-spots", click -> menu.later(back));
        // What he is doing with it stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /** Where a spot is and what it shows, as lines under it. */
    List<String> placeLore(Spot spot) {
        List<String> lore = new ArrayList<>(lines(place(messages.getMessage("menu-spot-lore"), spot)));
        String look = plugin.getSpots().describe(spot.look());
        if (!look.isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-look").replace("{look}", look)));
        }
        return lore;
    }

    /** Whether a spot is public, and whom it is shared with and offered to, as lines under it. */
    List<String> shareLore(Share share) {
        List<String> lore = new ArrayList<>();
        if (share == null) {
            return lore;
        }
        CamSpots spots = plugin.getSpots();
        if (share.isEveryone()) {
            lore.addAll(lines(messages.getMessage("menu-spot-public-lore")
                    .replace("{likes}", String.valueOf(share.likes()))));
        }
        if (!share.getPlayers().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-shared-lore").replace("{players}", spots.whom(share))));
        }
        if (!share.getPending().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-offered-lore")
                    .replace("{players}", spots.offeredTo(share))));
        }
        return lore;
    }

    /**
     * The button that sorts a list: the order it has now under its name, and
     * under it every order there is - the one it has now marked, the others
     * greyed out. A click takes the next order, a right-click the one before.
     *
     * @param now    the order it has now, as the language file names it after {@code menu-sort-}
     * @param orders every order it can have
     * @param choose what picking one does
     */
    void putSort(Player player, Page page, int slot, String now, List<String> orders,
                         Consumer<String> choose) {
        String name = messages.getMessage("menu-sort").replace("{sort}", messages.getMessage("menu-sort-" + now));
        List<String> lore = new ArrayList<>();
        for (String order : orders) {
            lore.addAll(lines(messages.getMessage(order.equals(now) ? "menu-sort-option-current" : "menu-sort-option")
                    .replace("{sort}", messages.getMessage("menu-sort-" + order))));
        }
        lore.addAll(lines(menu.text(player, "menu-sort-lore")));
        menu.put(page, slot, Material.HOPPER, name, lore, click -> menu.later(() -> {
            int at = Math.max(0, orders.indexOf(now));
            int step = click.isRightClick() ? orders.size() - 1 : 1;
            choose.accept(orders.get((at + step) % orders.size()));
        }));
        List<Pick> picks = new ArrayList<>();
        for (String order : orders) {
            picks.add(new Pick(messages.getMessage("menu-sort-" + order), order.equals(now),
                    () -> menu.later(() -> choose.accept(order))));
        }
        menu.picks(player, page, slot, picks);
    }
}
