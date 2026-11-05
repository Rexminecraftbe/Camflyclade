package de.elia.cameraplugin.command;

/** Which commands a camera player may use, {@code cam-commands.mode}. */
public enum CommandMode {
    /** Every command but those of {@code cam-commands.list}. */
    BLACKLIST,
    /** Only {@code /cam} and those of {@code cam-commands.list}. */
    WHITELIST,
    /** Every command, as before there was this setting. */
    ALL
}
