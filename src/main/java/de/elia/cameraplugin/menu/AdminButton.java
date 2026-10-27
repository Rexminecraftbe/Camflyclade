package de.elia.cameraplugin.menu;

/**
 * The buttons of the admin page of {@code /cam menu}, each with its section
 * under {@code menu.admin-buttons} and the item and slot it has out of the
 * box. The page has as many slots as the main page.
 */
public enum AdminButton implements MenuSlot {
    LIST("list", "spyglass", 10),
    PLAYERS("players", "iron_bars", 12),
    STATS("stats", "bookshelf", 14),
    TRAILS("trails", "lead", 16),
    SPOTS("spots", "filled_map", 20),
    TOURS("tours", "saddle", 24),
    BACK("back", "arrow", 22);

    private final String key;
    private final String defaultItem;
    private final int defaultSlot;

    AdminButton(String key, String defaultItem, int defaultSlot) {
        this.key = key;
        this.defaultItem = defaultItem;
        this.defaultSlot = defaultSlot;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String defaultItem() {
        return defaultItem;
    }

    @Override
    public int defaultSlot() {
        return defaultSlot;
    }
}
