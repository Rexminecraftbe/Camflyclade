package de.elia.cameraplugin.start;

/**
 * Which hits a player hands out keep him from starting camera mode for a
 * while, the values of {@code cam-safety.after-attack}: false, players and
 * all.
 */
public enum AttackSafety {
    /** {@code false}: none, only the hits he takes count. */
    OFF,
    /** {@code players}: a hit on another player, or on the body of one in camera mode. */
    PLAYERS,
    /** {@code all}: a hit on a player or on any mob. */
    ALL
}
