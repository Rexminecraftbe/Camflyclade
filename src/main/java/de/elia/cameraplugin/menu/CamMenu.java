package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.body.PlayerSkins;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.spot.Share;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import de.elia.cameraplugin.spot.SpotLook;
import de.elia.cameraplugin.tour.TourBans;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.logging.Level;

/**
 * {@code /cam menu}: what camera mode offers, in a window to click instead of
 * commands to type - starting and ending it, {@code back}, {@code resume},
 * the spots of {@code /cam spot} - private, public and shared with him, each
 * on a tab of its own -, the tour of {@code /cam follow}, {@code photo},
 * {@code time}, {@code weather}, {@code nightvision} and the own
 * {@code stats} - the time, the weather and the night vision stepped through
 * on their button, a click on to the next, a right-click back to the one
 * before -; and for
 * admins a page of their own: who is in camera mode
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
 *
 * <p>With {@code menu.live-update} a page that is open shows what changes
 * while it is - an invitation that comes, a player who comes close, how long
 * somebody is in camera mode - without being opened anew: it is drawn anew in
 * the same window by itself, see {@link #refresh}, the lists in the order they
 * had, see {@link #list}. What an admin is setting up - a ban, a
 * restriction - stays as he leaves it.</p>
 *
 * <p>The pages themselves are classes of their own, one for each group:
 * {@link MainPage}, {@link SpotPages}, {@link NewSpotPages},
 * {@link FollowPage}, {@link AdminPages}, {@link SpotAdminPages},
 * {@link FollowAdminPages} and {@link TrailPages}. This class has what they
 * share: drawing a page and showing it ({@link #page}, {@link #show},
 * {@link #list}), drawing it anew by itself, what is being set up on a page,
 * the questions in the chat, the clicks, and the windows of Bedrock.</p>
 */
public final class CamMenu implements Listener {

    /**
     * The sizes of the windows: three rows for the main page and the small
     * ones, six for the lists - on Bedrock six for every page, see
     * {@link #page}, a small one spread over them, {@link #SMALL_ON_LARGE}.
     */
    static final int SMALL = 27;
    static final int LIST = 54;
    /**
     * The size of the pages of a restriction: four rows, for what it keeps
     * him from as well - on Bedrock six, like every page there, see
     * {@link #page}: a chest of four Bedrock does not have. Its rows are
     * spread over them, {@link #MEDIUM_ON_LARGE}.
     */
    static final int MEDIUM = 36;
    /** Where the page of a restriction has the buttons of his own spots, the public ones and the shared ones. */
    static final int[] RESTRICT_SLOTS = {11, 13, 15};

    /** The slots of a list page that hold entries; the last row holds the buttons to go on. */
    static final int ENTRIES = 45;
    static final int SLOT_BACK = 45;
    static final int SLOT_PREVIOUS = 48;
    static final int SLOT_NEW = 49;
    static final int SLOT_NEXT = 50;
    /** The button back to the main page on the small pages. */
    static final int SLOT_SMALL_BACK = 22;
    /**
     * The last row of the pages of the spots: after the button back the page
     * before, the bell of the guestbook next to the tabs, the tabs, the order
     * next to them, the page after, and on the right the button for a new
     * spot.
     */
    static final int SLOT_SPOT_PREVIOUS = 46;
    static final int SLOT_SPOT_GUESTBOOK = 47;
    static final int SLOT_SPOT_NEXT = 52;
    static final int SLOT_SPOT_SORT = 51;
    static final int SLOT_SPOT_NEW = 53;
    /** The button that sorts a list of players on the admin pages. */
    static final int SLOT_ADMIN_SORT = 53;
    /**
     * The last row of the page that shares a spot: the beacon that makes it
     * public in the middle, between the pages before and after, and the
     * button that sorts the players in the corner, as on the admin pages.
     */
    static final int SLOT_SHARE_PUBLIC = 49;
    static final int SLOT_SHARE_SORT = 53;
    /**
     * Everybody on the admin pages of the players, the spots and the tours:
     * a beacon in the middle of the last row, on every page of the list.
     */
    static final int SLOT_ADMIN_EVERYBODY = 49;
    /**
     * The button on the page of the tour that invites everybody he could
     * invite at once, a beacon in the corner of the last row. Where the
     * button for a new spot is on the pages of the spots.
     */
    static final int SLOT_EVERYBODY = 53;
    /** The button on the page of the tour that switches between the guide's view and looking round freely. */
    static final int SLOT_TOUR_VIEW = 49;
    /** The button on the page of the tour that ends it, or what keeps him from leading or following. */
    static final int SLOT_TOUR_END = 47;

    /** What a button that only shows something does on a click: nothing. */
    static final Consumer<ClickType> NOTHING = click -> { };

