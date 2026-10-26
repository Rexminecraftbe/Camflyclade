package de.elia.cameraplugin.menu;

/**
 * The buttons of the main page of {@code /cam menu}, each with its section
 * under {@code menu.buttons} and the item and slot it has out of the box.
 *
 * <p>{@code start} and {@code end} are never there at the same time - one
 * starts camera mode, the other ends it -, so they may share a slot; every
 * other button needs one of its own.</p>
 */
public enum MenuButton {
    NIGHT_VISION("nightvision", MenuIcon.NIGHT_VISION_POTION, 10),
    BACK("back", "ender_pearl", 11),
    RESUME("resume", "recovery_compass", 12),
    SPOTS("spots", "filled_map", 13),
    PHOTO("photo", "painting", 14),
    TIME("time", "clock", 15),
    WEATHER("weather", "sunflower", 16),
    STATS("stats", "book", 21),
    START("start", MenuIcon.CAMERA_HEAD, 22),
    END("end", "barrier", 22),
    ADMIN("admin", "command_block", 23);

    /** How many slots the main page has: three rows. */
    public static final int SLOTS = 27;

    private final String key;
    private final String defaultItem;
    private final int defaultSlot;

    MenuButton(String key, String defaultItem, int defaultSlot) {
        this.key = key;
        this.defaultItem = defaultItem;
        this.defaultSlot = defaultSlot;
    }

    /** Its section under {@code menu.buttons}. */
    public String key() {
        return key;
    }

    public String defaultItem() {
        return defaultItem;
    }

    public int defaultSlot() {
        return defaultSlot;
    }

    /** Whether two buttons may stand in the same slot: only start and end, which are never there together. */
    public boolean mayShareWith(MenuButton other) {
        return (this == START && other == END) || (this == END && other == START);
    }
}
