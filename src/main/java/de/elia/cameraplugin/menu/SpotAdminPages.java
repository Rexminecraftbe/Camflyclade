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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import static de.elia.cameraplugin.menu.CamMenu.*;
import static de.elia.cameraplugin.menu.MenuDrafts.*;
import static de.elia.cameraplugin.menu.MenuItems.*;
import static de.elia.cameraplugin.menu.MenuSorts.*;

/**
 * The admin pages of {@code /cam menu} about the spots of everybody: who
 * has which, taking them down, and keeping players from spots.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class SpotAdminPages {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    SpotAdminPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The players who have spots - and those kept from something with spots
     * who have none -, online or not: under each how many he has, how many of
     * them are public or shared, and what keeps him. A click shows his spots.
     * Everybody is the beacon in the middle of the last row; a click on it
     * opens the restriction of everybody.
     */
    void openSpotOwners(Player player, int pageNumber) {
        SpotModeration moderation = plugin.getSpotModeration();
        if (!moderation.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-spots-title"));
        for (CamSpots.Owner owner : plugin.getSpots().owners()) {
            names.put(owner.id(), owner.name());
            counts.put(owner.id(), owner.spots());
        }
        for (Map.Entry<UUID, SpotBans.Ban> entry : moderation.restricted().entrySet()) {
            String name = entry.getValue().name();
            names.putIfAbsent(entry.getKey(), name == null ? entry.getKey().toString() : name);
        }
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            Player online = Bukkit.getPlayer(entry.getKey());
            if (online != null) {
                entry.setValue(online.getName());
            }
        }
        List<Map.Entry<UUID, String>> list = new ArrayList<>(names.entrySet());
        menu.adminPages.sortPlayers(player, list, Map.Entry::getValue, Map.Entry::getKey);
        List<Row> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : list) {
            UUID id = entry.getKey();
            String name = entry.getValue();
            entries.add(new Row(id, slot -> {
                List<String> lore = spotOwnerLines(id, counts.getOrDefault(id, 0));
                lore.addAll(lines(menu.text(player, "menu-admin-spots-actions")));
                menu.put(page, slot, playerItem(id, name, lore, moderation.banOf(id) != null),
                        click -> menu.later(() -> openSpotsOf(player, id, name, 0)));
            }));
        }
        Listed listed = menu.list(page, "spot-owners:" + menu.adminPages.playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        if (listed.empty()) {
            menu.put(page, 22, Material.GRAY_DYE, "menu-admin-spots-empty", NOTHING);
        }
        String everybody = messages.getMessage("menu-admin-everybody-name");
        boolean restricted = moderation.everybodyBan() != null;
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-spots-everybody-lore")));
        lore.addAll(restrictionLines(moderation.everybodyText()));
        // A click opens the page that changes or lifts a restriction that holds already.
        lore.addAll(lines(menu.text(player, restricted ? "menu-admin-spots-everybody-actions-change"
                : "menu-admin-spots-everybody-actions")));
        menu.put(page, SLOT_ADMIN_EVERYBODY, everybodyItem(lore, restricted), click -> menu.later(() -> {
            menu.spotDrafts.put(player.getUniqueId(), spotDraft(null, everybody));
            openSpotBan(player);
        }));
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> menu.adminPages.openAdmin(player)));
        menu.adminPages.playerSortButton(player, page, () -> openSpotOwners(player, 0));
        menu.paging(page, number, listed.pages(), n -> openSpotOwners(player, n));
        // It reads the files of the players offline, to sort them by their last flight: not every second.
        menu.show(player, page, EVERY_FIVE_SECONDS, () -> openSpotOwners(player, number));
    }

    /** How many spots he has, how many of them are public or shared, and what keeps him, as lines under him. */
    List<String> spotOwnerLines(UUID id, int count) {
        int open = 0;
        int sharedCount = 0;
        for (Share share : plugin.getSpots().sharesOf(id)) {
            if (share.isEveryone()) {
                open++;
            }
            if (!share.getPlayers().isEmpty() || !share.getPending().isEmpty()) {
                sharedCount++;
            }
        }
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-spots-count")
                .replace("{count}", String.valueOf(count)).replace("{public}", String.valueOf(open))
                .replace("{shared}", String.valueOf(sharedCount))));
        lore.addAll(restrictionLines(plugin.getSpotModeration().banText(id)));
        return lore;
    }

    /**
     * What a restriction keeps him, or everybody, from, for how long and
     * why, as lines - or that nothing does.
     *
     * @param text the restriction as its moderation writes it out, {@code null} for none
     */
    List<String> restrictionLines(String text) {
        return lines(text == null ? messages.getMessage("menu-admin-restrict-free")
                : messages.getMessage("menu-admin-restricted").replace("{ban}", text));
    }

    /** Everybody as an item, on the admin pages of the spots and the tours: a beacon, which shines while restricted. */
    ItemStack everybodyItem(List<String> lore, boolean glint) {
        return shine(item(Material.BEACON, messages.getMessage("menu-admin-player")
                .replace("{player}", messages.getMessage("menu-admin-everybody-name")), lore), glint);
    }

    /**
     * A player as an item, online or not: his head while he is online, a
     * skull while he is not. It shines while something keeps him - where the
     * game draws it, never on a head.
     */
    ItemStack playerItem(UUID id, String name, List<String> lore, boolean glint) {
        String label = messages.getMessage("menu-admin-player").replace("{player}", name);
        Player online = Bukkit.getPlayer(id);
        return shine(online != null ? head(online, label, lore) : item(Material.SKELETON_SKULL, label, lore), glint);
    }

    /**
     * The spots of one player, online or not, in the order the admin picks
     * - the newest first, by name, the most likes, the most views, the
     * nearest to him first: under each where it is, whether it is public,
     * shared or private, and its views. A click takes a public or shared one
     * down - private again -, right-click takes the admin there, shift-click
     * deletes one. The last row has the button of what keeps him from what
     * with spots, and the order.
     */
    void openSpotsOf(Player player, UUID owner, String name, int pageNumber) {
        SpotModeration moderation = plugin.getSpotModeration();
        if (!moderation.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        CamSpots spots = plugin.getSpots();
        SpotSort sort = menu.adminSpotSorts.getOrDefault(player.getUniqueId(), SpotSort.DATE);
        List<Spot> list = sorted(spots.spotsOf(owner).values(), sort, spot -> spot, spot -> "", spot -> {
            Share share = spots.shareOf(owner, spot.key());
            return share == null ? 0 : share.likes();
        }, spot -> spots.views(owner, spot), menu.spotPages.nearFrom(player));
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-spots-of-title").replace("{player}", name));
        // Filled in below, once the list knows which page of it is shown.
        int[] shown = new int[1];
        List<Row> entries = new ArrayList<>();
        for (Spot spot : list) {
            entries.add(new Row(spot.key(), slot -> putSpotOf(player, page, slot, owner, name, spot, shown)));
        }
        Listed listed = menu.list(page, "spots-of:" + owner + ":" + sort.key, entries, pageNumber);
        int number = listed.number();
        shown[0] = number;
        if (listed.empty()) {
            menu.put(page, 22, Material.GRAY_DYE, "menu-admin-spots-none", NOTHING);
        }
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-spot-owners", click -> menu.later(() -> openSpotOwners(player, 0)));
        boolean restricted = moderation.banOf(owner) != null;
        String key = restricted ? "menu-admin-spot-restrict-change" : "menu-admin-spot-restrict";
        List<String> lore = new ArrayList<>(restrictionLines(moderation.banText(owner)));
        lore.addAll(lines(messages.getMessage(key + "-lore")));
        menu.put(page, SLOT_NEW, shine(item(Material.IRON_BARS, messages.getMessage(key), lore), restricted),
                click -> menu.later(() -> {
                    menu.spotDrafts.put(player.getUniqueId(), spotDraft(owner, name));
                    openSpotBan(player);
                }));
        menu.newSpotPages.putSort(player, page, SLOT_ADMIN_SORT, sort.key, ADMIN_SPOT_SORTS, chosen -> {
            menu.adminSpotSorts.put(player.getUniqueId(), SpotSort.of(chosen));
            openSpotsOf(player, owner, name, 0);
        });
        menu.paging(page, number, listed.pages(), n -> openSpotsOf(player, owner, name, n));
        // The spots of a player offline come out of his file: not every second.
        menu.show(player, page, EVERY_FIVE_SECONDS, () -> openSpotsOf(player, owner, name, number));
    }

    /**
     * One spot of a player on the admin page: where it is, whether it is
     * public, shared or private, and its views. A click takes a public or
     * shared one down, right-click takes the admin there, shift-click deletes
     * it.
     *
     * @param shown which page of the list is shown, once it is known
     */
    void putSpotOf(Player player, Page page, int slot, UUID owner, String name, Spot spot, int[] shown) {
        SpotModeration moderation = plugin.getSpotModeration();
        CamSpots spots = plugin.getSpots();
        Share share = spots.shareOf(owner, spot.key());
        boolean open = share != null && share.isEveryone();
        boolean shared = share != null && (!share.getPlayers().isEmpty() || !share.getPending().isEmpty());
        List<String> lore = menu.spotPages.spotLore(owner, spot);
        if (open) {
            lore.addAll(lines(messages.getMessage("menu-spot-public-lore")
                    .replace("{likes}", String.valueOf(share.likes()))));
        }
        if (share != null && !share.getPlayers().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-shared-lore")
                    .replace("{players}", spots.whom(share))));
        }
        if (share != null && !share.getPending().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-offered-lore")
                    .replace("{players}", spots.offeredTo(share))));
        }
        if (!open && !shared) {
            lore.addAll(lines(messages.getMessage("menu-admin-spot-private")));
        }
        boolean options = settings.spots().options();
        lore.addAll(lines(menu.text(player, options ? "menu-admin-spot-actions-options"
                : open || shared ? "menu-admin-spot-actions-shared" : "menu-admin-spot-actions")));
        // Its own icon where it has one; else how it is shared.
        Material icon = CamSpots.iconOf(spot,
                open ? Material.GLOBE_BANNER_PATTERN : shared ? Material.MAP : Material.FILLED_MAP);
        if (options) {
            Consumer<ClickType> action = click -> {
                if (click.isShiftClick()) {
                    // Nothing: deleting is for the page of the options.
                    return;
                }
                if (click.isRightClick()) {
                    menu.later(() -> menu.newSpotPages.openSpotOptions(player, owner, name, spot.key(), true, false));
                } else if (click.isLeftClick()) {
                    menu.act(player, () -> moderation.teleport(player, name, spot));
                }
            };
            menu.put(page, slot, item(icon, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore),
                    action);
            menu.picks(player, page, slot, List.of(menu.pick("menu-pick-spot-go", action, ClickType.LEFT),
                    menu.pick("menu-pick-spot-options", action, ClickType.RIGHT)));
            return;
        }
        Consumer<ClickType> action = click -> {
            if (click.isShiftClick()) {
                menu.later(() -> {
                    moderation.delete(player, owner, name, spot);
                    openSpotsOf(player, owner, name, shown[0]);
                });
            } else if (click.isRightClick()) {
                menu.act(player, () -> moderation.teleport(player, name, spot));
            } else if (click.isLeftClick() && (open || shared)) {
                menu.later(() -> {
                    moderation.takeDown(player, owner, name, spot);
                    openSpotsOf(player, owner, name, shown[0]);
                });
            }
        };
        menu.put(page, slot, item(icon, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore), action);
        List<Pick> picks = new ArrayList<>();
        if (open || shared) {
            picks.add(menu.pick("menu-pick-spot-take-down", action, ClickType.LEFT));
        }
        picks.add(menu.pick("menu-pick-spot-go", action, ClickType.RIGHT));
        picks.add(menu.pick("menu-pick-spot-delete", action, ClickType.SHIFT_LEFT));
        menu.picks(player, page, slot, picks);
    }

    /**
     * A new restriction for him, or for everybody - nothing picked yet, for
     * good, without a reason - or the one that holds, to be changed: what it
     * keeps him from, the time it still has, rounded up to whole minutes, and
     * its reason.
     *
     * @param id the player, {@code null} for everybody
     */
    SpotBanDraft spotDraft(UUID id, String name) {
        SpotModeration moderation = plugin.getSpotModeration();
        SpotBans.Ban now = id == null ? moderation.everybodyBan() : moderation.banOf(id);
        SpotBanDraft draft = new SpotBanDraft(id, name, now != null);
        if (now != null) {
            for (SpotBans.Kind kind : SpotBans.Kind.values()) {
                draft.stages.put(kind, now.stage(kind));
            }
            keepTime(draft, now.forGood(), now.secondsLeft(System.currentTimeMillis()), now.reason());
        }
        return draft;
    }

    /**
     * What a player, or everybody, may not do with spots, before it is set or
     * changed: for his own spots, the public ones and those shared by name,
     * each on its button, step by step: nothing, no new ones, none at all -
     * not even to look at -; then the time and the reason, see
     * {@link #restrictTimeAndReason}, and the button that sets it.
     */
    void openSpotBan(Player player) {
        SpotBanDraft draft = menu.spotDrafts.get(player.getUniqueId());
        SpotModeration moderation = plugin.getSpotModeration();
        if (draft == null || !moderation.mayUse(player)) {
            openSpotOwners(player, 0);
            return;
        }
        Runnable back = () -> {
            menu.spotDrafts.remove(player.getUniqueId());
            if (draft.everybody()) {
                openSpotOwners(player, 0);
            } else {
                openSpotsOf(player, draft.id, draft.name, 0);
            }
        };
        Page page = menu.page(player, MEDIUM, messages.getMessage(draft.change
                ? "menu-admin-spot-restrict-change-title" : "menu-admin-spot-restrict-title")
                .replace("{player}", draft.name));
        String now = draft.everybody() ? moderation.everybodyText() : moderation.banText(draft.id);
        menu.put(page, 4, restrictTarget(draft, restrictionLines(now)), NOTHING);
        for (SpotTab tab : SpotTab.values()) {
            restrictStage(player, page, draft, tab);
        }
        restrictTimeAndReason(player, page, draft, "menu-admin-spot-restrict-reason-question",
                () -> openSpotBan(player));
        SpotBans.Stage own = draft.stage(SpotBans.Kind.PRIVATE);
        SpotBans.Stage open = draft.stage(SpotBans.Kind.PUBLIC);
        SpotBans.Stage shared = draft.stage(SpotBans.Kind.SHARED);
        restrictConfirm(page, draft, "menu-admin-spot-restrict", "menu-admin-spot-unrestrict",
                moderation.whatText(own, open, shared), () -> {
            if (draft.everybody()) {
                moderation.banEverybody(player, own, open, shared, draft.minutes, draft.reason);
            } else {
                moderation.ban(player, draft.id, draft.name, own, open, shared, draft.minutes, draft.reason);
            }
        }, () -> {
            if (draft.everybody()) {
                moderation.unbanEverybody(player);
            } else {
                moderation.unban(player, draft.id, draft.name);
            }
        }, back);
        menu.put(page, 27, Material.ARROW, draft.everybody() ? "menu-to-spot-owners" : "menu-to-spots-of",
                click -> menu.later(back));
        // A restriction being set up stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /**
     * Whom a restriction is about, at the top of its page: everybody as a
     * beacon, a player as on the lists. It shines while one holds.
     */
    ItemStack restrictTarget(RestrictDraft draft, List<String> lore) {
        return draft.everybody() ? everybodyItem(lore, draft.change) : playerItem(draft.id, draft.name, lore,
                draft.change);
    }

    /**
     * The part every page of a restriction has, under what it keeps him
     * from: for good or days, hours and minutes, as on the page of a ban, and
     * the reason, written in the chat.
     *
     * @param question the question for the reason
     * @param reopen   what shows the page anew
     */
    void restrictTimeAndReason(Player player, Page page, RestrictDraft draft, String question,
                                       Runnable reopen) {
        boolean forGood = draft.minutes == 0L;
        List<String> foreverLore = forGood ? lines(messages.getMessage("menu-state-current")) : List.of();
        menu.put(page, 19, shine(item(Material.BEDROCK, messages.getMessage("menu-admin-ban-forever"), foreverLore),
                forGood), click -> menu.later(() -> {
                    draft.minutes = 0L;
                    reopen.run();
                }));
        String time = menu.adminPages.banTime(draft.minutes);
        LongConsumer set = minutes -> draft.minutes = minutes;
        LongSupplier current = () -> draft.minutes;
        menu.adminPages.banUnit(player, page, 21, "menu-admin-ban-days", current, set, reopen, DAY, time);
        menu.adminPages.banUnit(player, page, 22, "menu-admin-ban-hours", current, set, reopen, HOUR, time);
        menu.adminPages.banUnit(player, page, 23, "menu-admin-ban-minutes", current, set, reopen, 1L, time);
        String reason = draft.reason.isEmpty() ? messages.getMessage("menu-admin-ban-no-reason") : draft.reason;
        List<String> reasonLore = new ArrayList<>(lines(messages.getMessage("menu-state").replace("{state}", reason)));
        reasonLore.addAll(lines(menu.text(player, "menu-admin-ban-reason-actions")));
        Consumer<ClickType> action = click -> {
            if (click.isRightClick()) {
                menu.later(() -> {
                    draft.reason = "";
                    reopen.run();
                });
            } else {
                menu.act(player, () -> menu.questions.ask(player, question, answer -> {
                    draft.reason = answer;
                    reopen.run();
                }, "{player}", draft.name));
            }
        };
        menu.put(page, 25, Material.WRITABLE_BOOK, messages.getMessage("menu-admin-ban-reason"), reasonLore, action);
        menu.picks(player, page, 25, List.of(menu.pick("menu-pick-reason-write", action, ClickType.LEFT),
                menu.pick("menu-pick-reason-none", action, ClickType.RIGHT)));
    }

    /**
     * The last buttons of the page of a restriction: the one that sets it,
     * with all it does written out - or, while it keeps him from nothing, a
     * grey one that says so -, and while one holds already the one that
     * lifts it. Both go back to the page before.
     *
     * @param prefix the texts of the button that sets it, such as {@code menu-admin-spot-restrict}
     * @param lifts  the texts of the button that lifts it, such as {@code menu-admin-spot-unrestrict}
     * @param what   what it keeps him from, as its messages put it
     */
    void restrictConfirm(Page page, RestrictDraft draft, String prefix, String lifts, String what,
                                 Runnable set, Runnable lift, Runnable back) {
        if (draft.any()) {
            String summary = what + " " + menu.adminPages.banTime(draft.minutes);
            if (!draft.reason.isEmpty()) {
                summary += messages.getMessage("ban-reason").replace("{reason}", draft.reason);
            }
            menu.put(page, 31, Material.REDSTONE_BLOCK, messages.getMessage(draft.change
                            ? prefix + "-change-confirm" : prefix + "-confirm"),
                    lines(messages.getMessage(prefix + "-confirm-lore").replace("{player}", draft.name)
                            .replace("{ban}", summary)),
                    click -> menu.later(() -> {
                        set.run();
                        back.run();
                    }));
        } else {
            menu.put(page, 31, Material.GRAY_DYE, prefix + "-nothing", NOTHING);
        }
        if (draft.change) {
            menu.put(page, 35, Material.MILK_BUCKET, messages.getMessage(lifts),
                    lines(messages.getMessage(draft.everybody() ? lifts + "-everybody-lore" : lifts + "-lore")
                            .replace("{player}", draft.name)),
                    click -> menu.later(() -> {
                        lift.run();
                        back.run();
                    }));
        }
    }

    /**
     * The button of one kind of spots on the page of a restriction, with the
     * icon of its tab: the steps it can keep him at, the one it does marked -
     * it shines while it keeps him at all -; a left-click goes one step
     * further, after the last back to nothing, a right-click one back.
     */
    void restrictStage(Player player, Page page, SpotBanDraft draft, SpotTab tab) {
        SpotBans.Kind kind = SpotBans.Kind.valueOf(tab.name());
        SpotBans.Stage now = draft.stage(kind);
        String what = "menu-admin-spot-restrict-" + kind.key;
        List<String> lore = new ArrayList<>(lines(messages.getMessage(what + "-lore")));
        for (SpotBans.Stage stage : SpotBans.Stage.values()) {
            String label = messages.getMessage(stage == SpotBans.Stage.FREE ? "menu-admin-restrict-allowed"
                    : "spot-restriction-" + kind.key + "-" + stage.key);
            lore.addAll(stateLine(label, stage == now));
        }
        lore.addAll(lines(menu.text(player, "menu-admin-restrict-steps")));
        int slot = RESTRICT_SLOTS[kind.ordinal()];
        menu.put(page, slot, shine(item(tab.icon, messages.getMessage(what), lore),
                now != SpotBans.Stage.FREE), click -> menu.later(() -> {
                    draft.stages.put(kind, now.next(click.isRightClick()));
                    openSpotBan(player);
                }));
        List<Pick> picks = new ArrayList<>();
        for (SpotBans.Stage stage : SpotBans.Stage.values()) {
            String label = messages.getMessage(stage == SpotBans.Stage.FREE ? "menu-admin-restrict-allowed"
                    : "spot-restriction-" + kind.key + "-" + stage.key);
            picks.add(new Pick(label, stage == now, () -> menu.later(() -> {
                draft.stages.put(kind, stage);
                openSpotBan(player);
            })));
        }
        menu.picks(player, page, slot, picks);
    }

    /** One of the steps under a button of a restriction: the one it is at marked. */
    List<String> stateLine(String label, boolean now) {
        return lines(messages.getMessage(now ? "menu-admin-restrict-stage-now" : "menu-admin-restrict-stage")
                .replace("{stage}", label));
    }
}
