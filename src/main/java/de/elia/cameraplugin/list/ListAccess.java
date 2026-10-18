package de.elia.cameraplugin.list;

/** Who may use {@code /cam list}, the values of {@code cam-list.access}: admin, all and false. */
public enum ListAccess {
    /** {@code admin}: whoever holds {@code camplugin.list}, which operators have by default. */
    ADMIN,
    /** {@code all}: whoever may use {@code /cam} at all. */
    ALL,
    /** {@code false}: no player, only the console. */
    OFF
}
