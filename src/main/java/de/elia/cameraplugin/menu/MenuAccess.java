package de.elia.cameraplugin.menu;

/**
 * How the players get at what camera mode offers besides itself -
 * {@code back}, {@code resume}, {@code spot}, {@code visit}, {@code photo},
 * {@code time}, {@code weather}, {@code nightvision} and their own
 * {@code stats} -, the values of {@code menu.access}; and how admins get at
 * theirs - {@code list}, {@code stats player}, {@code stop}, {@code ban},
 * {@code unban}, {@code banlist}, {@code trail} and {@code tp} -, the values of
 * {@code menu.admin-access}: both, menu, command and false. The console is
 * held to neither.
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
