package de.elia.cameraplugin.list;

/**
 * Who may use a command for admins, the values admin, all and false: of
 * {@code cam-list.access} for {@code /cam list}, and of
 * {@code statistics.view-others} for {@code /cam stats player}.
 */
public enum ListAccess {
    /** {@code admin}: whoever holds the right of the command, which operators have by default. */
    ADMIN,
    /** {@code all}: whoever may use {@code /cam} at all. */
    ALL,
    /** {@code false}: no player, only the console. */
    OFF
}
