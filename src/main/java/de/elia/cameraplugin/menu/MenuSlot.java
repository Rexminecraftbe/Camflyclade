package de.elia.cameraplugin.menu;

/**
 * A button of a page of {@code /cam menu} that the config file places: its
 * section, and the item and slot it has out of the box.
 */
public interface MenuSlot {

    /** Its section, below {@code menu.buttons} or {@code menu.admin-buttons}. */
    String key();

    /** The section it had before it was renamed, which an older file may still have; {@code null} for none. */
    default String oldKey() {
        return null;
    }

    String defaultItem();

    int defaultSlot();

    /** Whether it may stand in the same slot as the other one, because they are never there together. */
    default boolean mayShareWith(MenuSlot other) {
        return false;
    }
}
