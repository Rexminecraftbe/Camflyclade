package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.ban.BanList;
import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.sky.CamSky;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import de.elia.cameraplugin.trail.CamTrail;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@code /cam menu}: what camera mode offers, in a window to click instead of
 * commands to type - starting and ending it, {@code back}, {@code resume},
 * the spots of {@code /cam spot} with sharing them and visiting those of
 * others, {@code photo}, {@code time}, {@code weather}, {@code nightvision}
 * and the own {@code stats}; and for admins a page of their own: who is in
 * camera mode ({@code list}, {@code tp}, {@code stop}), the statistics of
 * others, the bans and the flights of {@code /cam trail}.
 *
 * <p>Each button does what its command does, with the same checks, rights
 * and messages; what is switched off in the config file is left out, and so
 * is what he has no right to. Where the buttons of the main page stand and
 * what they show is {@code menu.buttons}; whether the menu is there at all,
 * and whether the commands are, {@code menu.access} and
 * {@code menu.admin-access}.</p>
 *
 * <p>What cannot be clicked - the name of a new spot, why a ban is given -
 * the menu asks for in the chat: it closes, and the next line he writes is
 * the answer, which nobody else reads.</p>
 *
 * <p>The window belongs to no block and holds no items of anybody: every
 * click in it is turned away and only read for the button it hit, so nothing
 * gets in or out - in camera mode as well, where every other window stays
 * shut.</p>
 */
public final class CamMenu implements Listener {

    /** The sizes of the windows: three rows for the main page and the small ones, six for the lists. */
    private static final int SMALL = 27;
    private static final int LIST = 54;

    /** The slots of a list page that hold entries; the last row holds the buttons to go on. */
    private static final int ENTRIES = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_NEW = 49;
    private static final int SLOT_NEXT = 50;
    /** The button back to the main page on the small pages. */
    private static final int SLOT_SMALL_BACK = 22;

    /** How long the menu waits for an answer in the chat, in milliseconds. */
    private static final long ANSWER_WAIT = 60_000L;

    /** The word that calls off an answer in the chat. */
    private static final String CANCEL = "cancel";

    /** A day and an hour in minutes, for the buttons of the time of a ban. */
    private static final long DAY = 24L * 60L;
    private static final long HOUR = 60L;

    /** The longest ban the buttons of the time add up to, in minutes: 999 days. */
    private static final long MAX_BAN_MINUTES = 999L * DAY;


    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** Who is asked for something in the chat, until when and what to do with it. Read from the chat thread. */
    private final Map<UUID, Question> questions = new ConcurrentHashMap<>();

    /** A question in the chat: until when the answer counts, and what it does - on the main thread. */
    private record Question(long until, Consumer<String> answer) {
    }

    /** The bans being set up on their page, by the id of the admin. */
    private final Map<UUID, BanDraft> drafts = new HashMap<>();

    /** Whom the page of a player is about: one player, by his id and name, or everybody. */
    private record Target(UUID id, String name) {

        static Target everybody(String name) {
            return new Target(null, name);
        }

        boolean everybody() {
            return id == null;
        }

        boolean inCam(CameraPlugin plugin) {
            return id != null && plugin.getCameraPlayers().contains(id);
        }

        boolean banned(CamBan ban) {
            return id == null ? ban.everybodyBanned() : ban.isBanned(id);
        }
    }

    /**
     * A ban being set up on its page: whom, for how long and why - a new one,
     * or the one he has already, to be changed.
     */
    private static final class BanDraft {
        final Target target;
        /** Whether he is banned already, and the page changes his ban. */
        final boolean change;
        /** How long, in minutes; 0 for good. */
        long minutes;
        String reason = "";

        BanDraft(Target target, boolean change) {
            this.target = target;
            this.change = change;
        }
    }

    /** One window of the menu, and what each of its slots does when clicked. */
    public static final class Page implements InventoryHolder {
        private final Map<Integer, Consumer<ClickType>> buttons = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public CamMenu(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /** Whether this window is one of the menu, which camera mode lets open. */
    public static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Page;
    }

    /** Whether {@code /cam menu} opens. */
    public boolean isOpenable() {
        return settings.getMenuAccess().allowsMenu();
    }

    /** {@code /cam menu}: the main page. */
    public void command(Player player) {
        if (!isOpenable()) {
            messages.sendConfiguredMessage(player, "menu-off");
            return;
        }
        openMain(player);
    }

    // ------------------------------------------------------------------ pages

