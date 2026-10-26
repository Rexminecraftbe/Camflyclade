package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.sky.CamSky;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@code /cam menu}: what camera mode offers, in a window to click instead of
 * commands to type - starting and ending it, {@code back}, {@code resume},
 * the spots of {@code /cam spot} with sharing them and visiting those of
 * others, {@code photo}, {@code time}, {@code weather} and
 * {@code nightvision}.
 *
 * <p>Each button does what its command does, with the same checks and the
 * same messages; what is switched off in the config file is left out. Whether
 * the menu is there at all, and whether the commands are, is
 * {@code menu.access}.</p>
 *
 * <p>The name of a new spot cannot be clicked, so the menu closes and asks
 * for it in the chat: the next line he writes is the name, and nobody else
 * reads it.</p>
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

    /** How long the menu waits for the name of a new spot in the chat, in milliseconds. */
    private static final long NAME_WAIT = 60_000L;

    /** The word that calls off naming a new spot. */
    private static final String CANCEL = "cancel";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** Who is asked for the name of a new spot, until when. Read from the chat thread. */
    private final Map<UUID, Long> naming = new ConcurrentHashMap<>();

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
        player.openInventory(inventory);
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

    // ------------------------------------------------------------ naming

    /** Asks for the name of a new spot in the chat; the next line he writes is it. */
    private void askName(Player player) {
        naming.put(player.getUniqueId(), System.currentTimeMillis() + NAME_WAIT);
        messages.sendConfiguredMessage(player, "menu-spot-name", "{cancel}", CANCEL);
    }

    /**
     * The line he writes after "save a spot here": the name of the spot, or
     * {@code cancel}. Nobody else reads it. Asked on the thread of the chat,
     * so the spot itself is saved on the main thread, where he stands then.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Long until = naming.remove(player.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String name = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (name.equalsIgnoreCase(CANCEL)) {
                messages.sendConfiguredMessage(player, "menu-spot-name-cancelled");
            } else {
                plugin.getSpots().set(player, name);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        naming.remove(event.getPlayer().getUniqueId());
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
