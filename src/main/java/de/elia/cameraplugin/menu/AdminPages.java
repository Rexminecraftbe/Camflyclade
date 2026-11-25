package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.ban.BanList;
import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import static de.elia.cameraplugin.menu.CamMenu.*;
import static de.elia.cameraplugin.menu.MenuDrafts.*;
import static de.elia.cameraplugin.menu.MenuItems.*;
import static de.elia.cameraplugin.menu.MenuSorts.*;

/**
 * The admin pages of {@code /cam menu}: who is in camera mode
 * ({@code list}, {@code tp}, {@code stop}), the statistics of others and the
 * bans of {@code /cam ban}.
 *
 * <p>Part of {@link CamMenu}, which draws and shows the pages, keeps what
 * is being set up on them, and asks in the chat.</p>
 */
final class AdminPages {

    /**
     * How many days, hours or minutes a click with shift adds to a ban on its
     * page or takes away, where a click alone does one. Ten: the step between
     * a few minutes and an hour, a few days and a month. The section
     * {@code menu} had it as {@code ban-shift-step} once.
     */
    static final int BAN_SHIFT_STEP = 10;

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    AdminPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /** Whether there is anything on the admin page for him: one of its parts he has the right of. */
    boolean hasAdminPage(Player player) {
        return plugin.getCamList().mayUse(player) || viewsOthers(player) || managesPlayers(player)
                || plugin.getTrail().mayUse(player) || plugin.getSpotModeration().mayUse(player)
                || plugin.getTourModeration().mayUse(player);
    }

    boolean viewsOthers(Player player) {
        return plugin.getStats().showsAnything() && plugin.getStats().mayViewOthers(player);
    }

    /** Whether he may end the camera mode of others or ban them, the page of the players. */
    boolean managesPlayers(Player player) {
        return plugin.getStop().mayUse(player) || plugin.getBan().mayUse(player);
    }

    /**
     * The admin page, for what admins do with commands otherwise: who is in
     * camera mode, the players to stop and to ban, the statistics of others,
     * the flights of {@code /cam trail}, the spots of everybody and who may
     * lead tours and take part in them - each where
     * {@code menu.admin-buttons} puts it, only for whoever has its right and
     * while it is switched on.
     */
    void openAdmin(Player player) {
        if (!settings.menu().adminAccess().allowsMenu() || !hasAdminPage(player)) {
            menu.mainPage.openMain(player);
            return;
        }
        Page page = menu.page(player, MenuButton.SLOTS, messages.getMessage("menu-admin-title"));
        if (plugin.getCamList().mayUse(player)) {
            adminButton(page, AdminButton.LIST, "menu-admin-list", click -> menu.later(() -> openCamList(player, 0)));
        }
        if (managesPlayers(player)) {
            adminButton(page, AdminButton.PLAYERS, "menu-admin-players", click -> menu.later(() -> openPlayers(player, 0)));
        }
        if (viewsOthers(player)) {
            adminButton(page, AdminButton.STATS, "menu-admin-stats", click -> menu.later(() -> openStatsOf(player, 0)));
        }
        if (plugin.getTrail().mayUse(player)) {
            adminButton(page, AdminButton.TRAILS, "menu-admin-trails", click -> menu.later(() -> menu.trailPages.openTrails(player, 0)));
        }
        if (plugin.getSpotModeration().mayUse(player)) {
            adminButton(page, AdminButton.SPOTS, "menu-admin-spots", click -> menu.later(() -> menu.spotAdminPages.openSpotOwners(player, 0)));
        }
        if (plugin.getTourModeration().mayUse(player)) {
            adminButton(page, AdminButton.FOLLOW, "menu-admin-tours", click -> menu.later(() -> menu.followAdminPages.openTourPlayers(player, 0)));
        }
        adminButton(page, AdminButton.BACK, "menu-to-main", click -> menu.later(() -> menu.mainPage.openMain(player)));
        // Nothing on it changes by itself.
        menu.show(player, page);
    }

    /** One button of the admin page, with the item and in the slot {@code menu.admin-buttons} gives it. */
    void adminButton(Page page, AdminButton which, String key, Consumer<ClickType> action) {
        menu.put(page, settings.menu().adminSlot(which), labelled(settings.menu().adminIcon(which).create(plugin.getLogger()),
                messages.getMessage(key), lines(messages.getMessage(key + "-lore"))), action);
    }

