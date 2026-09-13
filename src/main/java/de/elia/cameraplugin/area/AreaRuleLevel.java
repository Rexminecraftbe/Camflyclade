package de.elia.cameraplugin.area;

/**
 * How far the rules about where camera mode is allowed reach - the values of
 * {@code cam-area.level}.
 *
 * <p>The levels build on each other: each one forbids something the level
 * below it still allows. The number in brackets is the value used in the
 * config file.</p>
 */
public enum AreaRuleLevel {
    /**
     * The rules are not looked at. Camera mode works in every biome and in
     * every dimension, the two lists in the config file stay untouched.
     */
    OFF(0),
    /**
     * Camera mode cannot be started in a forbidden area. Whoever started it
     * somewhere else may still fly into one.
     */
    START(1),
    /**
     * Camera mode cannot be started in a forbidden area and cannot be carried
     * into one either: at its border the player simply does not get any
     * further, the way {@code camera-mode.max-distance} stops him.
     */
    START_AND_FLIGHT(2);

    private final int id;

    AreaRuleLevel(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /** Whether a forbidden area keeps camera mode from being started. */
    public boolean blocksStart() {
        return this != OFF;
    }

    /** Whether a forbidden area cannot be flown into while in camera mode. */
    public boolean blocksFlight() {
        return this == START_AND_FLIGHT;
    }

    /**
     * Resolves the number configured in {@code cam-area.level}.
     *
     * @return the matching level, or {@code null} if the number is unknown
     */
    public static AreaRuleLevel fromId(int id) {
        for (AreaRuleLevel level : values()) {
            if (level.id == id) {
                return level;
            }
        }
        return null;
    }
}
