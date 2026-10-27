package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.ban.BanList;
import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.body.PlayerSkins;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.sky.CamSky;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import de.elia.cameraplugin.tour.CamTour;
import de.elia.cameraplugin.trail.CamTrail;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import de.elia.cameraplugin.spot.SpotModeration;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * {@code /cam menu}: what camera mode offers, in a window to click instead of
 * commands to type - starting and ending it, {@code back}, {@code resume},
 * the spots of {@code /cam spot} - private, public and shared with him, each
 * on a tab of its own -, the tour of {@code /cam tour}, {@code photo},
 * {@code time}, {@code weather}, {@code nightvision} and the own
 * {@code stats}; and for admins a page of their own: who is in camera mode
 * ({@code list}, {@code tp}, {@code stop}), the statistics of others, the
 * bans and the flights of {@code /cam trail}. The lists of spots and of
 * players have a button that sorts them.
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
    /** The size of the page of a restriction of the spots: four rows, for what it keeps him from as well. */
    private static final int MEDIUM = 36;
    /** Where the page of a restriction has the buttons of his own spots, the public ones and the shared ones. */
    private static final int[] RESTRICT_SLOTS = {11, 13, 15};

    /** The slots of a list page that hold entries; the last row holds the buttons to go on. */
    private static final int ENTRIES = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_NEW = 49;
    private static final int SLOT_NEXT = 50;
    /** The button back to the main page on the small pages. */
    private static final int SLOT_SMALL_BACK = 22;
    /**
     * The last row of the pages of the spots: after the button back the
     * pages, then the tabs, the order next to them, a gap, and on the right
     * the button for a new spot.
     */
    private static final int SLOT_SPOT_PREVIOUS = 46;
    private static final int SLOT_SPOT_NEXT = 47;
    private static final int SLOT_SPOT_SORT = 51;
    private static final int SLOT_SPOT_NEW = 53;
    /** The button that sorts a list of players on the admin pages. */
    private static final int SLOT_ADMIN_SORT = 53;

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

    /** The restrictions of the spots being set up on their page, by the id of the admin. */
    private final Map<UUID, SpotBanDraft> spotDrafts = new HashMap<>();

    /** How each player has the tabs of the spots sorted, while he is online. */
    private final Map<UUID, Map<SpotTab, SpotSort>> spotSorts = new HashMap<>();

    /** How each admin has the lists of players sorted, while he is online. */
    private final Map<UUID, PlayerSort> playerSorts = new HashMap<>();

    /** The orders of the spots of a player on the admin page, the first being where it starts. */
    private static final List<String> ADMIN_SPOT_SORTS = List.of("date", "name", "likes", "views", "near");

    /** How each admin has the spots of a player sorted on the admin page, while he is online. */
    private final Map<UUID, SpotSort> adminSpotSorts = new HashMap<>();

    /**
     * The tabs of the spots: all his own, the public ones of everybody and
     * those shared with him - each with its icon, its slot in the last row
     * and the orders it can be sorted in, the first being where it starts.
     */
    private enum SpotTab {
        PRIVATE("private", Material.FILLED_MAP, 48, List.of("date", "name", "views", "near")),
        PUBLIC("public", Material.GLOBE_BANNER_PATTERN, 49,
                List.of("date", "name", "owner", "likes", "views", "near")),
        SHARED("shared", Material.MAP, 50, List.of("date", "name", "owner", "views", "near"));

        final String key;
        final Material icon;
        final int slot;
        final List<String> sorts;

        SpotTab(String key, Material icon, int slot, List<String> sorts) {
            this.key = key;
            this.icon = icon;
            this.slot = slot;
            this.sorts = sorts;
        }
    }

    /**
     * The orders of the spots: the newest first, by name, by the name of the
     * owner, the most liked first, the most viewed first, the nearest first.
     */
    private enum SpotSort {
        DATE("date"), NAME("name"), OWNER("owner"), LIKES("likes"), VIEWS("views"), NEAR("near");

        final String key;

        SpotSort(String key) {
            this.key = key;
        }

        static SpotSort of(String key) {
            for (SpotSort sort : values()) {
                if (sort.key.equals(key)) {
                    return sort;
                }
            }
            return DATE;
        }
    }

    /** The orders of the lists of players on the admin pages: by name, or the last flight first. */
    private enum PlayerSort {
        NAME("name"), LAST_FLIGHT("last-flight");

        static final List<String> KEYS = List.of("name", "last-flight");

        final String key;

        PlayerSort(String key) {
            this.key = key;
        }

        static PlayerSort of(String key) {
            return key.equals(LAST_FLIGHT.key) ? LAST_FLIGHT : NAME;
        }
    }

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

    /**
     * A restriction of the spots of a player being set up on its page: whom,
     * what he may not do, for how long and why - a new one, or the one he
     * has already, to be changed.
     */
    private static final class SpotBanDraft {
        final UUID id;
        final String name;
        /** Whether something keeps him already, and the page changes it. */
        final boolean change;
        /** How far it keeps him from each kind of spots. */
        final Map<SpotBans.Kind, SpotBans.Stage> stages = new EnumMap<>(SpotBans.Kind.class);
        /** How long, in minutes; 0 for good. */
        long minutes;
        String reason = "";

        /** How far it keeps him from this kind of spots. */
        SpotBans.Stage stage(SpotBans.Kind kind) {
            return stages.getOrDefault(kind, SpotBans.Stage.FREE);
        }

        /** Whether it keeps him from anything at all. */
        boolean any() {
            return stages.values().stream().anyMatch(stage -> stage != SpotBans.Stage.FREE);
        }

        SpotBanDraft(UUID id, String name, boolean change) {
            this.id = id;
            this.name = name;
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
            spotsButton(player, page);
        }
        if (settings.isTourEnabled()) {
            tourButton(player, page);
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

    /**
     * The button of the spots on the main page. While spots are offered to
     * him it shines, says how many, and opens on the tab they wait on.
     */
    private void spotsButton(Player player, Page page) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-spots-lore")));
        int offers = plugin.getSpots().offers(player).size();
        if (offers > 0) {
            lore.addAll(lines(messages.getMessage("menu-spots-offers").replace("{count}", String.valueOf(offers))));
        }
        ItemStack item = labelled(settings.getMenuIcon(MenuButton.SPOTS).create(plugin.getLogger()),
                messages.getMessage("menu-spots"), lore);
        SpotTab tab = offers > 0 ? SpotTab.SHARED : SpotTab.PRIVATE;
        put(page, settings.getMenuSlot(MenuButton.SPOTS), shine(item, offers > 0),
                click -> later(() -> openSpots(player, tab, 0)));
    }

    /**
     * The button of the tour on the main page: under it how his tour stands -
     * whom he follows, or how many guests he has -, and how many
     * invitations wait for him, which make it shine.
     */
    private void tourButton(Player player, Page page) {
        CamTour tour = plugin.getTour();
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-tour-lore")));
        Player guide = tour.guideOf(player);
        int guests = tour.guestsOf(player).size();
        if (guide != null) {
            lore.addAll(lines(messages.getMessage("menu-tour-following").replace("{player}", guide.getName())));
        } else if (guests > 0) {
            int max = settings.getTourMaxGuests();
            lore.addAll(lines(messages.getMessage(max > 0 ? "menu-tour-guiding-max" : "menu-tour-guiding")
                    .replace("{count}", String.valueOf(guests)).replace("{max}", String.valueOf(max))));
        }
        int invited = tour.invitationsOf(player).size();
        if (invited > 0) {
            lore.addAll(lines(messages.getMessage("menu-tour-invitations").replace("{count}",
                    String.valueOf(invited))));
        }
        ItemStack item = labelled(settings.getMenuIcon(MenuButton.TOUR).create(plugin.getLogger()),
                messages.getMessage("menu-tour"), lore);
        put(page, settings.getMenuSlot(MenuButton.TOUR), shine(item, invited > 0),
                click -> later(() -> openTour(player, 0)));
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
     * The spots, a page of {@value #ENTRIES} at a time, on one of three tabs:
     * all his own - the public ones among them -, the public ones of
     * everybody, and those shared with him - the offers he has not accepted
     * yet first. With sharing switched off there are only his own. The last
     * row: back, the pages, the tabs, the order, and on the right the button
     * for a new spot - left-click private, right-click public.
     */
    private void openSpots(Player player, SpotTab wanted, int pageNumber) {
        CamSpots spots = plugin.getSpots();
        boolean sharing = settings.isViewpointsSharing();
        SpotTab tab = sharing ? wanted : SpotTab.PRIVATE;
        SpotSort sort = spotSort(player, tab);
        Location from = nearFrom(player);
        List<Consumer<Integer>> entries = new ArrayList<>();
        Page page = new Page();
        // A tab of the spots a restriction keeps him from looking at shows him only that.
        boolean mayLook = plugin.getSpotModeration().mayLook(player, SpotBans.Kind.valueOf(tab.name()));
        if (mayLook) {
            switch (tab) {
                case PRIVATE -> {
                    for (Spot spot : sorted(spots.own(player), sort, own -> own, own -> "", own -> 0,
                            own -> spots.views(player.getUniqueId(), own), from)) {
                        entries.add(slot -> putOwnSpot(player, page, slot, spot));
                    }
                }
                case PUBLIC -> {
                    for (Share share : sorted(spots.publicSpots(), sort, Share::getSpot, Share::getOwnerName,
                            Share::likes, share -> spots.views(share.getOwner(), share.getSpot()), from)) {
                        entries.add(slot -> putPublicSpot(player, page, slot, share));
                    }
                }
                case SHARED -> {
                    for (Share share : spots.offers(player)) {
                        entries.add(slot -> putOffer(player, page, slot, share));
                    }
                    for (Share share : sorted(spots.accepted(player), sort, Share::getSpot, Share::getOwnerName,
                            Share::likes, share -> spots.views(share.getOwner(), share.getSpot()), from)) {
                        entries.add(slot -> putSharedSpot(player, page, slot, share));
                    }
                }
            }
        }
        int pages = Math.max(1, (entries.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Inventory inventory = create(page, LIST, messages.getMessage(sharing ? "menu-spots-title-" + tab.key
                : "menu-spots-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < entries.size(); slot++) {
            entries.get(number * ENTRIES + slot).accept(slot);
        }
        if (!mayLook) {
            put(page, 22, Material.GRAY_DYE, messages.getMessage("menu-spots-restricted"),
                    lines(plugin.getSpotModeration().fill(messages.getMessage("menu-spots-restricted-lore"),
                            player.getUniqueId())), click -> { });
        } else if (entries.isEmpty()) {
            put(page, 22, Material.GRAY_DYE, "menu-spots-empty-" + tab.key, click -> { });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        if (sharing) {
            for (SpotTab other : SpotTab.values()) {
                putTab(player, page, tab, other);
            }
        }
        putSort(page, SLOT_SPOT_SORT, sort.key, tab.sorts, current -> {
            spotSorts.computeIfAbsent(player.getUniqueId(), id -> new EnumMap<>(SpotTab.class)).put(tab,
                    SpotSort.of(current));
            openSpots(player, tab, 0);
        });
        List<String> newLore = new ArrayList<>(lines(spotCount(player)));
        newLore.addAll(lines(messages.getMessage(sharing ? "menu-new-spot-lore-public" : "menu-new-spot-lore")));
        put(page, SLOT_SPOT_NEW, Material.WRITABLE_BOOK, messages.getMessage("menu-new-spot"), newLore,
                click -> act(player, () -> askName(player, sharing && click.isRightClick())));
        paging(page, number, pages, n -> openSpots(player, tab, n), SLOT_SPOT_PREVIOUS, SLOT_SPOT_NEXT);
        player.openInventory(inventory);
    }

    /**
     * How many spots he has, and how many more he may save under
     * {@code viewpoints.max-per-player}, for the button of a new spot - or
     * only how many he has, while there is no limit.
     */
    private String spotCount(Player player) {
        int count = plugin.getSpots().own(player).size();
        if (!settings.limitsViewpoints()) {
            return messages.getMessage("menu-new-spot-count-unlimited").replace("{count}", String.valueOf(count));
        }
        int left = Math.max(0, settings.getViewpointsMax() - count);
        return messages.getMessage(left > 0 ? "menu-new-spot-count" : "menu-new-spot-count-full")
                .replace("{count}", String.valueOf(count))
                .replace("{max}", String.valueOf(settings.getViewpointsMax()))
                .replace("{left}", String.valueOf(left));
    }

    /**
     * What "nearest first" measures from: where his camera is held from - his
     * body, or the spot he visits -, the same spot a jump is measured from;
     * out of camera mode, where he stands, which is where his body will be.
     */
    private Location nearFrom(Player player) {
        CameraData data = plugin.getCameraPlayers().get(player.getUniqueId());
        return data != null ? plugin.getMovementGuard().home(data) : player.getLocation();
    }

    /**
     * The button of one tab of the spots: the one open shines and says so;
     * the tab of the shared spots counts the offers waiting on it.
     */
    private void putTab(Player player, Page page, SpotTab open, SpotTab tab) {
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-spots-tab-" + tab.key + "-lore")));
        int offers = tab == SpotTab.SHARED ? plugin.getSpots().offers(player).size() : 0;
        if (offers > 0) {
            lore.addAll(lines(messages.getMessage("menu-spots-offers").replace("{count}", String.valueOf(offers))));
        }
        if (tab == open) {
            lore.addAll(lines(messages.getMessage("menu-state-current")));
        }
        ItemStack item = item(tab.icon, messages.getMessage("menu-spots-tab-" + tab.key), lore);
        item.setAmount(Math.max(1, Math.min(offers, item.getMaxStackSize())));
        put(page, tab.slot, shine(item, tab == open), click -> later(() -> openSpots(player, tab, 0)));
    }

    /**
     * One of his own spots, private or public - a public one says so, with
     * its likes: left-click jumps to it, right-click shares it or makes it
     * public, shift-click deletes it.
     */
    private void putOwnSpot(Player player, Page page, int slot, Spot spot) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(player.getUniqueId(), spot);
        Share share = spots.shareOf(player, spot);
        if (share != null && share.isEveryone()) {
            lore.addAll(lines(messages.getMessage("menu-spot-public-lore")
                    .replace("{likes}", String.valueOf(share.likes()))));
        }
        if (share != null && !share.getPlayers().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-shared-lore").replace("{players}", spots.whom(share))));
        }
        if (share != null && !share.getPending().isEmpty()) {
            lore.addAll(lines(messages.getMessage("menu-spot-offered-lore")
                    .replace("{players}", spots.offeredTo(share))));
        }
        lore.addAll(reachLore());
        lore.addAll(lines(messages.getMessage(settings.isViewpointsSharing() ? "menu-spot-actions-share"
                : "menu-spot-actions")));
        put(page, slot, Material.FILLED_MAP, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore,
                click -> {
                    if (click.isShiftClick()) {
                        later(() -> {
                            spots.delete(player, spot.name());
                            openSpots(player, SpotTab.PRIVATE, 0);
                        });
                    } else if (click.isRightClick() && settings.isViewpointsSharing()) {
                        later(() -> openShare(player, spot.key(), 0));
                    } else if (click.isLeftClick()) {
                        act(player, () -> spots.jump(player, spot.name()));
                    }
                });
    }

    /**
     * A public spot. One of his own: left-click jumps to it, right-click takes
     * it offline - it stays among his own; it is deleted there. One of another
     * player: left-click jumps to it like to one of his own, in camera mode
     * and within reach, right-click likes it or no longer does, shift-click
     * visits it; it shines while he likes it.
     */
    private void putPublicSpot(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        Spot spot = share.getSpot();
        boolean own = share.getOwner().equals(player.getUniqueId());
        List<String> lore = spotLore(share.getOwner(), spot);
        lore.addAll(lines(messages.getMessage("menu-spot-likes").replace("{likes}", String.valueOf(share.likes()))));
        if (own) {
            lore.addAll(reachLore());
            lore.addAll(lines(messages.getMessage("menu-public-own-actions")));
            put(page, slot, Material.FILLED_MAP, messages.getMessage("menu-public-own").replace("{spot}", spot.name()),
                    lore, click -> {
                        if (click.isShiftClick()) {
                            // Deleting is for the tab of his own spots, not this one.
                            return;
                        }
                        if (click.isRightClick()) {
                            later(() -> {
                                spots.makePublic(player, spot, false);
                                openSpots(player, SpotTab.PUBLIC, 0);
                            });
                        } else if (click.isLeftClick()) {
                            act(player, () -> spots.jump(player, spot.name()));
                        }
                    });
            return;
        }
        boolean liked = share.isLikedBy(player.getUniqueId());
        if (liked) {
            lore.addAll(lines(messages.getMessage("menu-spot-liked")));
        }
        lore.addAll(reachLore());
        lore.addAll(lines(messages.getMessage(liked ? "menu-public-spot-actions-unlike"
                : "menu-public-spot-actions")));
        lore.addAll(visitLore());
        String name = messages.getMessage("menu-shared-spot").replace("{owner}", share.getOwnerName())
                .replace("{spot}", spot.name());
        put(page, slot, shine(item(Material.MAP, name, lore), liked), click -> {
            if (click.isShiftClick()) {
                visit(player, share);
            } else if (click.isRightClick()) {
                later(() -> {
                    spots.like(player, share);
                    openSpots(player, SpotTab.PUBLIC, 0);
                });
            } else if (click.isLeftClick()) {
                act(player, () -> spots.jump(player, share.label()));
            }
        });
    }

    /** A spot offered to him: left-click accepts it, right-click turns it down. */
    private void putOffer(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(share.getOwner(), share.getSpot());
        lore.addAll(lines(messages.getMessage("menu-offer-actions")));
        String name = messages.getMessage("menu-offer").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        put(page, slot, shine(item(Material.PAPER, name, lore), true), click -> {
            if (click.isRightClick()) {
                later(() -> {
                    spots.decline(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            } else if (click.isLeftClick()) {
                later(() -> {
                    spots.accept(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            }
        });
    }

    /**
     * A spot shared with him that he accepted: left-click jumps to it like to
     * one of his own, in camera mode and within reach, right-click takes it
     * off his list, shift-click visits it.
     */
    private void putSharedSpot(Player player, Page page, int slot, Share share) {
        CamSpots spots = plugin.getSpots();
        List<String> lore = spotLore(share.getOwner(), share.getSpot());
        lore.addAll(reachLore());
        lore.addAll(lines(messages.getMessage("menu-shared-spot-actions")));
        lore.addAll(visitLore());
        String name = messages.getMessage("menu-shared-spot").replace("{owner}", share.getOwnerName())
                .replace("{spot}", share.getSpot().name());
        put(page, slot, Material.MAP, name, lore, click -> {
            if (click.isShiftClick()) {
                visit(player, share);
            } else if (click.isRightClick()) {
                later(() -> {
                    spots.decline(player, share);
                    openSpots(player, SpotTab.SHARED, 0);
                });
            } else if (click.isLeftClick()) {
                act(player, () -> spots.jump(player, share.label()));
            }
        });
    }

    /**
     * How close he has to be to jump to a spot, over what a click does:
     * without camera mode within {@code viewpoints.start-distance} of it, in
     * camera mode where his camera may fly - or only the latter, while
     * {@code viewpoints.start-distance} is 0.
     */
    private List<String> reachLore() {
        double start = settings.getViewpointsStartDistance();
        return lines(messages.getMessage(start > 0.0 ? "menu-spot-reach-lore" : "menu-spot-reach-lore-cam-only")
                .replace("{start}", String.valueOf(Math.round(start))));
    }

    /** What shift-click does on a spot of another player, while {@code visitor-camera} is on: nothing otherwise. */
    private List<String> visitLore() {
        return settings.isVisitorCameraEnabled() ? lines(messages.getMessage("menu-spot-visit-action")) : List.of();
    }

    /** Visits a spot of another player with the visitor camera, while it is on. */
    private void visit(Player player, Share share) {
        if (settings.isVisitorCameraEnabled()) {
            act(player, () -> plugin.getVisit().visit(player, share));
        }
    }

    /**
     * Where a spot of this owner is, since when, and how often it was looked
     * at, as lines under it - with how often one player counts, while
     * {@code viewpoints.views-cooldown-minutes} holds him back.
     */
    private List<String> spotLore(UUID owner, Spot spot) {
        List<String> lore = new ArrayList<>(lines(place(messages.getMessage("menu-spot-lore"), spot)));
        if (spot.created() > 0L) {
            lore.addAll(lines(messages.getMessage("menu-spot-created").replace("{date}", date(spot.created()))));
        }
        lore.addAll(lines(messages.getMessage("menu-spot-views")
                .replace("{views}", String.valueOf(plugin.getSpots().views(owner, spot)))));
        int minutes = settings.getViewsCooldownMinutes();
        if (minutes > 0) {
            lore.addAll(lines(messages.getMessage("menu-spot-views-hint")
                    .replace("{minutes}", String.valueOf(minutes))));
        }
        return lore;
    }

    /**
     * Whom one of his spots is shared with: public first, then the players
     * online and those it is shared with or offered to who are not, a page of
     * {@value #ENTRIES} at a time. A click on a player offers it to him, or
     * takes it back; a click on the first makes it public, or private again.
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
            openSpots(player, SpotTab.PRIVATE, 0);
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
            for (Map.Entry<UUID, String> entry : share.getPending().entrySet()) {
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
                put(page, slot, shine(item(Material.BEACON, messages.getMessage("menu-share-everybody"),
                        lines(messages.getMessage(everybody ? "menu-share-public-lore" : "menu-share-private-lore"))),
                        everybody), click -> later(() -> {
                            spots.makePublic(player, target, !everybody);
                            openShare(player, key, number);
                        }));
            } else if (index < total) {
                Map.Entry<UUID, String> entry = list.get(index - 1);
                boolean with = share != null && share.isAcceptedBy(entry.getKey());
                boolean offered = share != null && share.isOfferedTo(entry.getKey());
                String state = with ? "on" : offered ? "offered" : "off";
                ItemStack head = item(Material.PLAYER_HEAD,
                        messages.getMessage(with || offered ? "menu-share-player-" + state : "menu-share-player")
                                .replace("{player}", entry.getValue()),
                        lines(messages.getMessage("menu-share-" + state + "-lore")));
                Player other = Bukkit.getPlayer(entry.getKey());
                if (other != null && head.getItemMeta() instanceof SkullMeta skull) {
                    // Only of a player online: one offline might have to be
                    // asked of Mojang for his skin.
                    PlayerSkins.putOn(skull, other);
                    head.setItemMeta(skull);
                }
                put(page, slot, head, click -> later(() -> {
                    if (with || offered) {
                        spots.unshareWith(player, target, entry.getKey(), entry.getValue());
                    } else {
                        OfflinePlayer whom = other != null ? other : Bukkit.getOfflinePlayer(entry.getKey());
                        spots.shareWith(player, target, whom);
                    }
                    openShare(player, key, number);
                }));
            }
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-spots",
                click -> later(() -> openSpots(player, SpotTab.PRIVATE, 0)));
        paging(page, number, pages, n -> openShare(player, key, n));
        player.openInventory(inventory);
    }

    /** How he has a tab of the spots sorted: the newest first, until he picks another order. */
    private SpotSort spotSort(Player player, SpotTab tab) {
        Map<SpotTab, SpotSort> own = spotSorts.get(player.getUniqueId());
        SpotSort sort = own == null ? null : own.get(tab);
        return sort != null && tab.sorts.contains(sort.key) ? sort : SpotSort.DATE;
    }

    /**
     * Spots in an order: the newest first - those without a date, saved
     * before dates were kept, last -, by name, by owner, the most liked
     * first, the most viewed first, or the nearest first - those in another
     * world last. What is the same in one order goes by name, then by owner.
     *
     * @param views how often each was looked at, see {@link de.elia.cameraplugin.spot.SpotViews}
     * @param from  what "nearest first" measures from, see {@link #nearFrom}
     */
    private static <T> List<T> sorted(Collection<T> items, SpotSort sort, Function<T, Spot> spot,
                                      Function<T, String> owner, ToIntFunction<T> likes, ToLongFunction<T> views,
                                      Location from) {
        Comparator<T> byName = Comparator.comparing(item -> spot.apply(item).name(), String.CASE_INSENSITIVE_ORDER);
        Comparator<T> byOwner = Comparator.comparing(owner, String.CASE_INSENSITIVE_ORDER);
        Comparator<T> newest = Comparator.comparingLong((T item) -> spot.apply(item).created()).reversed();
        Comparator<T> order = switch (sort) {
            case DATE -> newest.thenComparing(byName).thenComparing(byOwner);
            case NAME -> byName.thenComparing(byOwner);
            case OWNER -> byOwner.thenComparing(byName);
            case LIKES -> Comparator.comparingInt(likes).reversed().thenComparing(newest).thenComparing(byName);
            case VIEWS -> Comparator.comparingLong(views).reversed().thenComparing(newest).thenComparing(byName);
            case NEAR -> Comparator.comparingDouble((T item) -> distance(spot.apply(item), from))
                    .thenComparing(byName).thenComparing(byOwner);
        };
        List<T> list = new ArrayList<>(items);
        list.sort(order);
        return list;
    }

    /** How far a spot is from a place, squared - endlessly far in another world. */
    private static double distance(Spot spot, Location from) {
        if (from.getWorld() == null || !from.getWorld().getName().equals(spot.world())) {
            return Double.MAX_VALUE;
        }
        double dx = spot.x() - from.getX();
        double dy = spot.y() - from.getY();
        double dz = spot.z() - from.getZ();
        return dx * dx + dy * dy + dz * dz;
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
    private void putSort(Page page, int slot, String now, List<String> orders, Consumer<String> choose) {
        String name = messages.getMessage("menu-sort").replace("{sort}", messages.getMessage("menu-sort-" + now));
        List<String> lore = new ArrayList<>();
        for (String order : orders) {
            lore.addAll(lines(messages.getMessage(order.equals(now) ? "menu-sort-option-current" : "menu-sort-option")
                    .replace("{sort}", messages.getMessage("menu-sort-" + order))));
        }
        lore.addAll(lines(messages.getMessage("menu-sort-lore")));
        put(page, slot, Material.HOPPER, name, lore, click -> later(() -> {
            int at = Math.max(0, orders.indexOf(now));
            int step = click.isRightClick() ? orders.size() - 1 : 1;
            choose.accept(orders.get((at + step) % orders.size()));
        }));
    }

    /**
     * The tour. While he follows one: whom, and the button that leaves it.
     * Otherwise, a page of {@value #ENTRIES} at a time: the invitations he
     * has - left-click accepts, right-click turns down -, his guests - a
     * click sends one away -, and whom he could invite: players close to him
     * who could follow him from where they are, in camera mode or not, with
     * how far away they are; a click invites one. Is there nobody, the page
     * says how close they would have to be. While he has guests, the last
     * row has the button that ends the tour. Both pages have the button that
     * switches between the guide's view and looking round freely, where the
     * guide's view works.
     */
    private void openTour(Player player, int pageNumber) {
        CamTour tour = plugin.getTour();
        if (!settings.isTourEnabled()) {
            openMain(player);
            return;
        }
        Player guide = tour.guideOf(player);
        if (guide != null) {
            Page page = new Page();
            Inventory inventory = create(page, SMALL, messages.getMessage("menu-tour-title"));
            put(page, 11, head(guide, messages.getMessage("menu-tour-guide").replace("{player}", guide.getName()),
                    lines(messages.getMessage("menu-tour-guide-lore").replace("{player}", guide.getName()))),
                    click -> { });
            viewButton(player, page, 13);
            put(page, 15, Material.BARRIER, "menu-tour-leave", click -> act(player, () -> tour.leave(player)));
            put(page, SLOT_SMALL_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
            player.openInventory(inventory);
            return;
        }
        List<Consumer<Integer>> entries = new ArrayList<>();
        Page page = new Page();
        for (Player inviter : tour.invitationsOf(player)) {
            String name = inviter.getName();
            entries.add(slot -> put(page, slot, shine(head(inviter,
                    messages.getMessage("menu-tour-invitation").replace("{player}", name),
                    lines(messages.getMessage("menu-tour-invitation-lore").replace("{player}", name))), true),
                    click -> {
                        if (click.isRightClick()) {
                            later(() -> {
                                tour.decline(player, inviter, name);
                                openTour(player, 0);
                            });
                        } else if (click.isLeftClick()) {
                            act(player, () -> tour.accept(player, inviter, name));
                        }
                    }));
        }
        List<Player> guests = tour.guestsOf(player);
        for (Player guest : guests) {
            String name = guest.getName();
            entries.add(slot -> put(page, slot, head(guest,
                    messages.getMessage("menu-tour-guest").replace("{player}", name),
                    lines(messages.getMessage("menu-tour-guest-lore"))), click -> later(() -> {
                        tour.kick(player, guest, name);
                        openTour(player, 0);
                    })));
        }
        if (plugin.isInCameraMode(player)) {
            boolean full = tour.isFull(player);
            for (Player other : tour.candidates(player)) {
                String name = other.getName();
                String key = full ? "menu-tour-candidate-full-lore"
                        : tour.hasInvited(player, other) ? "menu-tour-candidate-invited-lore"
                        : plugin.isInCameraMode(other) ? "menu-tour-candidate-lore"
                        : "menu-tour-candidate-outside-lore";
                long blocks = Math.round(player.getLocation().distance(other.getLocation()));
                String lore = messages.getMessage(key).replace("{blocks}", String.valueOf(blocks))
                        .replace("{max}", String.valueOf(settings.getTourMaxGuests()));
                entries.add(slot -> put(page, slot, head(other,
                        messages.getMessage("menu-tour-candidate").replace("{player}", name), lines(lore)),
                        click -> later(() -> {
                            tour.invite(player, other, name);
                            openTour(player, 0);
                        })));
            }
        }
        int pages = Math.max(1, (entries.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Inventory inventory = create(page, LIST, messages.getMessage("menu-tour-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < entries.size(); slot++) {
            entries.get(number * ENTRIES + slot).accept(slot);
        }
        if (entries.isEmpty() && plugin.isInCameraMode(player)) {
            // How close one has to be: out of camera mode to the guide's
            // camera, in camera mode camera to camera.
            double start = settings.getTourStartDistance();
            put(page, 22, Material.GRAY_DYE, messages.getMessage("menu-tour-empty"),
                    lines(messages.getMessage(start > 0.0 ? "menu-tour-empty-lore" : "menu-tour-empty-lore-cam-only")
                            .replace("{start}", String.valueOf(Math.round(start)))
                            .replace("{radius}", String.valueOf(Math.round(settings.getTourRadius())))),
                    click -> { });
        } else if (entries.isEmpty()) {
            put(page, 22, Material.GRAY_DYE, "menu-tour-not-in-cam", click -> { });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-main", click -> later(() -> openMain(player)));
        if (!guests.isEmpty()) {
            put(page, SLOT_NEW, Material.BARRIER, "menu-tour-end", click -> act(player, () -> tour.end(player)));
        }
        viewButton(player, page, SLOT_ADMIN_SORT);
        paging(page, number, pages, n -> openTour(player, n));
        player.openInventory(inventory);
    }

    /**
     * Whether he sees through the eyes of his guide on tours or looks round
     * freely: how it stands under its name; a click switches. Only where the
     * guide's view works at all.
     */
    private void viewButton(Player player, Page page, int slot) {
        CamTour tour = plugin.getTour();
        if (!tour.viewWorks()) {
            return;
        }
        boolean guide = tour.seesThroughGuide(player);
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-tour-view-lore")));
        lore.addAll(lines(messages.getMessage("menu-state").replace("{state}",
                messages.getMessage(guide ? "menu-tour-view-guide" : "menu-tour-view-free"))));
        put(page, slot, item(guide ? Material.ENDER_EYE : Material.SPYGLASS, messages.getMessage("menu-tour-view"),
                lore), click -> later(() -> {
                    tour.toggleView(player);
                    openTour(player, 0);
                }));
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
                || plugin.getTrail().mayUse(player) || plugin.getSpotModeration().mayUse(player);
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
     * camera mode, the players to stop and to ban, the statistics of others,
     * the flights of {@code /cam trail} and the spots of everybody - each where
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
        if (plugin.getSpotModeration().mayUse(player)) {
            adminButton(page, AdminButton.SPOTS, "menu-admin-spots", click -> later(() -> openSpotOwners(player, 0)));
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
        sortPlayers(player, cameras, Player::getName, Player::getUniqueId);
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
        playerSortButton(player, page, () -> openCamList(player, 0));
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

    /** How he has the lists of players sorted: by name, until he picks the last flight. */
    private PlayerSort playerSort(Player admin) {
        return playerSorts.getOrDefault(admin.getUniqueId(), PlayerSort.NAME);
    }

    /** The button that sorts a list of players on the admin pages, by name or by the last flight. */
    private void playerSortButton(Player admin, Page page, Runnable reopen) {
        putSort(page, SLOT_ADMIN_SORT, playerSort(admin).key, PlayerSort.KEYS, chosen -> {
            playerSorts.put(admin.getUniqueId(), PlayerSort.of(chosen));
            reopen.run();
        });
    }

    /**
     * Sorts a list of players the way the admin has it, kept by name
     * otherwise. By the last flight, whoever is in camera mode comes first -
     * the one who started last at the top -, then the others by when their
     * last flight ended, as the statistics counted it; whoever never flew,
     * or was not counted, comes last.
     */
    private <T> void sortPlayers(Player admin, List<T> list, Function<T, String> name, Function<T, UUID> id) {
        list.sort(Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER));
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
        sortPlayers(player, online, Player::getName, Player::getUniqueId);
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
        playerSortButton(player, page, () -> openStatsOf(player, 0));
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
        sortPlayers(player, list, Map.Entry::getValue, Map.Entry::getKey);
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
        playerSortButton(player, page, () -> openPlayers(player, 0));
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
        LongConsumer set = minutes -> draft.minutes = minutes;
        Runnable reopen = () -> openBan(player);
        banUnit(page, 12, "menu-admin-ban-days", draft.minutes, set, reopen, DAY, draft.minutes / DAY, time);
        banUnit(page, 13, "menu-admin-ban-hours", draft.minutes, set, reopen, HOUR, draft.minutes % DAY / HOUR,
                time);
        banUnit(page, 14, "menu-admin-ban-minutes", draft.minutes, set, reopen, 1L, draft.minutes % HOUR, time);
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
     * @param minutes how long the ban lasts now, in minutes, 0 for good
     * @param set     what takes a new length
     * @param reopen  what shows the page anew afterwards
     * @param unit    what one of them is, in minutes
     * @param amount  how many of them the ban has now
     * @param time    how long it lasts altogether, as {@link #banTime} has it
     */
    private void banUnit(Page page, int slot, String key, long minutes, LongConsumer set, Runnable reopen, long unit,
                         long amount, String time) {
        int shiftStep = settings.getBanShiftStep();
        List<String> lore = new ArrayList<>(lines(messages.getMessage("menu-admin-ban-time-lore")
                .replace("{time}", time)));
        lore.addAll(lines(messages.getMessage("menu-admin-ban-time-actions")
                .replace("{step}", String.valueOf(shiftStep))));
        put(page, slot, item(Material.CLOCK, messages.getMessage(key).replace("{amount}", String.valueOf(amount)), lore),
                click -> later(() -> {
                    long step = unit * (click.isShiftClick() ? shiftStep : 1);
                    long next = click.isRightClick() ? minutes - step : minutes + step;
                    if (click.isRightClick() ? next >= 1L : next <= MAX_BAN_MINUTES) {
                        set.accept(next);
                    }
                    reopen.run();
                }));
    }

    /** How long a ban of so many minutes lasts, the way the texts put it: "for 2h 0m 0s", or "for good" for 0. */
    private String banTime(long minutes) {
        return minutes == 0L ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(minutes * 60L));
    }

    // ------------------------------------------------------------ admin: the spots of everybody

    /**
     * The players who have spots - and those kept from something with spots
     * who have none -, online or not: under each how many he has, how many
     * of them are public or shared, and what keeps him. A click shows his
     * spots.
     */
    private void openSpotOwners(Player player, int pageNumber) {
        SpotModeration moderation = plugin.getSpotModeration();
        if (!moderation.mayUse(player)) {
            openAdmin(player);
            return;
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
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
        sortPlayers(player, list, Map.Entry::getValue, Map.Entry::getKey);
        int pages = Math.max(1, (list.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-spots-title"));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < list.size(); slot++) {
            UUID id = list.get(number * ENTRIES + slot).getKey();
            String name = list.get(number * ENTRIES + slot).getValue();
            List<String> lore = spotOwnerLines(id, counts.getOrDefault(id, 0));
            lore.addAll(lines(messages.getMessage("menu-admin-spots-actions")));
            put(page, slot, playerItem(id, name, lore, moderation.banOf(id) != null),
                    click -> later(() -> openSpotsOf(player, id, name, 0)));
        }
        if (list.isEmpty()) {
            put(page, 22, Material.GRAY_DYE, "menu-admin-spots-empty", click -> { });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-admin", click -> later(() -> openAdmin(player)));
        playerSortButton(player, page, () -> openSpotOwners(player, 0));
        paging(page, number, pages, n -> openSpotOwners(player, n));
        player.openInventory(inventory);
    }

    /** How many spots he has, how many of them are public or shared, and what keeps him, as lines under him. */
    private List<String> spotOwnerLines(UUID id, int count) {
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
        lore.addAll(restrictionLines(id));
        return lore;
    }

    /** What keeps him from what with spots, for how long and why, as lines - or that nothing does. */
    private List<String> restrictionLines(UUID id) {
        String text = plugin.getSpotModeration().banText(id);
        return lines(text == null ? messages.getMessage("menu-admin-spots-free")
                : messages.getMessage("menu-admin-spots-restricted").replace("{ban}", text));
    }

    /**
     * A player as an item, online or not: his head while he is online, a
     * skull while he is not. It shines while something keeps him - where the
     * game draws it, never on a head.
     */
    private ItemStack playerItem(UUID id, String name, List<String> lore, boolean glint) {
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
    private void openSpotsOf(Player player, UUID owner, String name, int pageNumber) {
        SpotModeration moderation = plugin.getSpotModeration();
        if (!moderation.mayUse(player)) {
            openAdmin(player);
            return;
        }
        CamSpots spots = plugin.getSpots();
        SpotSort sort = adminSpotSorts.getOrDefault(player.getUniqueId(), SpotSort.DATE);
        List<Spot> list = sorted(spots.spotsOf(owner).values(), sort, spot -> spot, spot -> "", spot -> {
            Share share = spots.shareOf(owner, spot.key());
            return share == null ? 0 : share.likes();
        }, spot -> spots.views(owner, spot), nearFrom(player));
        int pages = Math.max(1, (list.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        Page page = new Page();
        Inventory inventory = create(page, LIST, messages.getMessage("menu-admin-spots-of-title")
                .replace("{player}", name));
        for (int slot = 0; slot < ENTRIES && number * ENTRIES + slot < list.size(); slot++) {
            Spot spot = list.get(number * ENTRIES + slot);
            Share share = spots.shareOf(owner, spot.key());
            boolean open = share != null && share.isEveryone();
            boolean shared = share != null && (!share.getPlayers().isEmpty() || !share.getPending().isEmpty());
            List<String> lore = spotLore(owner, spot);
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
            lore.addAll(lines(messages.getMessage(open || shared ? "menu-admin-spot-actions-shared"
                    : "menu-admin-spot-actions")));
            Material icon = open ? Material.GLOBE_BANNER_PATTERN : shared ? Material.MAP : Material.FILLED_MAP;
            put(page, slot, item(icon, messages.getMessage("menu-spot").replace("{spot}", spot.name()), lore),
                    click -> {
                        if (click.isShiftClick()) {
                            later(() -> {
                                moderation.delete(player, owner, name, spot);
                                openSpotsOf(player, owner, name, pageNumber);
                            });
                        } else if (click.isRightClick()) {
                            act(player, () -> moderation.teleport(player, name, spot));
                        } else if (click.isLeftClick() && (open || shared)) {
                            later(() -> {
                                moderation.takeDown(player, owner, name, spot);
                                openSpotsOf(player, owner, name, pageNumber);
                            });
                        }
                    });
        }
        if (list.isEmpty()) {
            put(page, 22, Material.GRAY_DYE, "menu-admin-spots-none", click -> { });
        }
        put(page, SLOT_BACK, Material.ARROW, "menu-to-spot-owners", click -> later(() -> openSpotOwners(player, 0)));
        boolean restricted = moderation.banOf(owner) != null;
        String key = restricted ? "menu-admin-spot-restrict-change" : "menu-admin-spot-restrict";
        List<String> lore = new ArrayList<>(restrictionLines(owner));
        lore.addAll(lines(messages.getMessage(key + "-lore")));
        put(page, SLOT_NEW, shine(item(Material.IRON_BARS, messages.getMessage(key), lore), restricted),
                click -> later(() -> {
                    spotDrafts.put(player.getUniqueId(), spotDraft(owner, name));
                    openSpotBan(player);
                }));
        putSort(page, SLOT_ADMIN_SORT, sort.key, ADMIN_SPOT_SORTS, chosen -> {
            adminSpotSorts.put(player.getUniqueId(), SpotSort.of(chosen));
            openSpotsOf(player, owner, name, 0);
        });
        paging(page, number, pages, n -> openSpotsOf(player, owner, name, n));
        player.openInventory(inventory);
    }

    /**
     * A new restriction for him - nothing ticked yet, for good, without a
     * reason - or the one he has, to be changed: what it keeps him from, the
     * time it still has, rounded up to whole minutes, and its reason.
     */
    private SpotBanDraft spotDraft(UUID id, String name) {
        SpotBans.Ban now = plugin.getSpotModeration().banOf(id);
        SpotBanDraft draft = new SpotBanDraft(id, name, now != null);
        if (now != null) {
            for (SpotBans.Kind kind : SpotBans.Kind.values()) {
                draft.stages.put(kind, now.stage(kind));
            }
            draft.minutes = now.forGood() ? 0L
                    : Math.max(1L, (now.secondsLeft(System.currentTimeMillis()) + 59L) / 60L);
            draft.reason = now.reason();
        }
        return draft;
    }

    /**
     * What a player may not do with spots, before it is set or changed: for
     * his own spots, the public ones and those shared by name, each on its
     * button, step by step: nothing, no new ones, none at all - not even to
     * look at -; for good or for days, hours and minutes, as on the page of a
     * ban, and why; then the button that sets it. While something keeps him
     * already, the last row has the button that lifts it.
     */
    private void openSpotBan(Player player) {
        SpotBanDraft draft = spotDrafts.get(player.getUniqueId());
        SpotModeration moderation = plugin.getSpotModeration();
        if (draft == null || !moderation.mayUse(player)) {
            openSpotOwners(player, 0);
            return;
        }
        Page page = new Page();
        Inventory inventory = create(page, MEDIUM, messages.getMessage(draft.change
                ? "menu-admin-spot-restrict-change-title" : "menu-admin-spot-restrict-title")
                .replace("{player}", draft.name));
        put(page, 4, playerItem(draft.id, draft.name, restrictionLines(draft.id), draft.change), click -> { });
        for (SpotTab tab : SpotTab.values()) {
            restrictStage(player, page, draft, tab);
        }
        boolean forGood = draft.minutes == 0L;
        List<String> foreverLore = forGood ? lines(messages.getMessage("menu-state-current")) : List.of();
        put(page, 19, shine(item(Material.BEDROCK, messages.getMessage("menu-admin-ban-forever"), foreverLore),
                forGood), click -> later(() -> {
                    draft.minutes = 0L;
                    openSpotBan(player);
                }));
        String time = banTime(draft.minutes);
        LongConsumer set = minutes -> draft.minutes = minutes;
        Runnable reopen = () -> openSpotBan(player);
        banUnit(page, 21, "menu-admin-ban-days", draft.minutes, set, reopen, DAY, draft.minutes / DAY, time);
        banUnit(page, 22, "menu-admin-ban-hours", draft.minutes, set, reopen, HOUR, draft.minutes % DAY / HOUR,
                time);
        banUnit(page, 23, "menu-admin-ban-minutes", draft.minutes, set, reopen, 1L, draft.minutes % HOUR, time);
        String reason = draft.reason.isEmpty() ? messages.getMessage("menu-admin-ban-no-reason") : draft.reason;
        List<String> reasonLore = new ArrayList<>(lines(messages.getMessage("menu-state").replace("{state}", reason)));
        reasonLore.addAll(lines(messages.getMessage("menu-admin-ban-reason-actions")));
        put(page, 25, Material.WRITABLE_BOOK, messages.getMessage("menu-admin-ban-reason"), reasonLore, click -> {
            if (click.isRightClick()) {
                later(() -> {
                    draft.reason = "";
                    openSpotBan(player);
                });
            } else {
                act(player, () -> ask(player, "menu-admin-spot-restrict-reason-question", answer -> {
                    draft.reason = answer;
                    openSpotBan(player);
                }, "{player}", draft.name));
            }
        });
        if (draft.any()) {
            String summary = moderation.whatText(draft.stage(SpotBans.Kind.PRIVATE),
                    draft.stage(SpotBans.Kind.PUBLIC), draft.stage(SpotBans.Kind.SHARED)) + " " + time;
            if (!draft.reason.isEmpty()) {
                summary += messages.getMessage("ban-reason").replace("{reason}", draft.reason);
            }
            put(page, 31, Material.REDSTONE_BLOCK, messages.getMessage(draft.change
                            ? "menu-admin-spot-restrict-change-confirm" : "menu-admin-spot-restrict-confirm"),
                    lines(messages.getMessage("menu-admin-spot-restrict-confirm-lore")
                            .replace("{player}", draft.name).replace("{ban}", summary)),
                    click -> later(() -> {
                        spotDrafts.remove(player.getUniqueId());
                        moderation.ban(player, draft.id, draft.name, draft.stage(SpotBans.Kind.PRIVATE),
                                draft.stage(SpotBans.Kind.PUBLIC), draft.stage(SpotBans.Kind.SHARED), draft.minutes,
                                draft.reason);
                        openSpotsOf(player, draft.id, draft.name, 0);
                    }));
        } else {
            put(page, 31, Material.GRAY_DYE, "menu-admin-spot-restrict-nothing", click -> { });
        }
        if (draft.change) {
            put(page, 35, Material.MILK_BUCKET, messages.getMessage("menu-admin-spot-unrestrict"),
                    lines(messages.getMessage("menu-admin-spot-unrestrict-lore").replace("{player}", draft.name)),
                    click -> later(() -> {
                        spotDrafts.remove(player.getUniqueId());
                        moderation.unban(player, draft.id, draft.name);
                        openSpotsOf(player, draft.id, draft.name, 0);
                    }));
        }
        put(page, 27, Material.ARROW, "menu-to-spots-of", click -> later(() -> {
            spotDrafts.remove(player.getUniqueId());
            openSpotsOf(player, draft.id, draft.name, 0);
        }));
        player.openInventory(inventory);
    }

    /**
     * The button of one kind of spots on the page of a restriction, with the
     * icon of its tab: the steps it can keep him at, the one it does marked -
     * it shines while it keeps him at all -; a left-click goes one step
     * further, after the last back to nothing, a right-click one back.
     */
    private void restrictStage(Player player, Page page, SpotBanDraft draft, SpotTab tab) {
        SpotBans.Kind kind = SpotBans.Kind.valueOf(tab.name());
        SpotBans.Stage now = draft.stage(kind);
        String what = "menu-admin-spot-restrict-" + kind.key;
        List<String> lore = new ArrayList<>(lines(messages.getMessage(what + "-lore")));
        for (SpotBans.Stage stage : SpotBans.Stage.values()) {
            String label = messages.getMessage(stage == SpotBans.Stage.FREE ? "menu-admin-spot-restrict-free"
                    : "spot-restriction-" + kind.key + "-" + stage.key);
            lore.addAll(lines(messages.getMessage(stage == now ? "menu-admin-spot-restrict-stage-now"
                    : "menu-admin-spot-restrict-stage").replace("{stage}", label)));
        }
        lore.addAll(lines(messages.getMessage("menu-admin-spot-restrict-steps")));
        put(page, RESTRICT_SLOTS[kind.ordinal()], shine(item(tab.icon, messages.getMessage(what), lore),
                now != SpotBans.Stage.FREE), click -> later(() -> {
                    draft.stages.put(kind, now.next(click.isRightClick()));
                    openSpotBan(player);
                }));
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
        if (playerSort(player) == PlayerSort.LAST_FLIGHT) {
            // The flights of a player are kept newest first; one still going
            // is the newest of all.
            names.sort(Comparator.comparing((String name) -> !trail.flightsOf(name).isEmpty()
                            && trail.flightsOf(name).get(0).flying()).reversed()
                    .thenComparing(Comparator.comparingLong((String name) -> trail.flightsOf(name).isEmpty() ? 0L
                            : trail.flightsOf(name).get(0).startedAt()).reversed()));
        }
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
        playerSortButton(player, page, () -> openTrails(player, 0));
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

    /**
     * The head of a player online, with a name and lines of the menu - a
     * plain one while the server has no skin of his, see {@link PlayerSkins}.
     */
    private static ItemStack head(Player owner, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            PlayerSkins.putOn(skull, owner);
            head.setItemMeta(skull);
        }
        return labelled(head, name, lore);
    }

    // ------------------------------------------------------------ questions in the chat

    /**
     * Asks for the name of a new spot in the chat; the next line he writes is
     * it. A public one is made public once it is saved.
     */
    private void askName(Player player, boolean makePublic) {
        if (makePublic && !plugin.getSpotModeration().checkNew(player, SpotBans.Kind.PUBLIC)) {
            return;
        }
        ask(player, makePublic ? "menu-spot-name-public" : "menu-spot-name", name -> {
            Spot spot = plugin.getSpots().set(player, name);
            if (spot != null && makePublic) {
                plugin.getSpots().makePublic(player, spot, true);
            }
        });
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
        spotDrafts.remove(event.getPlayer().getUniqueId());
        adminSpotSorts.remove(event.getPlayer().getUniqueId());
        spotSorts.remove(event.getPlayer().getUniqueId());
        playerSorts.remove(event.getPlayer().getUniqueId());
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
        paging(page, number, pages, open, SLOT_PREVIOUS, SLOT_NEXT);
    }

    /** The buttons to the page before and after, in other slots than usual. */
    private void paging(Page page, int number, int pages, Consumer<Integer> open, int previous, int next) {
        if (number > 0) {
            put(page, previous, Material.ARROW, "menu-previous", click -> later(() -> open.accept(number - 1)));
        }
        if (number < pages - 1) {
            put(page, next, Material.ARROW, "menu-next", click -> later(() -> open.accept(number + 1)));
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