    /**
     * The main page: each button where {@code menu.buttons} puts it, showing
     * the item it names. Photo mode, the time, the weather and the night
     * vision say under their name how they stand now.
     */
    private void openMain(Player player) {
        Page page = new Page();
        boolean inCam = plugin.isInCameraMode(player);
        Inventory inventory = create(page, MenuButton.SLOTS, messages.getMessage("menu-title"));
        if (settings.isNightVisionPlayerChoice()) {
            button(page, MenuButton.NIGHT_VISION, "menu-nightvision", nightVisionState(player), false,
                    click -> later(() -> openNightVision(player)));
        }
        if (settings.isBackCommandEnabled()) {
            button(page, MenuButton.BACK, "menu-back", null, false,
                    click -> act(player, () -> plugin.getBack().command(player)));
        }
        if (settings.isResumeEnabled()) {
            button(page, MenuButton.RESUME, "menu-resume", null, false,
                    click -> act(player, () -> plugin.getResume().command(player)));
        }
        if (settings.isViewpointsEnabled()) {
            button(page, MenuButton.SPOTS, "menu-spots", null, false, click -> later(() -> openSpots(player, 0)));
        }
        if (settings.isPhotoModeEnabled()) {
            boolean photo = plugin.getPhotoMode().isActive(player);
            button(page, MenuButton.PHOTO, "menu-photo",
                    messages.getMessage(photo ? "menu-state-on" : "menu-state-off"), photo,
                    click -> act(player, () -> plugin.getPhotoMode().command(player)));
        }
        if (settings.isCamTimeEnabled()) {
            button(page, MenuButton.TIME, "menu-time", timeState(player), false,
                    click -> later(() -> openTime(player)));
        }
        if (settings.isCamWeatherEnabled()) {
            button(page, MenuButton.WEATHER, "menu-weather", weatherState(player), false,
                    click -> later(() -> openWeather(player)));
        }
        button(page, inCam ? MenuButton.END : MenuButton.START, inCam ? "menu-end" : "menu-start", null, false,
                click -> act(player, () -> plugin.getStarter().toggle(player)));
        CamStats stats = plugin.getStats();
        if (stats.showsAnything()) {
            List<String> lore = statLines(player.getUniqueId(), player.getName());
            lore.addAll(lines(messages.getMessage(settings.isStatsEnabled() ? "menu-stats-actions-reset"
                    : "menu-stats-actions")));
            put(page, settings.getMenuSlot(MenuButton.STATS), labelled(
                    settings.getMenuIcon(MenuButton.STATS).create(plugin.getLogger()),
                    messages.getMessage("menu-stats"), lore), click -> {
                        if (click.isShiftClick() && settings.isStatsEnabled()) {
                            act(player, () -> stats.reset(player));
                        } else {
                            act(player, () -> stats.command(player, new String[0]));
                        }
                    });
        }
        if (settings.getAdminAccess().allowsMenu() && hasAdminPage(player)) {
            button(page, MenuButton.ADMIN, "menu-admin", null, false, click -> later(() -> openAdmin(player)));
        }
        player.openInventory(inventory);
    }

    /**
     * The statistics of a player as lines under a button, without their
     * header: the name stands on the button already.
     */
    private List<String> statLines(UUID id, String name) {
        List<String> lore = new ArrayList<>();
        for (CamStats.Line line : plugin.getStats().lines(id, name)) {
            String text = line.key().equals("stats-header") ? null : plugin.getStats().text(line);
            if (text != null && !text.isEmpty()) {
                lore.add(text);
            }
        }
        return lore;
    }

