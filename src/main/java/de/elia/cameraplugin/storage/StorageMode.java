package de.elia.cameraplugin.storage;

/**
 * Where the cooldowns of {@code time-limit} and the charge of {@code battery}
 * are kept, the values of {@code time-limit.storage} and
 * {@code battery.storage}: file and memory.
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
