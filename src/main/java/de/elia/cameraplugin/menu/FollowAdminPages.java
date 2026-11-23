package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.tour.CamTour;
import de.elia.cameraplugin.tour.TourBans;
import de.elia.cameraplugin.tour.TourModeration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static de.elia.cameraplugin.menu.CamMenu.*;

/**
 * The admin pages of {@code /cam menu} about {@code /cam follow}: who leads
 * and follows, and keeping players from it.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class FollowAdminPages {

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    FollowAdminPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * The players online and those kept from tours who are not: under each
     * whether he shows others round or follows a tour, and what keeps him. A
     * click opens what keeps him, to set or change it. Everybody is the
     * beacon in the middle of the last row; a click on it switches tours on
     * or off for everybody.
     */
    void openTourPlayers(Player player, int pageNumber) {
        TourModeration moderation = plugin.getTourModeration();
        if (!moderation.mayUse(player)) {
            menu.adminPages.openAdmin(player);
            return;
        }
        Map<UUID, String> people = new LinkedHashMap<>();
        for (Player other : onlineSorted(player)) {
            people.put(other.getUniqueId(), other.getName());
        }
        for (Map.Entry<UUID, TourBans.Ban> entry : moderation.restricted().entrySet()) {
            String name = entry.getValue().name();
            people.putIfAbsent(entry.getKey(), name == null ? entry.getKey().toString() : name);
        }
        List<Map.Entry<UUID, String>> list = new ArrayList<>(people.entrySet());
        menu.adminPages.sortPlayers(player, list, Map.Entry::getValue, Map.Entry::getKey);
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-tours-title"));
        List<Row> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : list) {
            entries.add(new Row(entry.getKey(),
                    slot -> putTourPlayer(player, page, slot, entry.getKey(), entry.getValue())));
        }
        Listed listed = menu.list(page, "tour-players:" + menu.adminPages.playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        putTourPlayer(player, page, SLOT_ADMIN_EVERYBODY, null, messages.getMessage("menu-admin-everybody-name"));
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> menu.adminPages.openAdmin(player)));
        menu.adminPages.playerSortButton(player, page, () -> openTourPlayers(player, 0));
        menu.paging(page, number, listed.pages(), n -> openTourPlayers(player, n));
        // It reads the files of the players offline, to sort them by their last flight: not every second.
        menu.show(player, page, EVERY_FIVE_SECONDS, () -> openTourPlayers(player, number));
    }

    /**
     * One player, or everybody, on the admin page of the tours: whether he
     * shows others round or follows a tour, and what keeps him - for
     * everybody, whether tours are on. A click opens what keeps him, or
     * switches tours for everybody.
     *
     * @param id the player, {@code null} for everybody
     */
    void putTourPlayer(Player player, Page page, int slot, UUID id, String name) {
        TourModeration moderation = plugin.getTourModeration();
        List<String> lore = new ArrayList<>(id == null
                ? lines(messages.getMessage("menu-admin-tours-everybody-lore")) : tourLines(id));
        lore.addAll(id == null ? switchLines(moderation.offText())
                : menu.spotAdminPages.restrictionLines(moderation.banText(id)));
        // A click opens the page that changes or lifts what holds already.
        boolean kept = id == null ? moderation.everybodyBan() != null : moderation.banOf(id) != null;
        lore.addAll(lines(menu.text(player, id == null
                ? kept ? "menu-admin-tours-everybody-actions-off" : "menu-admin-tours-everybody-actions"
                : kept ? "menu-admin-tours-actions-change" : "menu-admin-tours-actions")));
        ItemStack item = id == null ? menu.spotAdminPages.everybodyItem(lore, moderation.everybodyBan() != null)
                : menu.spotAdminPages.playerItem(id, name, lore, moderation.banOf(id) != null);
        menu.put(page, slot, item, click -> menu.later(() -> {
            menu.tourDrafts.put(player.getUniqueId(), tourDraft(id, name));
            if (id == null) {
                openTourSwitch(player);
            } else {
                openTourBan(player);
            }
        }));
    }

    /** Whether he shows others round or follows a tour - or that he is not online -, as lines under him. */
    List<String> tourLines(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) {
            return lines(messages.getMessage("menu-admin-offline"));
        }
        CamTour tour = plugin.getTour();
        Player guide = tour.guideOf(online);
        int guests = tour.guestsOf(online).size();
        if (guide != null) {
            return lines(messages.getMessage("menu-admin-tours-following").replace("{player}", guide.getName()));
        }
        return lines(guests > 0
                ? messages.getMessage("menu-admin-tours-guiding").replace("{count}", String.valueOf(guests))
                : messages.getMessage("menu-admin-tours-none"));
    }

    /** Whether tours are on for everybody, or off - for how long still and why -, as lines. */
    List<String> switchLines(String off) {
        return lines(off == null ? messages.getMessage("menu-admin-tours-on")
                : messages.getMessage("menu-admin-tours-off").replace("{ban}", off));
    }

    /**
     * A new restriction of tours for him - nothing picked yet, for good,
     * without a reason - or the one that holds, to be changed; for everybody,
     * tours on, or off as they are.
     *
     * @param id the player, {@code null} for everybody
     */
    TourBanDraft tourDraft(UUID id, String name) {
        TourModeration moderation = plugin.getTourModeration();
        TourBans.Ban now = id == null ? moderation.everybodyBan() : moderation.banOf(id);
        TourBanDraft draft = new TourBanDraft(id, name, now != null);
        if (now != null) {
            for (TourBans.What what : TourBans.What.values()) {
                if (now.keeps(what)) {
                    draft.kept.add(what);
                }
            }
            keepTime(draft, now.forGood(), now.secondsLeft(System.currentTimeMillis()), now.reason());
        }
        return draft;
    }

    /**
     * What a player may not do with tours, before it is set or changed: lead
     * them - invite players, which starts one - and take part in them -
     * accept an invitation -, each switched on its button; then the time and
     * the reason, see {@link SpotAdminPages#restrictTimeAndReason}, and the button that
     * sets it.
     */
    void openTourBan(Player player) {
        TourBanDraft draft = menu.tourDrafts.get(player.getUniqueId());
        TourModeration moderation = plugin.getTourModeration();
        if (draft == null || !moderation.mayUse(player)) {
            openTourPlayers(player, 0);
            return;
        }
        Runnable back = () -> {
            menu.tourDrafts.remove(player.getUniqueId());
            openTourPlayers(player, 0);
        };
        Page page = menu.page(player, MEDIUM, messages.getMessage(draft.change
                ? "menu-admin-tour-restrict-change-title" : "menu-admin-tour-restrict-title")
                .replace("{player}", draft.name));
        menu.put(page, 4, menu.spotAdminPages.restrictTarget(draft, menu.spotAdminPages.restrictionLines(moderation.banText(draft.id))), NOTHING);
        restrictToggle(player, page, draft, TourBans.What.LEAD, 12, Material.LEAD);
        restrictToggle(player, page, draft, TourBans.What.JOIN, 14, Material.SADDLE);
        menu.spotAdminPages.restrictTimeAndReason(player, page, draft, "menu-admin-tour-restrict-reason-question",
                () -> openTourBan(player));
        boolean lead = draft.kept.contains(TourBans.What.LEAD);
        boolean join = draft.kept.contains(TourBans.What.JOIN);
        menu.spotAdminPages.restrictConfirm(page, draft, "menu-admin-tour-restrict", "menu-admin-tour-unrestrict",
                moderation.whatText(lead, join),
                () -> moderation.ban(player, draft.id, draft.name, lead, join, draft.minutes, draft.reason),
                () -> moderation.unban(player, draft.id, draft.name), back);
        menu.put(page, 27, Material.ARROW, "menu-to-tour-players", click -> menu.later(back));
        // A restriction being set up stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /**
     * Tours for everybody, on or off, before it is set or changed: one
     * button switches between the two - off, nobody leads tours or takes part
     * in them but whoever has {@value TourModeration#BYPASS} -; then for how
     * long and why, see {@link SpotAdminPages#restrictTimeAndReason}, and the button that
     * switches them off. While they are off, the last row has the button that
     * switches them on again.
     */
    void openTourSwitch(Player player) {
        TourBanDraft draft = menu.tourDrafts.get(player.getUniqueId());
        TourModeration moderation = plugin.getTourModeration();
        if (draft == null || !draft.everybody() || !moderation.mayUse(player)) {
            openTourPlayers(player, 0);
            return;
        }
        Runnable back = () -> {
            menu.tourDrafts.remove(player.getUniqueId());
            openTourPlayers(player, 0);
        };
        Page page = menu.page(player, MEDIUM, messages.getMessage(draft.change
                ? "menu-admin-tour-off-change-title" : "menu-admin-tour-off-title"));
        menu.put(page, 4, menu.spotAdminPages.everybodyItem(switchLines(moderation.offText()), draft.change), NOTHING);
        boolean off = draft.any();
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-tour-switch-lore")));
        lore.addAll(menu.spotAdminPages.stateLine(messages.getMessage("menu-admin-tour-switch-on"), !off));
        lore.addAll(menu.spotAdminPages.stateLine(messages.getMessage("menu-admin-tour-switch-off"), off));
        lore.addAll(lines(menu.text(player, "menu-admin-restrict-switch")));
        menu.put(page, 13, shine(item(Material.SADDLE, messages.getMessage("menu-admin-tour-switch"), lore), off),
                click -> menu.later(() -> {
                    if (off) {
                        draft.kept.clear();
                    } else {
                        draft.kept.addAll(EnumSet.allOf(TourBans.What.class));
                    }
                    openTourSwitch(player);
                }));
        menu.spotAdminPages.restrictTimeAndReason(player, page, draft, "menu-admin-tour-off-reason-question",
                () -> openTourSwitch(player));
        menu.spotAdminPages.restrictConfirm(page, draft, "menu-admin-tour-off", "menu-admin-tour-on",
                messages.getMessage("menu-admin-tour-switch-off"),
                () -> moderation.switchOff(player, draft.minutes, draft.reason), () -> moderation.switchOn(player),
                back);
        menu.put(page, 27, Material.ARROW, "menu-to-tour-players", click -> menu.later(back));
        // A restriction being set up stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /**
     * One of the buttons of what a restriction of tours keeps him from:
     * allowed or not, the one it is marked - it shines while not -, and a
     * click that switches it.
     */
    void restrictToggle(Player player, Page page, TourBanDraft draft, TourBans.What what, int slot,
                                Material icon) {
        boolean kept = draft.kept.contains(what);
        String key = "menu-admin-tour-restrict-" + what.key;
        List<String> lore = new ArrayList<>(lines(messages.getMessage(key + "-lore")));
        lore.addAll(menu.spotAdminPages.stateLine(messages.getMessage("menu-admin-restrict-allowed"), !kept));
        lore.addAll(menu.spotAdminPages.stateLine(messages.getMessage("tour-restriction-" + what.key), kept));
        lore.addAll(lines(menu.text(player, "menu-admin-restrict-switch")));
        menu.put(page, slot, shine(item(icon, messages.getMessage(key), lore), kept), click -> menu.later(() -> {
            if (!draft.kept.remove(what)) {
                draft.kept.add(what);
            }
            openTourBan(player);
        }));
    }

    /** Whether he is in camera mode and whether he is banned, as lines under him. */
    List<String> targetLines(Player viewer, Target target) {
        List<String> lore = new ArrayList<>();
        if (target.everybody()) {
            lore.addAll(lines(messages.getMessage("menu-admin-everybody-cam")
                    .replace("{count}", String.valueOf(menu.adminPages.cameraPlayersSorted().size()))));
        } else if (Bukkit.getPlayer(target.id()) != null) {
            lore.addAll(target.inCam(plugin) ? menu.adminPages.camLines(Bukkit.getPlayer(target.id()))
                    : lines(messages.getMessage("menu-admin-not-in-cam")));
        } else {
            lore.addAll(lines(messages.getMessage("menu-admin-offline")));
        }
        CamBan ban = plugin.getBan();
        if (ban.mayUse(viewer)) {
            String text = target.everybody() ? ban.everybodyBanText() : ban.banText(target.id());
            lore.addAll(lines(text == null ? messages.getMessage("menu-admin-not-banned")
                    : messages.getMessage("menu-admin-banned").replace("{ban}", text)));
        }
        return lore;
    }

    /**
     * A player, or everybody, as an item: his head while he is online. It
     * shines while he is banned - where the game draws it: on the beacon of
     * everybody, never on a head.
     */
    ItemStack targetItem(Target target, List<String> lore) {
        String label = messages.getMessage("menu-admin-player").replace("{player}", target.name());
        Player online = target.everybody() ? null : Bukkit.getPlayer(target.id());
        ItemStack item = target.everybody() ? item(Material.BEACON, label, lore)
                : online != null ? head(online, label, lore) : item(Material.SKELETON_SKULL, label, lore);
        return shine(item, target.banned(plugin.getBan()));
    }
}