    /**
     * One button of the main page, with the item and in the slot
     * {@code menu.buttons} gives it: its name, the text of the same name with
     * {@code -lore} after it, and how it stands now, if it says.
     *
     * @param state how it stands now, for {@code menu-state}, or {@code null}
     * @param glint whether it shines, for something switched on
     */
    private void button(Page page, MenuButton which, String key, String state, boolean glint,
                        Consumer<ClickType> action) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage(key + "-lore")));
        if (state != null) {
            lore.addAll(lines(messages.getMessage("menu-state").replace("{state}", state)));
        }
        ItemStack item = labelled(settings.getMenuIcon(which).create(plugin.getLogger()),
                messages.getMessage(key), lore);
        put(page, settings.getMenuSlot(which), shine(item, glint), action);
    }

    /** How his night vision stands: the name of the mode that holds for him. */
    private String nightVisionState(Player player) {
        return messages.getMessage("menu-nightvision-" + nightVisionWord(plugin.getNightVision().modeFor(player)));
    }

    /** The word of {@code /cam nightvision} for a mode. */
    private static String nightVisionWord(NightVisionMode mode) {
        return switch (mode) {
            case ALWAYS -> "on";
            case OFF -> "off";
            case DARK -> "dark";
        };
    }

    /** How his time stands: the name of his own, its ticks, or the time of the world. */
    private String timeState(Player player) {
        Long ticks = plugin.getSky().ownTime(player);
        if (ticks == null) {
            return messages.getMessage("menu-state-world-time");
        }
        String word = CamSky.timeWord(ticks);
        return word != null ? messages.getMessage("menu-time-" + word)
                : messages.getMessage("menu-state-ticks").replace("{ticks}", String.valueOf(ticks));
    }

    /** How his weather stands: clear, rain, or the weather of the world. */
    private String weatherState(Player player) {
        WeatherType weather = plugin.getSky().ownWeather(player);
        if (weather == null) {
            return messages.getMessage("menu-state-world-weather");
        }
        return messages.getMessage(weather == WeatherType.CLEAR ? "menu-weather-clear" : "menu-weather-rain");
    }

    /**
     * His own spots and then those shared with him, a page of
     * {@value #ENTRIES} at a time. Left-click jumps to one; on his own,
     * right-click shares it and a shift-click deletes it; on a shared one,
     * right-click visits it.
     */
    private void openSpots(Player player, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        List<Spot> own = new ArrayList<>(spots.own(player));
        List<Share> shared = spots.sharedWith(player);
        int total = own.size() + shared.size();
        int pages = Math.max(1, (total + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-spots-title"));
        for (int slot = 0; slot < ENTRIES; slot++) {
            int index = number * ENTRIES + slot;
            if (index < own.size()) {
                putOwnSpot(player, page, slot, own.get(index), number);
            } else if (index < total) {
                putSharedSpot(player, page, slot, shared.get(index - own.size()));
            }
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        put(page, SLOT_NEW, Material.WRITABLE_BOOK, "menu-new-spot", click -> act(player, () -> askName(player)));
        paging(page, number, pages, n -> openSpots(player, n));
        player.openInventory(inventory);
    }

    private void putOwnSpot(Player player, Page page, int slot, Spot spot, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = new ArrayList<>(lines(place(messages.getMessage("menu-spot-lore"), spot)));
        Share share = settings.isViewpointsSharing() ? spots.shareOf(player, spot) : null;
        if (share != null) {
            lore.addAll(lines(messages.getMessage("menu-spot-shared-lore").replace("{players}", spots.whom(share))));
        }
        lore.addAll(lines(messages.getMessage(settings.isViewpointsSharing() ? "menu-spot-actions-share"
                : "menu-spot-actions")));
        put(page, slot, Material.FILLED_MAP, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore,
                click -> {
                    if (click.isShiftClick()) {
                        later(() -> {
                            spots.delete(player, spot.name());
                            openSpots(player, pageNumber);
                        });
                    } else if (click.isRightClick() && settings.isViewpointsSharing()) {
                        later(() -> openShare(player, spot.key(), 0));
                    } else if (click.isLeftClick()) {
                        act(player, () -> spots.jump(player, spot.name()));
                    }
                });
    }

    private void putSharedSpot(Player player, Page page, int slot, Share share) {
        List<String> lore = new ArrayList<>(lines(place(messages.getMessage("menu-spot-lore"), share.getSpot())));
        lore.addAll(lines(messages.getMessage(settings.isVisitorCameraEnabled() ? "menu-shared-spot-actions-visit"
                : "menu-shared-spot-actions")));
        String name = messages.getMessage("menu-shared-spot").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        put(page, slot, Material.MAP, name, lore, click -> {
            if (click.isRightClick() && settings.isVisitorCameraEnabled()) {
                act(player, () -> plugin.getVisit().visit(player, share));
            } else if (click.isLeftClick()) {
                act(player, () -> plugin.getSpots().jump(player, share.label()));
            }
        });
    }

    /**
     * Whom one of his spots is shared with: everybody, then the players
     * online and those it is shared with who are not, a page of
     * {@value #ENTRIES} at a time. A click shares it, or takes it back.
     */
    private void openShare(Player player, String key, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        Spot spot = null;
        for (Spot own : spots.own(player)) {
            if (own.key().equals(key)) {
                spot = own;
            }
        }
        if (spot == null || !settings.isViewpointsSharing()) {
            openSpots(player, 0);
            return;
        }
        Spot target = spot;
        Share share = spots.shareOf(player, spot);
        Map<UUID, String> people = new LinkedHashMap<>();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (Player other : online) {
            if (!other.equals(player) && player.canSee(other)) {
                people.put(other.getUniqueId(), other.getName());
            }
        }
        if (share != null) {
            for (Map.Entry<UUID, String> entry : share.getPlayers().entrySet()) {
                people.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        List<Map.Entry<UUID, String>> list = new ArrayList<>(people.entrySet());
        // The first slot of the first page is for everybody.
        int total = list.size() + 1;
        int pages = Math.max(1, (total + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST,
                messages.getMessage("menu-share-title").replace("{spot}", spot.name()));
        for (int slot = 0; slot < ENTRIES; slot++) {
            int index = number * ENTRIES + slot;
            if (index == 0) {
                boolean everybody = share != null && share.isEveryone();
                put(page, slot, Material.BEACON, messages.getMessage("menu-share-everybody"),
                        lines(messages.getMessage(everybody ? "menu-share-on-lore" : "menu-share-off-lore")),
                        click -> later(() -> {
                            spots.shareAll(player, target, !everybody);
                            openShare(player, key, number);
                        }));
            } else if (index < total) {
                Map.Entry<UUID, String> entry = list.get(index - 1);
                boolean with = share != null && share.getPlayers().containsKey(entry.getKey());
                ItemStack head = item(Material.PLAYER_HEAD,
                        messages.getMessage(with ? "menu-share-player-on" : "menu-share-player")
                                .replace("{player}", entry.getValue()),
                        lines(messages.getMessage(with ? "menu-share-on-lore" : "menu-share-off-lore")));
                Player other = Bukkit.getPlayer(entry.getKey());
                if (other != null && head.getItemMeta() instanceof SkullMeta skull) {
                    // Only of a player online: one offline might have to be
                    // asked of Mojang for his skin.
                    skull.setOwningPlayer(other);
                    head.setItemMeta(skull);
                }
                put(page, slot, head, click -> later(() -> {
                    if (with) {
                        spots.unshareWith(player, target, entry.getKey(), entry.getValue());
                    } else {
                        OfflinePlayer whom = other != null ? other : Bukkit.getOfflinePlayer(entry.getKey());
                        spots.shareWith(player, target, whom);
                    }
                    openShare(player, key, number);
                }));
            }
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-spots", click -> later(() -> openSpots(player, 0)));
        paging(page, number, pages, n -> openShare(player, key, n));
        player.openInventory(inventory);
    }

    /** The times of day; the one he sees now shines, {@code reset} while it is the world's. */
    private void openTime(Player player) {
        Page page = new Page();
        Inventory inventory = create(page, SMALL, messages.getMessage("menu-time-title"));
        String[] words = {"day", "noon", "sunset", "night", "midnight", "sunrise", "reset"};
        Material[] icons = {Material.SUNFLOWER, Material.GLOWSTONE, Material.ORANGE_DYE, Material.BLUE_DYE,
                Material.BLACK_DYE, Material.YELLOW_DYE, Material.BARRIER};
        Long own = plugin.getSky().ownTime(player);
        String now = own == null ? "reset" : CamSky.timeWord(own);
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            choice(page, 10 + i, new ItemStack(icons[i]), "menu-time-" + word, word.equals(now),
                    click -> act(player, () -> plugin.getSky().timeCommand(player, new String[]{word})));
        }
        put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        player.openInventory(inventory);
    }

    /** The weathers; the one he sees now shines, {@code reset} while it is the world's. */
    private void openWeather(Player player) {
        Page page = new Page();
        Inventory inventory = create(page, SMALL, messages.getMessage("menu-weather-title"));
        String[] words = {"clear", "rain", "reset"};
        Material[] icons = {Material.SUNFLOWER, Material.WATER_BUCKET, Material.BARRIER};
        WeatherType own = plugin.getSky().ownWeather(player);
        String now = own == null ? "reset" : own == WeatherType.CLEAR ? "clear" : "rain";
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            choice(page, 11 + 2 * i, new ItemStack(icons[i]), "menu-weather-" + word, word.equals(now),
                    click -> act(player, () -> plugin.getSky().weatherCommand(player, new String[]{word})));
        }
        put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        player.openInventory(inventory);
    }

    /** The modes of the night vision; the one he picked shines, {@code reset} while he picked none. */
    private void openNightVision(Player player) {
        Page page = new Page();
        Inventory inventory = create(page, SMALL, messages.getMessage("menu-nightvision-title"));
        String[] words = {"on", "dark", "off", "reset"};
        ItemStack[] icons = {MenuIcon.parse(MenuIcon.NIGHT_VISION_POTION).create(plugin.getLogger()),
                new ItemStack(Material.TORCH), new ItemStack(Material.COAL), new ItemStack(Material.BARRIER)};
        String now = plugin.getNightVision().hasOwnMode(player)
                ? nightVisionWord(plugin.getNightVision().modeFor(player)) : "reset";
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            choice(page, 10 + 2 * i, icons[i], "menu-nightvision-" + word, word.equals(now),
                    click -> act(player, () -> plugin.getNightVision().command(player, new String[]{word})));
        }
        put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        player.openInventory(inventory);
    }

    /** One choice of a small page; the one that holds now shines and says so under its name. */
    private void choice(Page page, int slot, ItemStack item, String key, boolean now, Consumer<ClickType> action) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage(key + "-lore")));
        if (now) {
            lore.addAll(lines(messages.getMessage("menu-state-current")));
        }
        put(page, slot, shine(labelled(item, messages.getMessage(key), lore), now), action);
    }

    // ------------------------------------------------------------ admin pages

    /** Whether there is anything on the admin page for him: one of its parts he has the right of. */
    private boolean hasAdminPage(Player player) {
        return plugin.getCamList().mayUse(player) || viewsOthers(player) || managesPlayers(player)
                || plugin.getTrail().mayUse(player);
    }

    private boolean viewsOthers(Player player) {
        return plugin.getStats().showsAnything() && plugin.getStats().mayViewOthers(player);
    }

    /** Whether he may end the camera mode of others or ban them, the page of the players. */
    private boolean managesPlayers(Player player) {
        return plugin.getStop().mayUse(player) || plugin.getBan().mayUse(player);
    }

    /**
     * The admin page, for what admins do with commands otherwise: who is in
     * camera mode, the players to stop and to ban, the statistics of others
     * and the flights of {@code /cam trail} - each where
     * {@code menu.admin-buttons} puts it, only for whoever has its right and
     * while it is switched on.
     */
    private void openAdmin(Player player) {
        if (!settings.getAdminAccess().allowsMenu() || !hasAdminPage(player)) {
            openMain(player);
            return;
        }
        Page page = new Page();
        Inventory inventory = create(page, MenuButton.SLOTS, messages.getMessage("menu-admin-title"));
        if (plugin.getCamList().mayUse(player)) {
            adminButton(page, AdminButton.LIST, "menu-admin-list", click -> later(() -> openCamList(player, 0)));
        }
        if (managesPlayers(player)) {
            adminButton(page, AdminButton.PLAYERS, "menu-admin-players", click -> later(() -> openPlayers(player, 0)));
        }
        if (viewsOthers(player)) {
            adminButton(page, AdminButton.STATS, "menu-admin-stats", click -> later(() -> openStatsOf(player, 0)));
        }
        if (plugin.getTrail().mayUse(player)) {
            adminButton(page, AdminButton.TRAILS, "menu-admin-trails", click -> later(() -> openTrails(player, 0)));
        }
        adminButton(page, AdminButton.BACK, "menu-to-main", click -> later(() -> openMain(player)));
        player.openInventory(inventory);
    }

    /** One button of the admin page, with the item and in the slot {@code menu.admin-buttons} gives it. */
    private void adminButton(Page page, AdminButton which, String key, Consumer<ClickType> action) {
        put(page, settings.getAdminSlot(which), labelled(settings.getAdminIcon(which).create(plugin.getLogger()),
                messages.getMessage(key), lines(messages.getMessage(key + "-lore"))), action);
    }

    /**
     * Who is in camera mode right now, like {@code /cam list}: how long
     * already and how far from the body. Left-click teleports to the camera,
     * right-click to the body ({@code camplugin.teleport}).
     */
    private void openCamList(Player player, int pageNumber) {
        if (!plugin.getCamList().mayUse(player)) {
            openAdmin(player);
            return;
        }
        boolean teleport = plugin.getListTeleport().mayUse(player);
        List<Player> cameras = cameraPlayersSorted();
        int pages = Math.max(1, (cameras.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-list-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < cameras.size(); slot++) {
            Player camera = cameras.get(number * ENTRIES + slot);
            List<String> lore = new ArrayList<>(camLines(camera));
            if (teleport) {
                lore.addAll(lines(messages.getMessage("menu-admin-list-actions")));
            }
            String name = camera.getName();
            put(page, slot, head(camera, messages.getMessage("menu-admin-player").replace("{player}", name), lore),
                    click -> {
                        if (click.isRightClick() && teleport) {
                            act(player, () -> plugin.getListTeleport().command(player, new String[]{name, "body"}));
                        } else if (click.isLeftClick() && teleport) {
                            act(player, () -> plugin.getListTeleport().command(player, new String[]{name, "camera"}));
                        }
                    });
        }
        if (cameras.isEmpty()) {
            put(page, 22, Material.GRAY_DYE, "menu-admin-list-empty", click -> { });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> later(() -> openAdmin(player)));
        paging(page, number, pages, n -> openCamList(player, n));
        player.openInventory(inventory);
    }

    /** The players in camera mode, by name. */
    private List<Player> cameraPlayersSorted() {
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

    /** How long a camera player has been in camera mode and how far from his body, as lines. */
    private List<String> camLines(Player camera) {
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
     * under each head, and in the chat on a click.
     */
    private void openStatsOf(Player player, int pageNumber) {
        if (!viewsOthers(player)) {
            openAdmin(player);
            return;
        }
        List<Player> online = onlineSorted(player);
        int pages = Math.max(1, (online.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-stats-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < online.size(); slot++) {
            Player other = online.get(number * ENTRIES + slot);
            String name = other.getName();
            List<String> lore = statLines(other.getUniqueId(), name);
            lore.addAll(lines(messages.getMessage("menu-admin-stats-actions")));
            put(page, slot, head(other, messages.getMessage("menu-admin-player").replace("{player}", name), lore),
                    click -> act(player, () -> plugin.getStats().command(player, new String[]{"player", name})));
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> later(() -> openAdmin(player)));
        paging(page, number, pages, n -> openStatsOf(player, n));
        player.openInventory(inventory);
    }

    /**
     * The players, to end their camera mode and to ban them: first
     * everybody, then the players online, and those banned who are not. Under
     * each whether he is in camera mode and whether he is banned. A click
     * opens what can be done to him.
     */
    private void openPlayers(Player player, int pageNumber) {
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
        // The first slot of the first page is for everybody.
        int total = list.size() + 1;
        int pages = Math.max(1, (total + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-players-title"));
        for (int slot = 0; slot < ENTRIES; slot++) {
            int index = number * ENTRIES + slot;
            if (index >= total) {
                break;
            }
            Target target = index == 0 ? Target.everybody(messages.getMessage("menu-admin-everybody-name"))
                    : new Target(list.get(index - 1).getKey(), list.get(index - 1).getValue());
            List<String> lore = new ArrayList<>(targetLines(player, target));
            lore.addAll(lines(messages.getMessage("menu-admin-players-actions")));
            put(page, slot, targetItem(target, lore), click -> later(() -> openTarget(player, target)));
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> later(() -> openAdmin(player)));
        paging(page, number, pages, n -> openPlayers(player, n));
        player.openInventory(inventory);
    }

    /**
     * What can be done to one player, or to everybody: end his camera mode,
     * while he is in it, and ban him - or, while he is banned, change his ban
     * or lift it, both on the page of the ban. Its button shines while he is
     * banned.
     */
    private void openTarget(Player player, Target target) {
        if (!managesPlayers(player)) {
            openAdmin(player);
            return;
        }
        CamBan ban = plugin.getBan();
        Page page = new Page();
        Inventory inventory = create(page, SMALL, messages.getMessage("menu-admin-target-title")
                .replace("{player}", target.name()));
        put(page, 13, targetItem(target, targetLines(player, target)), click -> { });
        if (plugin.getStop().mayUse(player)) {
            boolean inCam = target.everybody() ? !plugin.getCameraPlayers().isEmpty() : target.inCam(plugin);
            if (inCam) {
                put(page, 11, Material.BARRIER, target.everybody() ? "menu-admin-stop-all" : "menu-admin-stop",
                        click -> later(() -> {
                            plugin.getStop().command(player,
                                    new String[]{target.everybody() ? "all" : target.name()});
                            openTarget(player, target);
                        }));
            } else {
                put(page, 11, Material.GRAY_DYE, "menu-admin-stop-none", click -> { });
            }
        }
        if (ban.mayUse(player)) {
            boolean banned = target.banned(ban);
            String key = banned ? "menu-admin-ban-change" : "menu-admin-ban";
            put(page, 15, shine(item(Material.IRON_BARS, messages.getMessage(key),
                    lines(messages.getMessage(key + "-lore"))), banned), click -> later(() -> {
                        drafts.put(player.getUniqueId(), draft(target));
                        openBan(player);
                    }));
        }
        put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-players", click -> later(() -> openPlayers(player, 0)));
        player.openInventory(inventory);
    }

    /**
     * A new ban for him, for good and without a reason - or, while he is
     * banned, his ban as it stands, to be changed: the time it still has,
     * rounded up to whole minutes, and its reason.
     */
    private BanDraft draft(Target target) {
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
    private void openBan(Player player) {
        BanDraft draft = drafts.get(player.getUniqueId());
        CamBan ban = plugin.getBan();
        if (draft == null || !ban.mayUse(player)) {
            openPlayers(player, 0);
            return;
        }
        Target target = draft.target;
        Page page = new Page();
        Inventory inventory = create(page, SMALL, messages.getMessage(draft.change
                ? "menu-admin-ban-change-title" : "menu-admin-ban-title").replace("{player}", target.name()));
        put(page, 4, targetItem(target, targetLines(player, target)), click -> { });
        boolean forGood = draft.minutes == 0L;
        List<String> foreverLore = forGood ? lines(messages.getMessage("menu-state-current")) : List.of();
        put(page, 10, shine(item(Material.BEDROCK, messages.getMessage("menu-admin-ban-forever"), foreverLore), forGood),
                click -> later(() -> {
                    draft.minutes = 0L;
                    openBan(player);
                }));
        String time = banTime(draft.minutes);
        banUnit(player, page, 12, "menu-admin-ban-days", draft, DAY, draft.minutes / DAY, time);
        banUnit(player, page, 13, "menu-admin-ban-hours", draft, HOUR, draft.minutes % DAY / HOUR, time);
        banUnit(player, page, 14, "menu-admin-ban-minutes", draft, 1L, draft.minutes % HOUR, time);
        if (draft.change) {
            put(page, 16, Material.MILK_BUCKET, messages.getMessage("menu-admin-unban"),
                    lines(messages.getMessage("menu-admin-unban-lore").replace("{player}", target.name())),
                    click -> later(() -> {
                        drafts.remove(player.getUniqueId());
                        ban.unban(player, new String[]{target.everybody() ? "all" : target.name()});
                        openTarget(player, target);
                    }));
        } else {
            String reason = draft.reason.isEmpty() ? messages.getMessage("menu-admin-ban-no-reason") : draft.reason;
            List<String> reasonLore = new ArrayList<>(lines(messages.getMessage("menu-state")
                    .replace("{state}", reason)));
            reasonLore.addAll(lines(messages.getMessage("menu-admin-ban-reason-actions")));
            put(page, 16, Material.WRITABLE_BOOK, messages.getMessage("menu-admin-ban-reason"), reasonLore, click -> {
                if (click.isRightClick()) {
                    later(() -> {
                        draft.reason = "";
                        openBan(player);
                    });
                } else {
                    act(player, () -> ask(player, "menu-admin-ban-reason-question", answer -> {
                        draft.reason = answer;
                        openBan(player);
                    }, "{player}", target.name()));
                }
            });
        }
        String summary = time;
        if (!draft.reason.isEmpty()) {
            summary += messages.getMessage("ban-reason").replace("{reason}", draft.reason);
        }
        put(page, 22, Material.REDSTONE_BLOCK,
                messages.getMessage(draft.change ? "menu-admin-ban-change-confirm" : "menu-admin-ban-confirm"),
                lines(messages.getMessage("menu-admin-ban-confirm-lore").replace("{player}", target.name())
                        .replace("{ban}", summary)),
                click -> later(() -> {
                    drafts.remove(player.getUniqueId());
                    ban.ban(player, target.everybody() ? "all" : target.name(), draft.minutes, draft.reason);
                    openTarget(player, target);
                }));
        put(page, 18, Material.ARROW, "menu-to-target", click -> later(() -> openTarget(player, target)));
        player.openInventory(inventory);
    }

    /**
     * One of the buttons of the time of a ban: how many days, hours or
     * minutes it has, and what that makes altogether. A click adds one, a
     * right-click takes one away, with shift {@code menu.ban-shift-step} of
     * them. Taking away stops short of nothing - nothing would be for good -,
     * adding at the longest ban the buttons give.
     *
     * @param unit   what one of them is, in minutes
     * @param amount how many of them the ban has now
     * @param time   how long it lasts altogether, as {@link #banTime} has it
     */
    private void banUnit(Player player, Page page, int slot, String key, BanDraft draft, long unit, long amount,
                         String time) {
        int shiftStep = settings.getBanShiftStep();
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-ban-time-lore")
                .replace("{time}", time)));
        lore.addAll(lines(messages.getMessage("menu-admin-ban-time-actions")
                .replace("{step}", String.valueOf(shiftStep))));
        put(page, slot, item(Material.CLOCK, messages.getMessage(key).replace("{amount}", String.valueOf(amount)), lore),
                click -> later(() -> {
                    long step = unit * (click.isShiftClick() ? shiftStep : 1);
                    long minutes = click.isRightClick() ? draft.minutes - step : draft.minutes + step;
                    if (click.isRightClick() ? minutes >= 1L : minutes <= MAX_BAN_MINUTES) {
                        draft.minutes = minutes;
                    }
                    openBan(player);
                }));
    }

    /** How long a ban of so many minutes lasts, the way the texts put it: "for 2h 0m 0s", or "for good" for 0. */
    private String banTime(long minutes) {
        return minutes == 0L ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(minutes * 60L));
    }

    /** Whether he is in camera mode and whether he is banned, as lines under him. */
    private List<String> targetLines(Player viewer, Target target) {
        List<String> lore = new ArrayList<>();
        if (target.everybody()) {
            lore.addAll(lines(messages.getMessage("menu-admin-everybody-cam")
                    .replace("{count}", String.valueOf(cameraPlayersSorted().size()))));
        } else if (Bukkit.getPlayer(target.id()) != null) {
            lore.addAll(target.inCam(plugin) ? camLines(Bukkit.getPlayer(target.id()))
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
    private ItemStack targetItem(Target target, List<String> lore) {
        String label = messages.getMessage("menu-admin-player").replace("{player}", target.name());
        Player online = target.everybody() ? null : Bukkit.getPlayer(target.id());
        ItemStack item = target.everybody() ? item(Material.BEACON, label, lore)
                : online != null ? head(online, label, lore) : item(Material.SKELETON_SKULL, label, lore);
        return shine(item, target.banned(plugin.getBan()));
    }

    /**
     * The players whose flights {@code /cam trail} keeps: a click shows his
     * flights. The last button hides a line that is shown.
     */
    private void openTrails(Player player, int pageNumber) {
        CamTrail trail = plugin.getTrail();
        if (!trail.mayUse(player)) {
            openAdmin(player);
            return;
        }
        List<String> names = new ArrayList<>(trail.recordedNames());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        int pages = Math.max(1, (names.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-trails-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < names.size(); slot++) {
            String name = names.get(number * ENTRIES + slot);
            List<CamTrail.Flight> flights = trail.flightsOf(name);
            Player online = Bukkit.getPlayerExact(name);
            String label = messages.getMessage("menu-admin-player").replace("{player}", name);
            List<String> lore = new ArrayList<>();
            if (!flights.isEmpty()) {
                lore.addAll(lines(messages.getMessage("menu-admin-trails-count")
                        .replace("{count}", String.valueOf(flights.size()))
                        .replace("{date}", date(flights.get(0).startedAt()))));
            }
            lore.addAll(lines(messages.getMessage("menu-admin-trails-actions")));
            put(page, slot, online != null ? head(online, label, lore) : item(Material.LEAD, label, lore),
                    click -> later(() -> openFlights(player, name, 0)));
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> later(() -> openAdmin(player)));
        put(page, SLOT_NEW, Material.BUCKET, "menu-admin-trails-hide",
                click -> act(player, () -> trail.command(player, new String[0])));
        paging(page, number, pages, n -> openTrails(player, n));
        player.openInventory(inventory);
    }

    /**
     * The flights kept of one player, the newest first: under each when it
     * began, how long and how far it went, where it began and ended. A
     * left-click shows it; a right-click shows it and takes him to where it
     * began. The last button shows all of them at once.
     */
    private void openFlights(Player player, String name, int pageNumber) {
        CamTrail trail = plugin.getTrail();
        if (!trail.mayUse(player)) {
            openAdmin(player);
            return;
        }
        List<CamTrail.Flight> flights = trail.flightsOf(name);
        if (flights.isEmpty()) {
            openTrails(player, 0);
            return;
        }
        int pages = Math.max(1, (flights.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-flights-title")
                .replace("{player}", name));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < flights.size(); slot++) {
            CamTrail.Flight flight = flights.get(number * ENTRIES + slot);
            String when = date(flight.startedAt());
            List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-flight-lore")
                    .replace("{date}", when)
                    .replace("{time}", CamTimeLimit.formatDuration(TimeUnit.MILLISECONDS.toSeconds(flight.duration())))
                    .replace("{distance}", String.valueOf(Math.round(flight.distance())))
                    .replace("{from}", trail.place(flight.start()))
                    .replace("{to}", trail.place(flight.last()))));
            if (flight.flying()) {
                lore.addAll(lines(messages.getMessage("menu-admin-flight-flying")));
            }
            lore.addAll(lines(messages.getMessage("menu-admin-flight-actions")));
            String label = messages.getMessage("menu-admin-flight")
                    .replace("{number}", String.valueOf(flight.number())).replace("{date}", when);
            put(page, slot, flight.flying() ? Material.ENDER_EYE : Material.PAPER, label, lore, click -> {
                if (click.isRightClick()) {
                    act(player, () -> trail.showFlight(player, name, flight.startedAt(), true));
                } else if (click.isLeftClick()) {
                    act(player, () -> trail.showFlight(player, name, flight.startedAt(), false));
                }
            });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-trails", click -> later(() -> openTrails(player, 0)));
        put(page, SLOT_NEW, Material.SPYGLASS, "menu-admin-flights-all",
                click -> act(player, () -> trail.command(player, new String[]{name, "all"})));
        paging(page, number, pages, n -> openFlights(player, name, n));
        player.openInventory(inventory);
    }

    /**
     * A moment as {@code menu-admin-flight-date} writes it, in the time zone
     * of the server. A pattern that is none falls back on year, month, day,
     * hour and minute.
     */
    private String date(long millis) {
        ZonedDateTime when = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        try {
            return DateTimeFormatter.ofPattern(messages.getMessage("menu-admin-flight-date")).format(when);
        } catch (IllegalArgumentException ex) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(when);
        }
    }

    /** The players online he can see, by name, for the admin pages. */
    private static List<Player> onlineSorted(Player viewer) {
        List<Player> online = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer) || viewer.canSee(other)) {
                online.add(other);
            }
        }
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return online;
    }

    /** The head of a player online, with a name and lines of the menu. */
    private static ItemStack head(Player owner, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            head.setItemMeta(skull);
        }
        return labelled(head, name, lore);
    }

    // ------------------------------------------------------------ questions in the chat

    /** Asks for the name of a new spot in the chat; the next line he writes is it. */
    private void askName(Player player) {
        ask(player, "menu-spot-name", name -> plugin.getSpots().set(player, name));
    }

    /**
     * Asks something in the chat: the next line he writes is the answer, or
     * {@code cancel}, which calls it off with {@code menu-answer-cancelled}.
     *
     * @param key    the question, with {@code {cancel}} for the word that calls it off
     * @param answer what the answer does, on the main thread
     */
    private void ask(Player player, String key, Consumer<String> answer, String... fills) {
        questions.put(player.getUniqueId(), new Question(System.currentTimeMillis() + ANSWER_WAIT, answer));
        String[] all = Arrays.copyOf(fills, fills.length + 2);
        all[fills.length] = "{cancel}";
        all[fills.length + 1] = CANCEL;
        messages.sendConfiguredMessage(player, key, all);
    }

    /**
     * The line he writes after a question of the menu: the answer, or
     * {@code cancel}. Nobody else reads it. Asked on the thread of the chat,
     * so the answer itself is acted on on the main thread - the name of a
     * spot is saved where he stands then.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Question question = questions.remove(player.getUniqueId());
        if (question == null || question.until() < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String answer = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (answer.equalsIgnoreCase(CANCEL)) {
                messages.sendConfiguredMessage(player, "menu-answer-cancelled");
            } else {
                question.answer().accept(answer);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        questions.remove(event.getPlayer().getUniqueId());
        drafts.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ clicks

    /**
     * Every click into a window of the menu is turned away - in the window
     * and in his own inventory below it, which a shift-click would empty into
     * it -, and the button it hit is pressed.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Page page)) {
            return;
        }
        event.setCancelled(true);
        // The slots of the window come first, his own inventory after them.
        if (event.getRawSlot() < 0 || event.getRawSlot() >= page.getInventory().getSize()) {
            return;
        }
        Consumer<ClickType> button = page.buttons.get(event.getRawSlot());
        if (button != null) {
            button.accept(event.getClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Page) {
            event.setCancelled(true);
        }
    }

    /**
     * Closes the menu and does what the button says, a tick later: the server
     * does not let a window be closed in the middle of a click into it.
     */
    private void act(Player player, Runnable action) {
        later(() -> {
            player.closeInventory();
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    /** Runs something a tick later, out of the click. */
    private void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    // ------------------------------------------------------------ items

    private static Inventory create(Page page, int size, String title) {
        Inventory inventory = Bukkit.createInventory(page, size, title);
        page.inventory = inventory;
        return inventory;
    }

    /** A button by a text of the language file: its name, and under it the text with {@code -lore} after the key. */
    private void put(Page page, int slot, Material material, String key, Consumer<ClickType> action) {
        put(page, slot, material, messages.getMessage(key), lines(messages.getMessage(key + "-lore")), action);
    }

    private void put(Page page, int slot, Material material, String name, List<String> lore,
                     Consumer<ClickType> action) {
        put(page, slot, item(material, name, lore), action);
    }

    private static void put(Page page, int slot, ItemStack item, Consumer<ClickType> action) {
        page.inventory.setItem(slot, item);
        page.buttons.put(slot, action);
    }

    /** The buttons to the page before and after, where there is one. */
    private void paging(Page page, int number, int pages, Consumer<Integer> open) {
        if (number > 0) {
            put(page, SLOT_PREVIOUS, Material.ARROW, "menu-previous", click -> later(() -> open.accept(number - 1)));
        }
        if (number < pages - 1) {
            put(page, SLOT_NEXT, Material.ARROW, "menu-next", click -> later(() -> open.accept(number + 1)));
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return labelled(new ItemStack(material), name, lore);
    }

    /** An item with a name and lines of the menu in place of its own. */
    private static ItemStack labelled(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Lets an item shine like an enchanted one, for what is switched on or holds now. */
    private static ItemStack shine(ItemStack item, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (glint && meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A text of the language file as lines under an item, {@code \n} beginning a new one; none for an empty one. */
    private static List<String> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.split("\n"));
    }

    /** {@code {x}}, {@code {y}}, {@code {z}} and {@code {world}} of a spot. */
    private static String place(String text, Spot spot) {
        return text.replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                .replace("{world}", spot.world());
    }
}
