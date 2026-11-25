package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The section {@code menu} of the config file, for {@code /cam menu}: who
 * gets the menu and who the commands, and where its buttons stand with which
 * item. See {@link de.elia.cameraplugin.config.CamSettings#menu()}.
 */
public final class MenuSettings {

    /** How the players get at back, spot, photo and the rest, {@code menu.access}. */
    private MenuAccess menuAccess;
    /** How admins get at list, stop, ban, trail and the rest, {@code menu.admin-access}. */
    private MenuAccess adminAccess;
    /** What each button of the main page of the menu shows, {@code menu.buttons.<button>.item}. */
    private final Map<MenuButton, MenuIcon> menuIcons = new EnumMap<>(MenuButton.class);
    /** Where it stands, {@code menu.buttons.<button>.slot}. */
    private final Map<MenuButton, Integer> menuSlots = new EnumMap<>(MenuButton.class);
    /** The same for the admin page, {@code menu.admin-buttons}. */
    private final Map<AdminButton, MenuIcon> adminIcons = new EnumMap<>(AdminButton.class);
    /** Whether the pages of the menu that are open are drawn anew by themselves, {@code menu.live-update}. */
    private boolean menuLiveUpdate;
    private final Map<AdminButton, Integer> adminSlots = new EnumMap<>(AdminButton.class);

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public MenuSettings(ConfigReader config, Messages messages) {
        menuAccess = readMenuAccess(config, "menu.access");
        adminAccess = readMenuAccess(config, "menu.admin-access");
        readButtons(config, messages, "menu.buttons", MenuButton.values(), menuIcons, menuSlots);
        readButtons(config, messages, "menu.admin-buttons", AdminButton.values(), adminIcons, adminSlots);
        menuLiveUpdate = config.getBoolean("menu.live-update", true);
    }

    /** Reads how something is got at, the values menu, both, command and false. */
    private static MenuAccess readMenuAccess(ConfigReader config, String path) {
        String access = config.getChoice(path, "menu", "menu", "both", "command", "false").toLowerCase(Locale.ROOT);
        return switch (access) {
            case "both" -> MenuAccess.BOTH;
            case "command" -> MenuAccess.COMMAND;
            case "false" -> MenuAccess.OFF;
            default -> MenuAccess.MENU;
        };
    }

    /**
     * Reads a section of buttons, {@code menu.buttons} or
     * {@code menu.admin-buttons}: the item and the slot of each. A slot that
     * another button of the same page has taken already - other than start
     * and end, which are never there together - is noted, and the button goes
     * to its own slot of out of the box, or the first one free.
     */
    private <B extends MenuSlot> void readButtons(ConfigReader config, Messages messages, String section, B[] buttons,
                                                  Map<B, MenuIcon> icons, Map<B, Integer> slots) {
        icons.clear();
        slots.clear();
        String allowed = messages.configMessage("config-allowed-menu-items");
        for (B button : buttons) {
            String path = button.oldKey() == null ? section + "." + button.key()
                    : config.renamed(section + "." + button.key(), section + "." + button.oldKey());
            String raw = config.getString(path + ".item", button.defaultItem());
            MenuIcon icon = MenuIcon.parse(raw);
            if (icon == null) {
                config.warnUnknownValue(path + ".item", raw, allowed, button.defaultItem());
                icon = MenuIcon.parse(button.defaultItem());
            }
            icons.put(button, icon);
            int slot = config.getInt(path + ".slot", button.defaultSlot(), 0, MenuButton.SLOTS - 1);
            B other = slotTakenBy(slots, button, slot);
            if (other != null) {
                int free = slotTakenBy(slots, button, button.defaultSlot()) == null ? button.defaultSlot() : 0;
                while (slotTakenBy(slots, button, free) != null) {
                    free++;
                }
                config.warnSlotTaken(path + ".slot", slot, section + "." + other.key() + ".slot", free);
                slot = free;
            }
            slots.put(button, slot);
        }
    }

    /** The button that has this slot already and may not share it with this one, or {@code null}. */
    private static <B extends MenuSlot> B slotTakenBy(Map<B, Integer> slots, B button, int slot) {
        for (Map.Entry<B, Integer> entry : slots.entrySet()) {
            if (entry.getValue() == slot && !button.mayShareWith(entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    public MenuAccess access() {
        return menuAccess;
    }

    public MenuAccess adminAccess() {
        return adminAccess;
    }

    /** Whether the pages of the menu that are open are drawn anew by themselves, as things change. */
    public boolean liveUpdate() {
        return menuLiveUpdate;
    }

    /** What a button of the main page of the menu shows. */
    public MenuIcon icon(MenuButton button) {
        return menuIcons.get(button);
    }

    /** Where a button of the main page of the menu stands, 0 being the top left. */
    public int slot(MenuButton button) {
        return menuSlots.get(button);
    }

    /** What a button of the admin page of the menu shows. */
    public MenuIcon adminIcon(AdminButton button) {
        return adminIcons.get(button);
    }

    /** Where a button of the admin page of the menu stands. */
    public int adminSlot(AdminButton button) {
        return adminSlots.get(button);
    }
}
