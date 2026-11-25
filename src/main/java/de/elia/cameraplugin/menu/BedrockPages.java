package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static de.elia.cameraplugin.menu.CamMenu.*;
import static de.elia.cameraplugin.menu.MenuItems.*;

/**
 * What {@link CamMenu} shows a player on Bedrock besides the chest, see
 * {@code bedrock.menu}: what a button does besides a plain click - a
 * right-click, a click with shift -, to pick from, on a page of its own or as
 * a window of Bedrock; and every page as a window, a list of buttons one under
 * the other, where that goes.
 */
final class BedrockPages {

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

    private final CamMenu menu;
    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    BedrockPages(CamMenu menu) {
        this.menu = menu;
        this.plugin = menu.plugin;
        this.settings = menu.settings;
        this.messages = menu.messages;
    }

    /**
     * What a button does, to pick one, for a player on Bedrock: a click on it
     * leads here. As a window of Bedrock where that goes - under
     * {@code bedrock.menu: mixed} the chest is closed for it, see
     * {@link #whenChestShut} -, otherwise as a page of its own, see
     * {@link #picksChest}. Picking one does what its click does; when that
     * shows nothing else, the page comes back, as it stands then, see
     * {@link CamMenu#afterwards}.
     */
    void openPicks(Player player, Page from, int slot) {
        List<Pick> picks = from.picks.get(slot);
        ItemStack button = from.inventory.getItem(slot);
        if (picks == null || button == null || !player.isOnline()) {
            return;
        }
        menu.shown(player);
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
     * {@link CamMenu#page}: Geyser keeps its chest for it, see {@link CamMenu#openWindow}.
     */
    void picksChest(Player player, Page from, ItemStack button, List<Pick> picks) {
        String name = ChatColor.stripColor(displayName(button));
        // A page of its own, never the one he has open drawn anew in place:
        // that one is where the way back leads.
        Page page = new Page();
        page.title = messages.getMessage("menu-pick-title").replace("{button}", name);
        page.inventory = Bukkit.createInventory(page, LIST, messages.getMessage("menu-title"));
        page.bedrock = true;
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
        menu.put(page, SLOT_PICK_BACK, Material.ARROW, messages.getMessage("menu-pick-back"), List.of(),
                click -> menu.later(() -> reshow(player, from)));
        menu.show(player, page);
    }

    /**
     * Runs what comes as a window of Bedrock: right away while the menu is
     * windows itself ({@code bedrock.menu: forms}); out of the chest
     * ({@code mixed}) once the chest is closed and Bedrock had a moment to
     * take it off the screen, {@link CamMenu#WINDOW_AFTER_CHEST_TICKS}.
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
        int before = menu.shownCount(player);
        pick.action().run();
        menu.afterwards(player, before, () -> reshow(player, from));
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
        menu.shown(player);
        if (!plugin.getBedrock().menuAsForms(player) || !sendForm(player, page)) {
            menu.openWindow(player, page);
        }
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
                        int before = menu.shownCount(player);
                        action.accept(ClickType.LEFT);
                        menu.afterwards(player, before, () -> reshow(player, page));
                    }
                }, null);
    }
}