    /**
     * Where the choices of a button stand on the page that offers them on
     * Bedrock, see {@link #openPicks}, a page of six rows like every page
     * there: the two rows in the middle first, then the rows above and below
     * them - the button itself stands on top, the way back at the bottom.
     */
    static final int[] PICK_SLOTS = {18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
            9, 10, 11, 12, 13, 14, 15, 16, 17, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    /** On the page of the choices: the button they belong to, and the way back. */
    static final int SLOT_PICK_BUTTON = 4;
    static final int SLOT_PICK_BACK = 49;
    /**
     * Where the rows of a page of three rows - the main page and the small
     * ones - go in the window of six rows every page has on Bedrock, see
     * {@link #page}: the first stays on top, the second comes into the
     * third, and the last, with the way back and what the page does, goes
     * to the bottom, where the lists have their buttons to go on.
     */
    static final int[] SMALL_ON_LARGE = {0, 2, 5};
    /** The same for a page of four rows, a restriction: the two in the middle into the middle. */
    static final int[] MEDIUM_ON_LARGE = {0, 2, 3, 5};
    /**
     * How long after a click on Bedrock it is looked whether the menu showed
     * anything, in ticks, see {@link #afterwards}: a button acts a tick after
     * the click, and the page it opens comes out of that.
     */
    static final long AFTERWARDS_TICKS = 3L;
    /** How long a line under a button of a window of Bedrock may be, in letters, before it is cut. */
    static final int FORM_LINE = 40;
    /**
     * How long a window of Bedrock waits after the chest of the menu was
     * closed for it, in ticks, see {@link #whenChestShut}: Bedrock is to have
     * the chest off the screen before the window comes.
     */
    static final long WINDOW_AFTER_CHEST_TICKS = 5L;

    /** How long the menu waits for an answer in the chat, in milliseconds. */
    static final long ANSWER_WAIT = 60_000L;

    /** The word that calls off an answer in the chat. */
    static final String CANCEL = "cancel";

    /** A day and an hour in minutes, for the buttons of the time of a ban. */
    static final long DAY = 24L * 60L;
    static final long HOUR = 60L;

    /** The longest ban the buttons of the time add up to, in minutes: 999 days. */
    static final long MAX_BAN_MINUTES = 999L * DAY;

    /** How often the open pages are looked at, whether one is to be drawn anew by itself, in ticks. */
    static final long LIVE_TICKS = 20L;
    /**
     * How often a page is drawn anew by itself, in milliseconds: one that
     * counts seconds - how long somebody is in camera mode, how far away he
     * is, how long an invitation still holds - every second.
     */
    static final long EVERY_SECOND = 1_000L;
    /**
     * The same for a page that reads the files of players who are offline -
     * the lists of the admin page with the players who are banned or
     * restricted, and their spots -: every five seconds, so that a page left
     * open does not keep reading them.
     */
    static final long EVERY_FIVE_SECONDS = 5_000L;
    /**
     * How long a click on a slot is turned away after the page, drawn anew by
     * itself, put something else there - an entry that came, one that is
     * gone, a button in place of another -, in milliseconds: the click was
     * meant for what stood there before, which the client may still have
     * shown, or which the player had his mouse on already.
     */
    static final long CLICK_GUARD = 750L;


    final CameraPlugin plugin;
    final CamSettings settings;
    final Messages messages;
    /** Who is asked for something in the chat, until when and what to do with it. Read from the chat thread. */
    final Map<UUID, Question> questions = new ConcurrentHashMap<>();

    /**
     * A question in the chat: until when the answer counts, what it does - on
     * the main thread -, and what calling it off does besides saying so,
     * {@code null} for nothing.
     */
    record Question(long until, Consumer<String> answer, Runnable cancelled) {
    }

    /** The bans being set up on their page, by the id of the admin. */
    final Map<UUID, BanDraft> drafts = new HashMap<>();

    /** The restrictions of the spots being set up on their page, by the id of the admin. */
    final Map<UUID, SpotBanDraft> spotDrafts = new HashMap<>();
    /** The restrictions of the tours being set up on their page, by the id of the admin. */
    final Map<UUID, TourBanDraft> tourDrafts = new HashMap<>();

    /** The new spots being set up on their page before they are saved, by the id of their owner. */
    final Map<UUID, NewSpot> newSpots = new HashMap<>();

    /** How each player has the tabs of the spots sorted, while he is online. */
    final Map<UUID, Map<SpotTab, SpotSort>> spotSorts = new HashMap<>();

    /** How each admin has the lists of players sorted, while he is online. */
    final Map<UUID, PlayerSort> playerSorts = new HashMap<>();

    /** The orders of the spots of a player on the admin page, the first being where it starts. */
    static final List<String> ADMIN_SPOT_SORTS = List.of("date", "name", "likes", "views", "near");

    /** How each admin has the spots of a player sorted on the admin page, while he is online. */
    final Map<UUID, SpotSort> adminSpotSorts = new HashMap<>();

    /**
     * The orders of the page that shares a spot, the first being where it
     * starts: those it is shared with first, by name, the nearest first.
     */
    static final List<String> SHARE_SORTS = List.of("shared", "name", "near");

    /** How each player has the page that shares a spot sorted, while he is online. */
    final Map<UUID, String> shareSorts = new HashMap<>();

    /**
     * How often the menu has shown each player something since he joined - a
     * page, a question, a window of Bedrock - or closed for something a
     * button does, see {@link #afterwards}.
     */
    final Map<UUID, Integer> shownCount = new HashMap<>();
    /** The page to be opened a moment from now on Bedrock, by player, see {@link #openWindow}. */
    final Map<UUID, Page> opening = new HashMap<>();

    /** What draws the open pages anew by themselves, see {@link #start}. */
    BukkitTask live;
    /** Whether a page is being drawn anew by itself right now, and not after a click. */
    boolean refreshing;

    /**
     * The tabs of the spots: all his own, the public ones of everybody and
     * those shared with him - each with its icon, its slot in the last row
     * and the orders it can be sorted in, the first being where it starts.
     */
    enum SpotTab {
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
    enum SpotSort {
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

    /** The orders of the lists of players on the admin pages: by name, the last flight first, or the nearest first. */
    enum PlayerSort {
        NAME("name"), LAST_FLIGHT("last-flight"), NEAR("near");

        static final List<String> KEYS = List.of("name", "last-flight", "near");

        final String key;

        PlayerSort(String key) {
            this.key = key;
        }

        static PlayerSort of(String key) {
            for (PlayerSort sort : values()) {
                if (sort.key.equals(key)) {
                    return sort;
                }
            }
            return NAME;
        }
    }

    /** Whom the page of a player is about: one player, by his id and name, or everybody. */
    record Target(UUID id, String name) {

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
    static final class BanDraft {
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
     * A restriction being set up on its page - of the spots or of the
     * tours, of one player or of everybody -: whom, for how long and why - a
     * new one, or the one that holds already, to be changed. What it keeps
     * him from is up to the kind of restriction.
     */
    abstract static class RestrictDraft {
        /** The player, or {@code null} for everybody. */
        final UUID id;
        final String name;
        /** Whether something keeps him already, and the page changes it. */
        final boolean change;
        /** How long, in minutes; 0 for good. */
        long minutes;
        String reason = "";

        RestrictDraft(UUID id, String name, boolean change) {
            this.id = id;
            this.name = name;
            this.change = change;
        }

        boolean everybody() {
            return id == null;
        }

        /** Whether it keeps him from anything at all. */
        abstract boolean any();
    }

    /** A restriction of the spots: how far it keeps him from each kind of spots. */
    static final class SpotBanDraft extends RestrictDraft {
        final Map<SpotBans.Kind, SpotBans.Stage> stages = new EnumMap<>(SpotBans.Kind.class);

        SpotBanDraft(UUID id, String name, boolean change) {
            super(id, name, change);
        }

        /** How far it keeps him from this kind of spots. */
        SpotBans.Stage stage(SpotBans.Kind kind) {
            return stages.getOrDefault(kind, SpotBans.Stage.FREE);
        }

        @Override
        boolean any() {
            return stages.values().stream().anyMatch(stage -> stage != SpotBans.Stage.FREE);
        }
    }

    /** A restriction of the tours: whether it keeps him from leading them, and from taking part. */
    static final class TourBanDraft extends RestrictDraft {
        final Set<TourBans.What> kept = EnumSet.noneOf(TourBans.What.class);

        TourBanDraft(UUID id, String name, boolean change) {
            super(id, name, change);
        }

        @Override
        boolean any() {
            return !kept.isEmpty();
        }
    }

    /**
     * A new spot being set up on its page, {@code viewpoints.create-page}:
     * where it is - where its owner was when he named it -, which way it
     * looks and what it shows, which are fixed; its name, its icon, whether
     * it is to be public and whom it is to be offered to, which the page
     * changes until it is saved - only then is any of it shared.
     */
    static final class NewSpot {
        String name;
        final Location where;
        final SpotLook look;
        String icon;
        boolean open;
        /** Whom it is offered to once it is saved, by id, with their names, in the order he picked them. */
        final Map<UUID, String> invited = new LinkedHashMap<>();

        NewSpot(String name, Location where, SpotLook look, String icon) {
            this.name = name;
            this.where = where;
            this.look = look;
            this.icon = icon;
        }

        /** The spot as it would be saved now, to show it. */
        Spot spot() {
            return Spot.at(name, where, System.currentTimeMillis(), look, icon);
        }
    }

    /**
     * One window of the menu, and what each of its slots does when clicked.
     *
     * <p>While it is open, it is drawn anew in place - by itself every
     * {@link #every} with {@code menu.live-update}, and after a click that
     * changes what it shows -: the same window, emptied and filled again, so
     * that only what changed reaches the client and nothing flickers.</p>
     */
    public static final class Page implements InventoryHolder {
        final Map<Integer, Consumer<ClickType>> buttons = new HashMap<>();
        /**
         * What a button does besides a plain click - a right-click, a click
         * with shift -, or the choices it steps through, for whoever cannot
         * click that way: on Bedrock, see {@link CamMenu#openPicks}. Only for
         * such a player, and only for a button that does more than one thing.
         */
        final Map<Integer, List<Pick>> picks = new HashMap<>();
        /**
         * A window of Bedrock of its own for a button with {@link #picks},
         * in place of the list of them, see {@link CamMenu#openPicks}: it
         * sends it and says whether it went out.
         */
        final Map<Integer, BooleanSupplier> windows = new HashMap<>();
        /**
         * Whether it is made for a player on Bedrock, which draws some items
         * otherwise than Java does, see {@link CamMenu#forBedrock}.
         */
        boolean bedrock;
        /** Whether the maps of 26.3 stay themselves on Bedrock, {@code bedrock.map-icons}, see {@link CamMenu#forBedrock}. */
        boolean bedrockMaps;
        Inventory inventory;
        /**
         * Its title, which a page drawn anew in place keeps. On Bedrock the
         * name of the page only: the window keeps the title of the menu, see
         * {@link CamMenu#page}.
         */
        String title;
        /** What draws it anew as it stands then, by itself; {@code null} for a page that is not. */
        Runnable again;
        /** How long it waits between two times it is drawn anew by itself, in milliseconds. */
        long every;
        /** When it is drawn anew by itself next, in milliseconds. */
        long next;
        /** The list it shows, in the order it shows it; {@code null} for a page with none. */
        Listing listing;
        /** The list of the page that was open before it was drawn, for the order of its own; see {@link #list}. */
        Listing earlier;
        /** What each slot held before the page was drawn anew in place, see {@link #identity}; {@code null} after. */
        String[] before;
        /** Until when a click on a slot is turned away, by slot: something else came there by itself. */
        final Map<Integer, Long> guarded = new HashMap<>();
        /**
         * Where each row of the page as it is laid out goes in its window, by
         * row: for a page of three or four rows in the window of six every
         * page has on Bedrock, see {@link CamMenu#SMALL_ON_LARGE}; {@code null}
         * where every slot stays where it is.
         */
        int[] rows;

        /** The slot of its window for a slot of the page as it is laid out, see {@link #rows}. */
        int at(int slot) {
            return rows == null ? slot : rows[slot / 9] * 9 + slot % 9;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * A list as a page shows it: which list it is, its entries in the order
     * they stand, and what each was called the last time it was shown - what
     * stands in its place once it is gone.
     *
     * @param list which list, such as {@code tour}; the same list on another
     *             page number is the same one
     */
    record Listing(String list, List<Object> order, Map<Object, String> names) {
    }

    /**
     * One entry of a list: what it is - a player, a spot -, which keeps it in
     * its place while the page is drawn anew, and what puts it into a slot.
     */
    record Row(Object key, Consumer<Integer> put) {
    }

    /** Where a list stands once its entries are in: on which of how many pages, and whether it has none at all. */
    record Listed(int number, int pages, boolean empty) {
    }

    /**
     * One of the things a button does, for a player on Bedrock who picks it
     * from a list instead of clicking a certain way, see {@link #openPicks}.
     *
     * @param label   what it is called, with colour codes
     * @param current whether it is the choice that holds now, of a button
     *                that steps through choices
     * @param action  what picking it does: what the click it stands for does
     */
    record Pick(String label, boolean current, Runnable action) {
    }

    /** The groups of pages, each a class of its own. */
    final MainPage mainPage;
    final SpotPages spotPages;
    final NewSpotPages newSpotPages;
    final FollowPage followPage;
    final AdminPages adminPages;
    final SpotAdminPages spotAdminPages;
    final FollowAdminPages followAdminPages;
    final TrailPages trailPages;

    public CamMenu(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        mainPage = new MainPage(this);
        spotPages = new SpotPages(this);
        newSpotPages = new NewSpotPages(this);
        followPage = new FollowPage(this);
        adminPages = new AdminPages(this);
        spotAdminPages = new SpotAdminPages(this);
        followAdminPages = new FollowAdminPages(this);
        trailPages = new TrailPages(this);
    }

    /** Whether this window is one of the menu, which camera mode lets open. */
    public static boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Page;
    }

    /** Whether {@code /cam menu} opens. */
    public boolean isOpenable() {
        return settings.menu().access().allowsMenu();
    }

    /** {@code /cam menu}: the main page. */
    public void command(Player player) {
        if (!isOpenable()) {
            messages.sendConfiguredMessage(player, "menu-off");
            return;
        }
        mainPage.openMain(player);
    }

    // ------------------------------------------------------------------ live

    /**
     * Starts drawing the open pages anew by themselves, with
     * {@code menu.live-update}: each where it waited long enough - see
     * {@link #EVERY_SECOND} and {@link #EVERY_FIVE_SECONDS} -, as it stands
     * then, in the window that is open. The setting is read each time, so a
     * reload switches it on and off.
     */
    public void start() {
        if (live == null) {
            live = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, LIVE_TICKS, LIVE_TICKS);
        }
    }

    public void onDisable() {
        if (live != null) {
            live.cancel();
            live = null;
        }
    }

    /**
     * Draws each open page anew that waited long enough. One that fails is
     * left as it is from then on, said once in the console, instead of
     * failing every second.
     */
    void refresh() {
        if (!settings.menu().liveUpdate()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Page page = openPage(player);
            if (page == null || page.again == null || now < page.next) {
                continue;
            }
            Runnable again = page.again;
            refreshing = true;
            try {
                again.run();
            } catch (RuntimeException ex) {
                page.again = null;
                plugin.getLogger().log(Level.WARNING, "Could not draw the menu of " + player.getName() + " anew", ex);
            } finally {
                refreshing = false;
            }
        }
    }

    /** The page of the menu he has open, or {@code null} while it is none. */
    static Page openPage(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof Page page ? page : null;
    }

    /**
     * The page to draw: the one he has open, emptied, where it has the same
     * size and title - it is drawn anew in place -, else a new one, which
     * {@link #show} opens. Either way, the list of the page that was open
     * goes along, for the order of its own, see {@link #list}.
     */
    Page page(Player player, int size, String title) {
        Page open = openPage(player);
        Listing earlier = open == null ? null : open.listing;
        // On Bedrock every page of the menu goes into one window of six rows,
        // drawn anew in place from page to page: Geyser shows a chest there
        // as one of its own put down for it, and one window opened in the
        // place of another now and then closes again at once, or never opens
        // - a small chest after a large one and back, too. Bedrock cannot
        // rename a window that is open, so it keeps the title of the menu. A
        // page of three or four rows is spread over the six, see
        // SMALL_ON_LARGE.
        boolean bedrock = bedrock(player);
        int rows = bedrock ? LIST : size;
        Page page;
        if (open != null && open.inventory.getSize() == rows && (bedrock || open.title.equals(title))) {
            page = open;
            page.before = new String[rows];
            for (int slot = 0; slot < rows; slot++) {
                page.before[slot] = identity(page.inventory.getItem(slot));
            }
            page.inventory.clear();
            page.buttons.clear();
            page.picks.clear();
            page.windows.clear();
        } else {
            page = new Page();
            page.inventory = Bukkit.createInventory(page, rows, bedrock ? messages.getMessage("menu-title") : title);
        }
        page.title = title;
        page.earlier = earlier;
        page.listing = null;
        page.again = null;
        page.bedrock = bedrock;
        page.bedrockMaps = settings.bedrock().mapIcons();
        page.rows = !bedrock ? null : switch (size) {
            case SMALL -> SMALL_ON_LARGE;
            case MEDIUM -> MEDIUM_ON_LARGE;
            default -> null;
        };
        return page;
    }

    /** {@link #show(Player, Page, long, Runnable)} for a page that is only drawn anew after a click. */
    void show(Player player, Page page) {
        show(player, page, 0L, null);
    }

    /**
     * Shows a page that is drawn: opens it, unless it was drawn in place. A
     * slot that holds something else after the page was drawn anew by itself
     * turns clicks away for {@link #CLICK_GUARD}, see {@link #onClick}; so
     * does every slot of a new window opened that way - the page he had open
     * became another, a tour that ended, say.
     *
     * @param every how long it waits until it is drawn anew by itself, in milliseconds
     * @param again what draws it anew as it stands then; {@code null} for a page that is not drawn anew by itself
     */
    void show(Player player, Page page, long every, Runnable again) {
        long now = System.currentTimeMillis();
        page.again = again;
        page.every = every;
        // Half a round early: the rounds of refresh() come only every
        // LIVE_TICKS, and one that comes a moment too soon would leave the
        // page for a whole round more.
        page.next = now + every - LIVE_TICKS * 25L;
        page.earlier = null;
        String[] before = page.before;
        page.before = null;
        if (!refreshing) {
            shown(player);
        }
        // On Bedrock as a window of its own, where that goes: it is drawn
        // anew only after a click, a window cannot be changed while it is open.
        if (before == null && plugin.getBedrock().menuAsForms(player) && sendForm(player, page)) {
            return;
        }
        if (before == null) {
            openWindow(player, page);
        }
        if (!refreshing) {
            return;
        }
        for (int slot = 0; slot < page.inventory.getSize(); slot++) {
            if (before == null || !before[slot].equals(identity(page.inventory.getItem(slot)))) {
                page.guarded.put(slot, now + CLICK_GUARD);
            }
        }
    }

    /**
     * Puts the entries of a list into the first {@value #ENTRIES} slots of a
     * page, a page of them at a time.
     *
     * <p>While the same list stays open, its entries keep their order - also
     * on the other pages of it, and after a click -: what came since stands
     * after them, so that nothing moves under the mouse. One that is gone
     * keeps its place, greyed out, while the page is drawn anew by itself;
     * after a click, or when the list is opened anew, it goes and the rest
     * moves up. A list opened anew, in another order or on another tab, is in
     * the order it comes in.</p>
     *
     * @param list       which list it is, such as {@code tour}: the same one on another page number is the same
     * @param rows       its entries, in their own order
     * @param pageNumber which page of it, from 0; one past the last shows the last
     */
    Listed list(Page page, String list, List<Row> rows, int pageNumber) {
        Map<Object, Row> fresh = new LinkedHashMap<>();
        for (Row row : rows) {
            Object key = row.key();
            for (int n = 2; fresh.containsKey(key); n++) {
                // Two entries that are the same thing each keep a place of their own.
                key = List.of(row.key(), n);
            }
            fresh.put(key, new Row(key, row.put()));
        }
        Listing earlier = page.earlier != null && page.earlier.list().equals(list) ? page.earlier : null;
        List<Row> ordered = new ArrayList<>(fresh.size());
        Map<Object, String> names = new HashMap<>();
        if (earlier != null) {
            for (Object key : earlier.order()) {
                Row row = fresh.remove(key);
                if (row != null) {
                    ordered.add(row);
                } else if (refreshing) {
                    String name = earlier.names().get(key);
                    ordered.add(new Row(key, slot -> put(page, slot, gone(name), NOTHING)));
                    // What it was called, not what stands in its place.
                    names.put(key, name == null ? "" : name);
                }
            }
        }
        ordered.addAll(fresh.values());
        int pages = Math.max(1, (ordered.size() + ENTRIES - 1) / ENTRIES);
        int number = Math.min(Math.max(0, pageNumber), pages - 1);
        List<Object> order = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            Row row = ordered.get(index);
            order.add(row.key());
            int slot = index - number * ENTRIES;
            if (slot >= 0 && slot < ENTRIES) {
                row.put().accept(slot);
                names.putIfAbsent(row.key(), displayName(page.inventory.getItem(slot)));
            } else if (!names.containsKey(row.key()) && earlier != null && earlier.names().containsKey(row.key())) {
                names.put(row.key(), earlier.names().get(row.key()));
            }
        }
        page.listing = new Listing(list, order, names);
        return new Listed(number, pages, ordered.isEmpty());
    }

    /**
     * What stands in the place of an entry that is gone while the page is
     * open, see {@link #list}: what it was called, greyed out, and that it is
     * no longer there. A click does nothing.
     */
    ItemStack gone(String name) {
        String was = name == null ? "" : ChatColor.stripColor(name);
        if (was.isEmpty()) {
            // Never shown, so without a name: what it says is name enough.
            return item(Material.GRAY_STAINED_GLASS_PANE, messages.getMessage("menu-gone-lore"), List.of());
        }
        return item(Material.GRAY_STAINED_GLASS_PANE, messages.getMessage("menu-gone").replace("{name}", was),
                lines(messages.getMessage("menu-gone-lore")));
    }

    /**
     * What a slot holds, as far as a click on it goes: the kind of item and
     * its name - empty for nothing. What is written under it may change
     * without the click meaning anything else, a countdown or a distance.
     */
    static String identity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        return item.getType().name() + "|" + displayName(item);
    }

    /** The name an item of the menu was given, or an empty one. */
    static String displayName(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "";
    }

    // ------------------------------------------------------------------ pages

    /** The word of {@code /cam nightvision} for a mode. */
    static String nightVisionWord(NightVisionMode mode) {
        return switch (mode) {
            case ALWAYS -> "on";
            case OFF -> "off";
            case DARK -> "dark";
        };
    }

    /** One of the choices a button steps through: the word of its command, and how the button names it. */
    record Choice(String word, String name) {
    }

    /** The choices a button steps through, in their order, and which of them holds now. */
    record Choices(List<Choice> all, int now) {

        /** The next choice, or with {@code back} the one before; after the last comes the first again. */
        Choice step(boolean back) {
            int size = all.size();
            return all.get(Math.floorMod(now + (back ? -1 : 1), size));
        }
    }

    /** A spot shared by its owner, as an entry of a list: the same spot offered and accepted is the same one. */
    static String shareKey(Share share) {
        return share.getOwner() + "/" + share.getSpot().key();
    }


    /** Whom he may share a spot with on its page: every other player online he can see, by name. */
    static Map<UUID, String> sharePeople(Player player) {
        Map<UUID, String> people = new LinkedHashMap<>();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (Player other : online) {
            if (!other.equals(player) && player.canSee(other)) {
                people.put(other.getUniqueId(), other.getName());
            }
        }
        return people;
    }

    /**
     * Where a player comes when those a spot is shared with come first: who
     * accepted it, then who it is offered to - or, a new spot, to be offered
     * once it is saved -, then the rest.
     *
     * @param state how it stands with him, as {@link SpotPages#sharePage} has it
     */
    static int shareRank(String state) {
        return switch (state) {
            case "on" -> 0;
            case "offered", "invited" -> 1;
            default -> 2;
        };
    }

    /**
     * How far another player is from him, squared - endlessly far while the
     * other is offline, in another world or hidden from him. Each of them
     * where he is - in camera mode where his camera is -, as on the page of
     * the tour.
     *
     * @param other the other player, {@code null} while he is offline
     */
    static double distance(Player viewer, Player other) {
        if (other == null || !other.getWorld().equals(viewer.getWorld())
                || !other.equals(viewer) && !viewer.canSee(other)) {
            return Double.MAX_VALUE;
        }
        return other.getLocation().distanceSquared(viewer.getLocation());
    }

    // ------------------------------------------------------------ a new spot, its icon and its options

    /** One button of the middle row of a page of three rows, see {@link #putRow}. */
    record RowButton(ItemStack item, Consumer<ClickType> action) {
    }

    /** Buttons in the middle row of a page of three rows, spread out from its middle. */
    static void putRow(Page page, List<RowButton> row) {
        int[] slots = switch (row.size()) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{11, 15};
            case 3 -> new int[]{11, 13, 15};
            case 4 -> new int[]{10, 12, 14, 16};
            default -> new int[]{9, 11, 13, 15, 17};
        };
        for (int index = 0; index < row.size() && index < slots.length; index++) {
            put(page, slots[index], row.get(index).item(), row.get(index).action());
        }
    }

    /**
     * Spots in an order: the newest first - those without a date, saved
     * before dates were kept, last -, by name, by owner, the most liked
     * first, the most viewed first, or the nearest first - those in another
     * world last. What is the same in one order goes by name, then by owner.
     *
     * @param views how often each was looked at, see {@link de.elia.cameraplugin.spot.SpotViews}
     * @param from  what "nearest first" measures from, see {@link SpotPages#nearFrom}
     */
    static <T> List<T> sorted(Collection<T> items, SpotSort sort, Function<T, Spot> spot,
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
    static double distance(Spot spot, Location from) {
        if (from.getWorld() == null || !from.getWorld().getName().equals(spot.world())) {
            return Double.MAX_VALUE;
        }
        double dx = spot.x() - from.getX();
        double dy = spot.y() - from.getY();
        double dz = spot.z() - from.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------ admin pages

    /** How many days, hours or minutes a ban of so many minutes has: of {@code unit}, in minutes. */
    static long amount(long minutes, long unit) {
        return unit == DAY ? minutes / DAY : unit == HOUR ? minutes % DAY / HOUR : minutes % HOUR;
    }


    // ------------------------------------------------------------ admin: the spots of everybody

    /** The time a restriction that holds still has, rounded up to whole minutes, and its reason, into its draft. */
    static void keepTime(RestrictDraft draft, boolean forGood, long secondsLeft, String reason) {
        draft.minutes = forGood ? 0L : Math.max(1L, (secondsLeft + 59L) / 60L);
        draft.reason = reason;
    }

    // ------------------------------------------------------------ admin: the tours

    /** The players online he can see, by name, for the admin pages. */
    static List<Player> onlineSorted(Player viewer) {
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
    static ItemStack head(Player owner, String name, List<String> lore) {
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
    void askName(Player player, boolean makePublic) {
        // Asked before the name: nobody types one only to hear that it does not go here.
        if (!plugin.getSpots().maySetHere(player)) {
            return;
        }
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
     * On Bedrock, under {@code bedrock.menu: forms} and {@code mixed}, in a
     * window instead, see {@link #askInForm}, where that goes.
     *
     * @param key    the question, with {@code {cancel}} for the word that calls it off
     * @param answer what the answer does, on the main thread
     */
    void ask(Player player, String key, Consumer<String> answer, String... fills) {
        ask(player, key, answer, (Runnable) null, fills);
    }

    /**
     * {@link #ask(Player, String, Consumer, String...)}, and when he calls it
     * off, something besides saying so - the page he came from comes back.
     *
     * @param cancelled what calling it off does, {@code null} for nothing
     */
    void ask(Player player, String key, Consumer<String> answer, Runnable cancelled, String... fills) {
        shown(player);
        if (plugin.getBedrock().menuChoicesAsForms(player)) {
            whenChestShut(player, () -> {
                if (!askInForm(player, key, answer, cancelled, fills)) {
                    askInChat(player, key, answer, cancelled, fills);
                }
            });
            return;
        }
        askInChat(player, key, answer, cancelled, fills);
    }

    /** {@link #ask} in the chat: the next line he writes is the answer, or {@code cancel}. */
    void askInChat(Player player, String key, Consumer<String> answer, Runnable cancelled,
                           String... fills) {
        questions.put(player.getUniqueId(), new Question(System.currentTimeMillis() + ANSWER_WAIT, answer,
                cancelled));
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
                if (question.cancelled() != null) {
                    question.cancelled().run();
                }
            } else {
                question.answer().accept(answer);
            }
        });
    }

    /**
     * Asks in a window of Bedrock instead of the chat: a line to type the
     * answer into, under the text of the question with {@code -form} after
     * its key. Closing the window, or an empty line, calls it off.
     *
     * @return whether the window went out; if not, the chat asks
     */
    boolean askInForm(Player player, String key, Consumer<String> answer, Runnable cancelled,
                              String... fills) {
        String label = messages.getMessage(key + "-form");
        if (label.isEmpty()) {
            label = messages.getMessage(key).replace("{cancel}", CANCEL);
        }
        for (int i = 0; i + 1 < fills.length; i += 2) {
            label = label.replace(fills[i], fills[i + 1]);
        }
        return plugin.getBedrock().forms().input(player, ChatColor.stripColor(messages.getMessage("menu-title")),
                label, messages.getMessage("menu-form-placeholder"), typed -> {
                    String text = typed.trim();
                    if (text.isEmpty()) {
                        called(player, cancelled);
                    } else {
                        answer.accept(text);
                    }
                }, () -> called(player, cancelled));
    }

    /** A question called off: he hears so, and what calling it off does besides is done. */
    void called(Player player, Runnable cancelled) {
        messages.sendConfiguredMessage(player, "menu-answer-cancelled");
        if (cancelled != null) {
            cancelled.run();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        questions.remove(event.getPlayer().getUniqueId());
        shownCount.remove(event.getPlayer().getUniqueId());
        opening.remove(event.getPlayer().getUniqueId());
        drafts.remove(event.getPlayer().getUniqueId());
        spotDrafts.remove(event.getPlayer().getUniqueId());
        newSpots.remove(event.getPlayer().getUniqueId());
        tourDrafts.remove(event.getPlayer().getUniqueId());
        adminSpotSorts.remove(event.getPlayer().getUniqueId());
        spotSorts.remove(event.getPlayer().getUniqueId());
        playerSorts.remove(event.getPlayer().getUniqueId());
        shareSorts.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ clicks

    /**
     * Every click into a window of the menu is turned away - in the window
     * and in his own inventory below it, which a shift-click would empty into
     * it -, and the button it hit is pressed. Not one that came there by
     * itself only a moment ago, see {@link #CLICK_GUARD}: the click was meant
     * for what stood there before.
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
        Long guarded = page.guarded.get(event.getRawSlot());
        if (guarded != null && System.currentTimeMillis() < guarded) {
            return;
        }
        int slot = event.getRawSlot();
        if (page.picks.containsKey(slot) && event.getWhoClicked() instanceof Player player
                && bedrock(player)) {
            // Whatever the click: on Bedrock there is but one, the choices come on a page of their own.
            if (event.getClick() != ClickType.DOUBLE_CLICK) {
                later(() -> openPicks(player, page, slot));
            }
            return;
        }
        Consumer<ClickType> button = page.buttons.get(slot);
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
    void act(Player player, Runnable action) {
        shown(player);
        later(() -> {
            player.closeInventory();
            if (player.isOnline()) {
                action.run();
            }
        });
    }

    /** Runs something a tick later, out of the click. */
    void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    // ------------------------------------------------------------ Bedrock

    /**
     * Whether the menu is made for him the way Bedrock needs it: there is but
     * one kind of click, a tap - what a right-click or a click with shift does
     * comes as a choice of its own, see {@link #openPicks}. See
     * {@link de.elia.cameraplugin.bedrock.BedrockPlayers#adapts}.
     */
    boolean bedrock(Player player) {
        return plugin.getBedrock().adapts(player);
    }

    /**
     * A text of the language file as it is for him: on Bedrock the one with
     * {@code -bedrock} after the key, where there is one - it says how a
     * button is used there -, otherwise the text itself. Under
     * {@code bedrock.menu: forms} the one with {@code -forms} after the key
     * comes first, where there is one: a page as a window is a list, with no
     * left or right to point at.
     */
    String text(Player player, String key) {
        if (!bedrock(player)) {
            return messages.getMessage(key);
        }
        if (plugin.getBedrock().menuAsForms(player)) {
            String forms = messages.getMessage(key + "-forms");
            if (!forms.isEmpty()) {
                return forms;
            }
        }
        String own = messages.getMessage(key + "-bedrock");
        return own.isEmpty() ? messages.getMessage(key) : own;
    }

    /** One thing a button does, by the text of its name, as the click it stands for does it. */
    Pick pick(String key, Consumer<ClickType> action, ClickType click) {
        return new Pick(messages.getMessage(key), false, () -> action.accept(click));
    }

    /**
     * The things a button does, for him to pick on Bedrock instead of
     * clicking a certain way - only there, and only where there is more than
     * one.
     */
    void picks(Player player, Page page, int slot, List<Pick> picks) {
        if (bedrock(player) && picks.size() > 1) {
            page.picks.put(page.at(slot), picks);
        }
    }

    /**
     * What a button does, to pick one, for a player on Bedrock: a click on it
     * leads here. As a window of Bedrock where that goes - under
     * {@code bedrock.menu: mixed} the chest is closed for it, see
     * {@link #whenChestShut} -, otherwise as a page of its own, see
     * {@link #picksChest}. Picking one does what its click does; when that
     * shows nothing else, the page comes back, as it stands then, see
     * {@link #afterwards}.
     */
    void openPicks(Player player, Page from, int slot) {
        List<Pick> picks = from.picks.get(slot);
        ItemStack button = from.inventory.getItem(slot);
        if (picks == null || button == null || !player.isOnline()) {
            return;
        }
        shown(player);
        if (plugin.getBedrock().menuChoicesAsForms(player)) {
            BooleanSupplier own = from.windows.get(slot);
            whenChestShut(player, () -> {
                boolean sent = own != null ? own.getAsBoolean() : picksWindow(player, from, button, picks);
                if (!sent) {
                    picksChest(player, from, button, picks);
                }
            });
            return;
        }
        picksChest(player, from, button, picks);
    }

    /**
     * The choices of a button as a window of Bedrock: the name and the lines
     * of the button in the text over them, the one that holds now marked,
     * the way back last. Closing the window leads back as well: it called off
     * the choice, not the menu.
     *
     * @return whether the window went out
     */
    boolean picksWindow(Player player, Page from, ItemStack button, List<Pick> picks) {
        List<String> labels = new ArrayList<>();
        for (Pick pick : picks) {
            labels.add(ChatColor.stripColor(pick.current()
                    ? messages.getMessage("menu-pick-current").replace("{pick}", pick.label()) : pick.label()));
        }
        labels.add(ChatColor.stripColor(messages.getMessage("menu-pick-back")));
        return plugin.getBedrock().forms().list(player, ChatColor.stripColor(displayName(button)), itemText(button),
                labels, index -> {
                    if (index < picks.size()) {
                        picked(player, from, picks.get(index));
                    } else {
                        reshow(player, from);
                    }
                }, () -> reshow(player, from));
    }

    /**
     * The choices of a button as a page of the chest: the button on top -
     * its lines show when it is held -, the choices in the middle, the one
     * that holds now shining, and the way back at the bottom. In a window of
     * six rows with the title of the menu, like every page on Bedrock, see
     * {@link #page}: Geyser keeps its chest for it, see {@link #openWindow}.
     */
    void picksChest(Player player, Page from, ItemStack button, List<Pick> picks) {
        String name = ChatColor.stripColor(displayName(button));
        // A page of its own, never the one he has open drawn anew in place:
        // that one is where the way back leads.
        Page page = new Page();
        page.title = messages.getMessage("menu-pick-title").replace("{button}", name);
        page.inventory = Bukkit.createInventory(page, LIST, messages.getMessage("menu-title"));
        page.bedrock = true;
        page.bedrockMaps = settings.bedrock().mapIcons();
        put(page, SLOT_PICK_BUTTON, button.clone(), NOTHING);
        for (int i = 0; i < picks.size() && i < PICK_SLOTS.length; i++) {
            Pick pick = picks.get(i);
            ItemStack item = pick.current()
                    ? shine(item(Material.LIME_DYE, messages.getMessage("menu-pick-current")
                    .replace("{pick}", pick.label()), lines(messages.getMessage("menu-state-current"))), true)
                    : item(Material.PAPER, messages.getMessage("menu-pick").replace("{pick}", pick.label()), List.of());
            put(page, PICK_SLOTS[i], item, click -> {
                if (click != ClickType.DOUBLE_CLICK) {
                    picked(player, from, pick);
                }
            });
        }
        put(page, SLOT_PICK_BACK, Material.ARROW, messages.getMessage("menu-pick-back"), List.of(),
                click -> later(() -> reshow(player, from)));
        show(player, page);
    }

    /**
     * Runs what comes as a window of Bedrock: right away while the menu is
     * windows itself ({@code bedrock.menu: forms}); out of the chest
     * ({@code mixed}) once the chest is closed and Bedrock had a moment to
     * take it off the screen, {@link #WINDOW_AFTER_CHEST_TICKS}.
     */
    void whenChestShut(Player player, Runnable window) {
        boolean chest = openPage(player) != null;
        if (!chest && plugin.getBedrock().menuAsForms(player)) {
            window.run();
            return;
        }
        if (chest) {
            player.closeInventory();
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                window.run();
            }
        }, WINDOW_AFTER_CHEST_TICKS);
    }

    /** Does what was picked; when that shows nothing else, the page it came from comes back. */
    void picked(Player player, Page from, Pick pick) {
        int before = shownCount(player);
        pick.action().run();
        afterwards(player, before, () -> reshow(player, from));
    }

    /**
     * Shows a page again after something was picked or clicked on Bedrock:
     * drawn anew as it stands now, where it can be, otherwise as it was.
     */
    void reshow(Player player, Page page) {
        if (!player.isOnline()) {
            return;
        }
        if (page.again != null) {
            page.again.run();
            return;
        }
        shown(player);
        if (!plugin.getBedrock().menuAsForms(player) || !sendForm(player, page)) {
            openWindow(player, page);
        }
    }

    /**
     * Opens the window of a page. On Bedrock, while another window of the
     * menu is open, that one is closed first and this one opened a moment
     * later, {@link #WINDOW_AFTER_CHEST_TICKS}: Geyser shows a chest on
     * Bedrock as a chest of its own put down for it, and one opened in the
     * place of another before the first is gone from the screen may close
     * again at once. Opened in between, by a click or by himself, another
     * one takes its place. Not so for a window as large as the one open,
     * with the same title - every page of the menu on Bedrock, see
     * {@link #page} -: Geyser keeps its chest for that one and only fills it
     * anew, nothing closes there.
     */
    void openWindow(Player player, Page page) {
        UUID id = player.getUniqueId();
        Page open = openPage(player);
        if (!bedrock(player) || open == null
                || open.bedrock && page.bedrock && open.inventory.getSize() == page.inventory.getSize()) {
            opening.remove(id);
            player.openInventory(page.inventory);
            return;
        }
        player.closeInventory();
        opening.put(id, page);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (opening.remove(id, page) && player.isOnline()) {
                player.openInventory(page.inventory);
            }
        }, WINDOW_AFTER_CHEST_TICKS);
    }

    /**
     * Shows something again once a button had its say, unless it showed
     * something itself - a page, a question - or closed the menu for what it
     * does. A button acts a tick after the click, or closes the menu right
     * away; what it opens comes out of that, so it is looked a few ticks
     * later.
     *
     * @param before {@link #shownCount} from before the button acted, which
     *               may close the menu at once
     */
    void afterwards(Player player, int before, Runnable again) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && shownCount(player) == before) {
                again.run();
            }
        }, AFTERWARDS_TICKS);
    }

    /** How often the menu has shown him something so far, see {@link #shown}. */
    int shownCount(Player player) {
        return shownCount.getOrDefault(player.getUniqueId(), 0);
    }

    /** Notes that the menu showed him something, or closed for what a button does, see {@link #afterwards}. */
    void shown(Player player) {
        shownCount.merge(player.getUniqueId(), 1, Integer::sum);
    }

    /**
     * Sends a page as a window of Bedrock: its buttons one under the other,
     * in the order of their slots - the list first, then the row with the way
     * back and on -, each with the first of its lines under its name; what
     * only shows something stands in the text over them. A button that does
     * more than one thing offers them on a tap, see {@link #openPicks};
     * every other one does what its click does. When that shows nothing
     * else, the page comes back as it stands then.
     *
     * @return whether the window went out; if not, the page is a chest
     */
    boolean sendForm(Player player, Page page) {
        List<Integer> slots = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (int slot = 0; slot < page.inventory.getSize(); slot++) {
            ItemStack item = page.inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Consumer<ClickType> action = page.buttons.get(slot);
            if (action == null || action == NOTHING) {
                if (!content.isEmpty()) {
                    content.append("\n\n");
                }
                content.append(itemText(item));
                continue;
            }
            slots.add(slot);
            labels.add(formLabel(item));
        }
        return plugin.getBedrock().forms().list(player, ChatColor.stripColor(page.title), content.toString(), labels,
                index -> {
                    int slot = slots.get(index);
                    if (page.picks.containsKey(slot)) {
                        openPicks(player, page, slot);
                        return;
                    }
                    Consumer<ClickType> action = page.buttons.get(slot);
                    if (action != null) {
                        int before = shownCount(player);
                        action.accept(ClickType.LEFT);
                        afterwards(player, before, () -> reshow(player, page));
                    }
                }, null);
    }

    /**
     * What a button of a window of Bedrock says: the name of the item, and
     * under it the first of its lines, cut short where it is long. Without
     * colours - Bedrock draws the buttons light, and most colours of the menu
     * are made for a dark window.
     */
    static String formLabel(ItemStack item) {
        String label = ChatColor.stripColor(displayName(item));
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : null;
        if (lore != null && !lore.isEmpty()) {
            String first = ChatColor.stripColor(lore.get(0)).trim();
            if (first.length() > FORM_LINE) {
                first = first.substring(0, FORM_LINE - 1).trim() + "\u2026";
            }
            if (!first.isEmpty()) {
                label += "\n" + first;
            }
        }
        return label;
    }

    /** The name of an item and its lines, as a text of a window of Bedrock, with their colours. */
    static String itemText(ItemStack item) {
        StringBuilder text = new StringBuilder(displayName(item));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String line : meta.getLore()) {
                text.append("\n").append(line);
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------ items

    /** A button by a text of the language file: its name, and under it the text with {@code -lore} after the key. */
    void put(Page page, int slot, Material material, String key, Consumer<ClickType> action) {
        put(page, slot, material, messages.getMessage(key), lines(messages.getMessage(key + "-lore")), action);
    }

    void put(Page page, int slot, Material material, String name, List<String> lore,
                     Consumer<ClickType> action) {
        put(page, slot, item(material, name, lore), action);
    }

    /**
     * Puts a button into a page, by its slot on the page as it is laid out:
     * on Bedrock a page of three or four rows has it where its window of six
     * puts it, see {@link Page#rows}.
     */
    static void put(Page page, int slot, ItemStack item, Consumer<ClickType> action) {
        int at = page.at(slot);
        page.inventory.setItem(at, page.bedrock ? forBedrock(item, page.bedrockMaps) : item);
        page.buttons.put(at, action);
    }

    /**
     * An item as a page for Bedrock shows it: a filled map as an empty one.
     * Bedrock names a filled map by the map it shows - "Unknown Map" for one
     * without, which every map of the menu is -, and never by the name the
     * menu gave it; an empty map keeps its name. Its name, its lines and its
     * shine go along. So do the maps of the structures 26.3 brings, the icons
     * a spot may have, unless {@code bedrock.map-icons} lets them be.
     *
     * @param maps whether the maps of 26.3 stay as they are, {@code bedrock.map-icons}
     */
    static ItemStack forBedrock(ItemStack item, boolean maps) {
        if (item == null || item.getType() == Material.MAP) {
            return item;
        }
        boolean filled = item.getType() == Material.FILLED_MAP;
        if (!filled && (maps || !item.getType().name().endsWith("_MAP"))) {
            return item;
        }
        ItemStack map = new ItemStack(Material.MAP, item.getAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            map.setItemMeta(Bukkit.getItemFactory().asMetaFor(meta, Material.MAP));
        }
        return map;
    }

    /** The buttons to the page before and after, where there is one. */
    void paging(Page page, int number, int pages, Consumer<Integer> open) {
        paging(page, number, pages, open, SLOT_PREVIOUS, SLOT_NEXT);
    }

    /** The buttons to the page before and after, in other slots than usual. */
    void paging(Page page, int number, int pages, Consumer<Integer> open, int previous, int next) {
        if (number > 0) {
            put(page, previous, Material.ARROW, "menu-previous", click -> later(() -> open.accept(number - 1)));
        }
        if (number < pages - 1) {
            put(page, next, Material.ARROW, "menu-next", click -> later(() -> open.accept(number + 1)));
        }
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        return labelled(new ItemStack(material), name, lore);
    }

    /** An item with a name and lines of the menu in place of its own. */
    static ItemStack labelled(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Lets an item shine like an enchanted one, for what is switched on or holds now. */
    static ItemStack shine(ItemStack item, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (glint && meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** A text of the language file as lines under an item, {@code \n} beginning a new one; none for an empty one. */
    static List<String> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.split("\n"));
    }

    /** {@code {x}}, {@code {y}}, {@code {z}} and {@code {world}} of a spot. */
    static String place(String text, Spot spot) {
        return text.replace("{x}", String.valueOf(Location.locToBlock(spot.x())))
                .replace("{y}", String.valueOf(Location.locToBlock(spot.y())))
                .replace("{z}", String.valueOf(Location.locToBlock(spot.z())))
                .replace("{world}", spot.world());
    }
}
