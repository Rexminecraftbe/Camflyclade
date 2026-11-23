package de.elia.cameraplugin.start;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.mob.MobTargeting;

/**
 * The section {@code cam-safety} of the config file: what keeps camera mode
 * from starting in the middle of a fight. See {@link de.elia.cameraplugin.config.CamSettings#camSafety()}.
 */
public final class CamSafetySettings {

    // Camera safety settings
    private boolean camSafetyEnabled;
    private int camSafetyDelay;
    /** Whether a hit on another player makes him wait as well, {@code cam-safety.after-attack.players}. */
    private boolean camSafetyAfterPlayers;
    /** The same for a hit on a hostile mob, {@code cam-safety.after-attack.mobs}. */
    private boolean camSafetyAfterMobs;
    /** The same for a hit on anything friendly, {@code cam-safety.after-attack.friendly}. */
    private boolean camSafetyAfterFriendly;
    /** Whether another player close by keeps camera mode from starting, {@code cam-safety.nearby-players}. */
    private boolean camSafetyNearbyPlayers;
    /** How close he has to be for that, in blocks, {@code cam-safety.nearby-radius}. */
    private double camSafetyNearbyRadius;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public CamSafetySettings(ConfigReader config) {
        camSafetyEnabled = config.getBoolean("cam-safety.enabled", true);
        camSafetyDelay = config.getInt("cam-safety.delay", 5, 0);
        camSafetyAfterPlayers = config.getBoolean("cam-safety.after-attack.players", false);
        camSafetyAfterMobs = config.getBoolean("cam-safety.after-attack.mobs", false);
        camSafetyAfterFriendly = config.getBoolean("cam-safety.after-attack.friendly", false);
        camSafetyNearbyPlayers = config.getBoolean("cam-safety.nearby-players", false);
        camSafetyNearbyRadius = config.getDouble("cam-safety.nearby-radius", 16.0, 0.0,
                MobTargeting.MAX_MOB_TARGET_RANGE);
    }

    public boolean enabled() {
        return camSafetyEnabled;
    }

    public int delay() {
        return camSafetyDelay;
    }

    public boolean waitsAfterHittingPlayers() {
        return camSafetyAfterPlayers;
    }

    public boolean waitsAfterHittingMobs() {
        return camSafetyAfterMobs;
    }

    public boolean waitsAfterHittingFriendly() {
        return camSafetyAfterFriendly;
    }

    /**
     * Whether another player close by keeps camera mode from starting: the
     * safety switched on, {@code nearby-players} as well, and a radius anybody
     * can stand inside of.
     */
    public boolean locksNearPlayers() {
        return camSafetyEnabled && camSafetyNearbyPlayers && camSafetyNearbyRadius > 0.0;
    }

    public double nearbyRadius() {
        return camSafetyNearbyRadius;
    }
}