    /**
     * Who is in camera mode right now, like {@code /cam list}: how long
     * already and how far from the body. Left-click teleports to the camera,
     * right-click to the body ({@code camplugin.teleport}).
     */
    void openCamList(Player player, int pageNumber) {
        if (!plugin.getCamList().mayUse(player)) {
            openAdmin(player);
            return;
        }
        boolean teleport = plugin.getListTeleport().mayUse(player);
        List<Player> cameras = cameraPlayersSorted();
        sortPlayers(player, cameras, Player::getName, Player::getUniqueId);
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-list-title"));
        List<Row> entries = new ArrayList<>();
        for (Player camera : cameras) {
            entries.add(new Row(camera.getUniqueId(), slot -> {
                List<String> lore = new ArrayList<>(camLines(camera));
                if (teleport) {
                    lore.addAll(lines(menu.text(player, "menu-admin-list-actions")));
                }
                String name = camera.getName();
                Consumer<ClickType> action = click -> {
                    if (click.isRightClick() && teleport) {
                        menu.act(player, () -> plugin.getListTeleport().command(player, new String[]{name, "body"}));
                    } else if (click.isLeftClick() && teleport) {
                        menu.act(player, () -> plugin.getListTeleport().command(player, new String[]{name, "camera"}));
                    }
                };
                menu.put(page, slot, head(camera, messages.getMessage("menu-admin-player").replace("{player}", name), lore),
                        teleport ? action : NOTHING);
                if (teleport) {
                    menu.picks(player, page, slot, List.of(menu.pick("menu-pick-tp-camera", action, ClickType.LEFT),
                            menu.pick("menu-pick-tp-body", action, ClickType.RIGHT)));
                }
            }));
        }
        Listed listed = menu.list(page, "cameras:" + playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        if (listed.empty()) {
            menu.put(page, 22, Material.GRAY_DYE, "menu-admin-list-empty", NOTHING);
        }
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> openAdmin(player)));
        playerSortButton(player, page, () -> openCamList(player, 0));
        menu.paging(page, number, listed.pages(), n -> openCamList(player, n));
        menu.show(player, page, EVERY_SECOND, () -> openCamList(player, number));
    }

    /** The players in camera mode, by name. */
    List<Player> cameraPlayersSorted() {
        List<Player> cameras = new ArrayList<>();
        for (UUID id : plugin.getCameraPlayers().ids()) {
            Player camera = Bukkit.getPlayer(id);
            if (camera != null) {
                cameras.add(camera);
            }
        }
        cameras.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return cameras;
    }

    /** How he has the lists of players sorted: by name, until he picks another order. */
    PlayerSort playerSort(Player admin) {
        return menu.playerSorts.getOrDefault(admin.getUniqueId(), PlayerSort.NAME);
    }

    /** The button that sorts a list of players on the admin pages: by name, by the last flight or the nearest first. */
    void playerSortButton(Player admin, Page page, Runnable reopen) {
        menu.newSpotPages.putSort(admin, page, SLOT_ADMIN_SORT, playerSort(admin).key, PlayerSort.KEYS, chosen -> {
            menu.playerSorts.put(admin.getUniqueId(), PlayerSort.of(chosen));
            reopen.run();
        });
    }

    /**
     * Sorts a list of players the way the admin has it, kept by name
     * otherwise. By the last flight, whoever is in camera mode comes first -
     * the one who started last at the top -, then the others by when their
     * last flight ended, as the statistics noted it - one too short to be
     * counted as well; whoever never flew, or only while the statistics were
     * switched off, comes last. The nearest first, whoever is online in his
     * world comes first, the closest to him at the top, see
     * {@link MenuSorts#distance(Player, Player)}; the others after them by name.
     */
    <T> void sortPlayers(Player admin, List<T> list, Function<T, String> name, Function<T, UUID> id) {
        list.sort(Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER));
        if (playerSort(admin) == PlayerSort.NEAR) {
            // Sorting is stable: whoever is as far - offline, in another world - stays by name.
            Map<UUID, Double> far = new HashMap<>();
            for (T entry : list) {
                far.put(id.apply(entry), distance(admin, Bukkit.getPlayer(id.apply(entry))));
            }
            list.sort(Comparator.comparingDouble((T entry) -> far.get(id.apply(entry))));
            return;
        }
        if (playerSort(admin) != PlayerSort.LAST_FLIGHT) {
            return;
        }
        Map<UUID, Long> ended = new HashMap<>();
        for (T entry : list) {
            UUID player = id.apply(entry);
            if (!plugin.getCameraPlayers().contains(player)) {
                ended.put(player, plugin.getPlayerStore().look(player).getLastFlightEnded());
            }
        }
        list.sort(Comparator.comparing((T entry) -> plugin.getCameraPlayers().contains(id.apply(entry))).reversed()
                .thenComparing(Comparator.comparingLong((T entry) -> {
                    CameraData data = plugin.getCameraPlayers().get(id.apply(entry));
                    return data != null ? data.getStartedAt() : ended.getOrDefault(id.apply(entry), 0L);
                }).reversed()));
    }

    /** How long a camera player has been in camera mode and how far from his body, as lines. */
    List<String> camLines(Player camera) {
        CameraData data = plugin.getCameraPlayers().get(camera.getUniqueId());
        if (data == null) {
            return List.of();
        }
        String time = CamTimeLimit.formatDuration(
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - data.getStartedAt()));
        Location body = data.getBody().getLocation();
        Location at = camera.getLocation();
        boolean sameWorld = body.getWorld().equals(at.getWorld());
        return lines(messages.getMessage(sameWorld ? "menu-admin-cam-lore" : "menu-admin-cam-lore-away")
                .replace("{time}", time)
                .replace("{distance}", sameWorld ? String.valueOf(Math.round(at.distance(body))) : ""));
    }

    /**
     * The statistics of the players online, like {@code /cam stats player}:
     * under each head, and in the chat on a click. A shift-click sets them
     * back to nothing, like {@code /cam stats reset <name>}, for whoever may.
     */
    void openStatsOf(Player player, int pageNumber) {
        if (!viewsOthers(player)) {
            openAdmin(player);
            return;
        }
        List<Player> online = onlineSorted(player);
        sortPlayers(player, online, Player::getName, Player::getUniqueId);
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-stats-title"));
        boolean resets = settings.statistics().enabled() && plugin.getStats().mayResetOthers(player);
        List<Row> entries = new ArrayList<>();
        for (Player other : online) {
            entries.add(new Row(other.getUniqueId(), slot -> {
                String name = other.getName();
                List<String> lore = menu.mainPage.statLines(other.getUniqueId(), name);
                lore.addAll(lines(menu.text(player, resets ? "menu-admin-stats-actions-reset"
                        : "menu-admin-stats-actions")));
                Consumer<ClickType> action = click -> {
                    if (click.isShiftClick() && resets) {
                        menu.act(player, () -> plugin.getStats().reset(player, name));
                    } else {
                        menu.act(player, () -> plugin.getStats().command(player, new String[]{"player", name}));
                    }
                };
                menu.put(page, slot, head(other, messages.getMessage("menu-admin-player").replace("{player}", name), lore),
                        action);
                if (resets) {
                    menu.picks(player, page, slot, List.of(menu.pick("menu-pick-stats-show", action, ClickType.LEFT),
                            menu.pick("menu-pick-stats-reset", action, ClickType.SHIFT_LEFT)));
                }
            }));
        }
        Listed listed = menu.list(page, "stats:" + playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> openAdmin(player)));
        playerSortButton(player, page, () -> openStatsOf(player, 0));
        menu.paging(page, number, listed.pages(), n -> openStatsOf(player, n));
        menu.show(player, page, EVERY_SECOND, () -> openStatsOf(player, number));
    }

    /**
     * The players, to end their camera mode and to ban them: the players
     * online, and those banned who are not. Under each whether he is in
     * camera mode and whether he is banned. A click opens what can be done to
     * him. Everybody is the beacon in the middle of the last row.
     */
    void openPlayers(Player player, int pageNumber) {
        if (!managesPlayers(player)) {
            openAdmin(player);
            return;
        }
        CamBan ban = plugin.getBan();
        Map<UUID, String> people = new LinkedHashMap<>();
        for (Player other : onlineSorted(player)) {
            people.put(other.getUniqueId(), other.getName());
        }
        if (ban.mayUse(player)) {
            for (String name : ban.bannedNames()) {
                OfflinePlayer banned = name == null ? null : CamStats.find(name);
                if (banned != null) {
                    people.putIfAbsent(banned.getUniqueId(), name);
                }
            }
        }
        List<Map.Entry<UUID, String>> list = new ArrayList<>(people.entrySet());
        sortPlayers(player, list, Map.Entry::getValue, Map.Entry::getKey);
        Page page = menu.page(player, LIST, messages.getMessage("menu-admin-players-title"));
        List<Row> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : list) {
            Target target = new Target(entry.getKey(), entry.getValue());
            entries.add(new Row(target.id(), slot -> putTarget(player, page, slot, target)));
        }
        Listed listed = menu.list(page, "players:" + playerSort(player).key, entries, pageNumber);
        int number = listed.number();
        putTarget(player, page, SLOT_ADMIN_EVERYBODY,
                Target.everybody(messages.getMessage("menu-admin-everybody-name")));
        menu.put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> menu.later(() -> openAdmin(player)));
        playerSortButton(player, page, () -> openPlayers(player, 0));
        menu.paging(page, number, listed.pages(), n -> openPlayers(player, n));
        // It looks the players banned up among all who ever played: not every second.
        menu.show(player, page, EVERY_FIVE_SECONDS, () -> openPlayers(player, number));
    }

    /**
     * One player, or everybody, on the page of the players: whether he is in
     * camera mode and whether he is banned; a click opens what can be done to
     * him.
     */
    void putTarget(Player player, Page page, int slot, Target target) {
        List<String> lore = new ArrayList<>(menu.followAdminPages.targetLines(player, target));
        lore.addAll(lines(menu.text(player, "menu-admin-players-actions")));
        menu.put(page, slot, menu.followAdminPages.targetItem(target, lore), click -> menu.later(() -> openTarget(player, target)));
    }

    /**
     * What can be done to one player, or to everybody: end his camera mode,
     * while he is in it, and ban him - or, while he is banned, change his ban
     * or lift it, both on the page of the ban. Its button shines while he is
     * banned.
     */
    void openTarget(Player player, Target target) {
        if (!managesPlayers(player)) {
            openAdmin(player);
            return;
        }
        CamBan ban = plugin.getBan();
        Page page = menu.page(player, SMALL, messages.getMessage("menu-admin-target-title")
                .replace("{player}", target.name()));
        menu.put(page, 13, menu.followAdminPages.targetItem(target, menu.followAdminPages.targetLines(player, target)), NOTHING);
        if (plugin.getStop().mayUse(player)) {
            boolean inCam = target.everybody() ? !plugin.getCameraPlayers().isEmpty() : target.inCam(plugin);
            if (inCam) {
                menu.put(page, 11, Material.BARRIER, target.everybody() ? "menu-admin-stop-all" : "menu-admin-stop",
                        click -> menu.later(() -> {
                            plugin.getStop().command(player,
                                    new String[]{target.everybody() ? "all" : target.name()});
                            openTarget(player, target);
                        }));
            } else {
                menu.put(page, 11, Material.GRAY_DYE, "menu-admin-stop-none", NOTHING);
            }
        }
        if (ban.mayUse(player)) {
            boolean banned = target.banned(ban);
            String key = banned ? "menu-admin-ban-change" : "menu-admin-ban";
            menu.put(page, 15, shine(item(Material.IRON_BARS, messages.getMessage(key),
                    lines(messages.getMessage(key + "-lore"))), banned), click -> menu.later(() -> {
                        menu.drafts.put(player.getUniqueId(), draft(target));
                        openBan(player);
                    }));
        }
        menu.put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-players", click -> menu.later(() -> openPlayers(player, 0)));
        menu.show(player, page, EVERY_SECOND, () -> openTarget(player, target));
    }

    /**
     * A new ban for him, for good and without a reason - or, while he is
     * banned, his ban as it stands, to be changed: the time it still has,
     * rounded up to whole minutes, and its reason.
     */
    BanDraft draft(Target target) {
        CamBan ban = plugin.getBan();
        BanList.Ban now = target.everybody() ? ban.everybodyBan() : ban.banOf(target.id());
        BanDraft draft = new BanDraft(target, now != null);
        if (now != null) {
            draft.minutes = now.forGood() ? 0L
                    : Math.max(1L, (now.secondsLeft(System.currentTimeMillis()) + 59L) / 60L);
            draft.reason = now.reason();
        }
        return draft;
    }

    /**
     * How long a ban lasts and why, before it is given or changed: for good,
     * or days, hours and minutes added and taken away on their buttons, and a
     * reason written in the chat if he likes; then the button that bans. A
     * ban that holds already keeps its reason: in place of the reason the
     * page has the button that lifts it.
     */
    void openBan(Player player) {
        BanDraft draft = menu.drafts.get(player.getUniqueId());
        CamBan ban = plugin.getBan();
        if (draft == null || !ban.mayUse(player)) {
            openPlayers(player, 0);
            return;
        }
        Target target = draft.target;
        Page page = menu.page(player, SMALL, messages.getMessage(draft.change
                ? "menu-admin-ban-change-title" : "menu-admin-ban-title").replace("{player}", target.name()));
        menu.put(page, 4, menu.followAdminPages.targetItem(target, menu.followAdminPages.targetLines(player, target)), NOTHING);
        boolean forGood = draft.minutes == 0L;
        List<String> foreverLore = forGood ? lines(messages.getMessage("menu-state-current")) : List.of();
        menu.put(page, 10, shine(item(Material.BEDROCK, messages.getMessage("menu-admin-ban-forever"), foreverLore), forGood),
                click -> menu.later(() -> {
                    draft.minutes = 0L;
                    openBan(player);
                }));
        String time = banTime(draft.minutes);
        LongConsumer set = minutes -> draft.minutes = minutes;
        Runnable reopen = () -> openBan(player);
        LongSupplier current = () -> draft.minutes;
        banUnit(player, page, 12, "menu-admin-ban-days", current, set, reopen, DAY, time);
        banUnit(player, page, 13, "menu-admin-ban-hours", current, set, reopen, HOUR, time);
        banUnit(player, page, 14, "menu-admin-ban-minutes", current, set, reopen, 1L, time);
        if (draft.change) {
            menu.put(page, 16, Material.MILK_BUCKET, messages.getMessage("menu-admin-unban"),
                    lines(messages.getMessage("menu-admin-unban-lore").replace("{player}", target.name())),
                    click -> menu.later(() -> {
                        menu.drafts.remove(player.getUniqueId());
                        ban.unban(player, new String[]{target.everybody() ? "all" : target.name()});
                        openTarget(player, target);
                    }));
        } else {
            String reason = draft.reason.isEmpty() ? messages.getMessage("menu-admin-ban-no-reason") : draft.reason;
            List<String> reasonLore = new ArrayList<>(lines(messages.getMessage("menu-state")
                    .replace("{state}", reason)));
            reasonLore.addAll(lines(menu.text(player, "menu-admin-ban-reason-actions")));
            Consumer<ClickType> action = click -> {
                if (click.isRightClick()) {
                    menu.later(() -> {
                        draft.reason = "";
                        openBan(player);
                    });
                } else {
                    menu.act(player, () -> menu.questions.ask(player, "menu-admin-ban-reason-question", answer -> {
                        draft.reason = answer;
                        openBan(player);
                    }, "{player}", target.name()));
                }
            };
            menu.put(page, 16, Material.WRITABLE_BOOK, messages.getMessage("menu-admin-ban-reason"), reasonLore, action);
            menu.picks(player, page, 16, List.of(menu.pick("menu-pick-reason-write", action, ClickType.LEFT),
                    menu.pick("menu-pick-reason-none", action, ClickType.RIGHT)));
        }
        String summary = time;
        if (!draft.reason.isEmpty()) {
            summary += messages.getMessage("ban-reason").replace("{reason}", draft.reason);
        }
        menu.put(page, 22, Material.REDSTONE_BLOCK,
                messages.getMessage(draft.change ? "menu-admin-ban-change-confirm" : "menu-admin-ban-confirm"),
                lines(messages.getMessage("menu-admin-ban-confirm-lore").replace("{player}", target.name())
                        .replace("{ban}", summary)),
                click -> menu.later(() -> {
                    menu.drafts.remove(player.getUniqueId());
                    ban.ban(player, target.everybody() ? "all" : target.name(), draft.minutes, draft.reason);
                    openTarget(player, target);
                }));
        menu.put(page, 18, Material.ARROW, "menu-to-target", click -> menu.later(() -> openTarget(player, target)));
        // A ban being set up stays as he leaves it, until he clicks.
        menu.show(player, page);
    }

    /**
     * One of the buttons of the time of a ban: how many days, hours or
     * minutes it has, and what that makes altogether. A click adds one, a
     * right-click takes one away, with shift {@link #BAN_SHIFT_STEP} of
     * them. Taking away stops short of nothing - nothing would be for good -,
     * adding at the longest ban the buttons give. On Bedrock, with its
     * windows, a window of its own that stays open, see {@link #banUnitWindow}.
     *
     * @param current how long the ban lasts now, in minutes, 0 for good
     * @param set     what takes a new length
     * @param reopen  what shows the page anew afterwards
     * @param unit    what one of them is, in minutes
     * @param time    how long it lasts altogether, as {@link #banTime} has it
     */
    void banUnit(Player player, Page page, int slot, String key, LongSupplier current, LongConsumer set,
                         Runnable reopen, long unit, String time) {
        long minutes = current.getAsLong();
        long amount = amount(minutes, unit);
        int shiftStep = BAN_SHIFT_STEP;
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-ban-time-lore")
                .replace("{time}", time)));
        lore.addAll(lines(menu.text(player, "menu-admin-ban-time-actions")
                .replace("{step}", String.valueOf(shiftStep))));
        Consumer<ClickType> action = click -> menu.later(() -> {
            long step = unit * (click.isShiftClick() ? shiftStep : 1);
            long next = click.isRightClick() ? minutes - step : minutes + step;
            if (click.isRightClick() ? next >= 1L : next <= MAX_BAN_MINUTES) {
                set.accept(next);
            }
            reopen.run();
        });
        menu.put(page, slot, item(Material.CLOCK, messages.getMessage(key).replace("{amount}", String.valueOf(amount)), lore),
                action);
        String step = String.valueOf(shiftStep);
        menu.picks(player, page, slot, List.of(menu.pick("menu-pick-ban-more", action, ClickType.LEFT),
                menu.pick("menu-pick-ban-less", action, ClickType.RIGHT),
                new Pick(messages.getMessage("menu-pick-ban-more-step").replace("{step}", step), false,
                        () -> action.accept(ClickType.SHIFT_LEFT)),
                new Pick(messages.getMessage("menu-pick-ban-less-step").replace("{step}", step), false,
                        () -> action.accept(ClickType.SHIFT_RIGHT))));
        page.windows.put(page.at(slot), () -> banUnitWindow(player, key, unit, current, set, reopen));
    }

    /**
     * One of the buttons of the time of a ban as a window of Bedrock: how
     * many days, hours or minutes, and what that makes altogether, with
     * {@code +1}, {@code -1} and as many at once as {@link #BAN_SHIFT_STEP}
     * says. It stays open after each - sent anew as it stands then - until
     * the way back, or closing it, leads back to the page. Taking away stops
     * at a minute, rather than doing nothing where it would reach nought - a
     * ban of nought is one for good -; a ban for good stays one, adding sets
     * the time.
     *
     * @param current how long the ban lasts now, in minutes, 0 for good
     * @param back    what shows the page anew
     * @return whether the window went out
     */
    boolean banUnitWindow(Player player, String key, long unit, LongSupplier current, LongConsumer set,
                                  Runnable back) {
        long minutes = current.getAsLong();
        String step = String.valueOf(BAN_SHIFT_STEP);
        long many = unit * BAN_SHIFT_STEP;
        long[] changes = {unit, -unit, many, -many};
        List<String> labels = List.of(ChatColor.stripColor(messages.getMessage("menu-pick-ban-more")),
                ChatColor.stripColor(messages.getMessage("menu-pick-ban-less")),
                ChatColor.stripColor(messages.getMessage("menu-pick-ban-more-step").replace("{step}", step)),
                ChatColor.stripColor(messages.getMessage("menu-pick-ban-less-step").replace("{step}", step)),
                ChatColor.stripColor(messages.getMessage("menu-pick-back")));
        String title = ChatColor.stripColor(messages.getMessage(key)
                .replace("{amount}", String.valueOf(amount(minutes, unit))));
        String content = messages.getMessage("menu-admin-ban-time-lore").replace("{time}", banTime(minutes));
        return plugin.getBedrock().forms().list(player, title, content, labels, index -> {
            if (index >= changes.length) {
                back.run();
                return;
            }
            long now = current.getAsLong();
            long change = changes[index];
            set.accept(change > 0 ? Math.min(MAX_BAN_MINUTES, now + change)
                    : now == 0L ? 0L : Math.max(1L, now + change));
            if (!banUnitWindow(player, key, unit, current, set, back)) {
                back.run();
            }
        }, back);
    }

    /** How long a ban of so many minutes lasts, the way the texts put it: "for 2h 0m 0s", or "for good" for 0. */
    String banTime(long minutes) {
        return minutes == 0L ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(minutes * 60L));
    }
}
