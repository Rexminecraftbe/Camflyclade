package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.camfly2.PluginPart;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

import static de.elia.cameraplugin.menu.MenuDrafts.*;
import static de.elia.cameraplugin.menu.MenuItems.*;
import static de.elia.cameraplugin.menu.MenuSorts.*;

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
 * and the clicks. The questions in the chat are {@link MenuQuestions}, what
 * Bedrock gets besides the chest {@link BedrockPages}; the items of the
 * buttons are made by {@link MenuItems}, the lists sorted by
 * {@link MenuSorts}, and what is set up on a page is one of
 * {@link MenuDrafts}.</p>
 */
public final class CamMenu implements Listener, PluginPart {

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
    /**
     * The buttons on the page of the flights of a player that delete all of
     * them: in the corner of the last row, where the admin pages sort their
     * players, and once it asks, the one that deletes them next to it.
     */
    static final int SLOT_FLIGHTS_DELETE = 53;
    static final int SLOT_FLIGHTS_DELETE_CONFIRM = 52;

    /** What a button that only shows something does on a click: nothing. */
    static final Consumer<ClickType> NOTHING = click -> { };

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
    /**
     * How long a window of Bedrock waits after the chest of the menu was
     * closed for it, in ticks, see {@link BedrockPages#whenChestShut}: Bedrock is to have
     * the chest off the screen before the window comes.
     */
    static final long WINDOW_AFTER_CHEST_TICKS = 5L;

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

    /** How each admin has the spots of a player sorted on the admin page, while he is online. */
    final Map<UUID, SpotSort> adminSpotSorts = new HashMap<>();

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
         * click that way: on Bedrock, see {@link BedrockPages#openPicks}. Only for
         * such a player, and only for a button that does more than one thing.
         */
        final Map<Integer, List<Pick>> picks = new HashMap<>();
        /**
         * A window of Bedrock of its own for a button with {@link #picks},
         * in place of the list of them, see {@link BedrockPages#openPicks}: it
         * sends it and says whether it went out.
         */
        final Map<Integer, BooleanSupplier> windows = new HashMap<>();
        /**
         * Whether it is made for a player on Bedrock, which draws some items
         * otherwise than Java does, see {@link MenuItems#forBedrock}.
         */
        boolean bedrock;
        /** Whether the maps of 26.3 stay themselves on Bedrock, {@code bedrock.map-icons}, see {@link MenuItems#forBedrock}. */
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
        /** What each slot held before the page was drawn anew in place, see {@link MenuItems#identity}; {@code null} after. */
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
     * from a list instead of clicking a certain way, see {@link BedrockPages#openPicks}.
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
    /** The questions in the chat, or in a window of Bedrock, see {@link MenuQuestions}. */
    final MenuQuestions questions;
    /** What Bedrock gets besides the chest, see {@link BedrockPages}. */
    final BedrockPages bedrockPages;

    public CamMenu(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        questions = new MenuQuestions(this);
        bedrockPages = new BedrockPages(this);
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
    @Override
    public void start() {
        if (live == null) {
            live = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, LIVE_TICKS, LIVE_TICKS);
        }
    }

    @Override
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
        if (before == null && plugin.getBedrock().menuAsForms(player) && bedrockPages.sendForm(player, page)) {
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

    // ------------------------------------------------------------ the chat, and leaving

    /** The line he writes after a question of the menu, see {@link MenuQuestions#onChat}. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        questions.onChat(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        questions.forget(event.getPlayer().getUniqueId());
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
                later(() -> bedrockPages.openPicks(player, page, slot));
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
     * comes as a choice of its own, see {@link BedrockPages#openPicks}. See
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
}
