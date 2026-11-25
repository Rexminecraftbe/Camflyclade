package de.elia.cameraplugin.storage;

/**
 * Where what belongs to the players is kept - the cooldowns, the charge of
 * the battery, the spots and the rest -, the values of {@code storage}: file
 * and memory.
 */
public enum StorageMode {
    /**
     * {@code file}: in the file of the player, see {@link PlayerStore}. It
     * outlasts {@code /cam reload} and a restart of the server.
     */
    FILE,
    /**
     * {@code memory}: only while the plugin runs. {@code /cam reload} and a
     * restart of the server forget it - every cooldown is over and every
     * battery full.
     */
    MEMORY
}
