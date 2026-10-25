package de.elia.cameraplugin.menu;

/**
 * How the players get at what camera mode offers besides itself -
 * {@code back}, {@code resume}, {@code spot}, {@code visit}, {@code photo},
 * {@code time}, {@code weather} and {@code nightvision} -, the values of
 * {@code menu.access}: both, menu, command and false.
 */
public enum MenuAccess {
    /** {@code both}: through {@code /cam menu} and through the commands. */
    BOTH,
    /** {@code menu}: through {@code /cam menu} alone; the commands send him there. */
    MENU,
    /** {@code command}: through the commands alone; there is no {@code /cam menu}. */
    COMMAND,
    /** {@code false}: neither - camera mode itself, on and off, and nothing besides. */
    OFF;

    /** Whether {@code /cam menu} opens. */
    public boolean allowsMenu() {
        return this == BOTH || this == MENU;
    }

    /** Whether the commands themselves work. */
    public boolean allowsCommands() {
        return this == BOTH || this == COMMAND;
    }
}
