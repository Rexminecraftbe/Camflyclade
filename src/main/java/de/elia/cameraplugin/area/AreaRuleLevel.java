package de.elia.cameraplugin.area;

/**
 * How far the rules about where camera mode is allowed reach - the values of
 * {@code cam-area.level}.
 *
 * <p>The levels build on each other: each one forbids something the level
 * below it still allows. The word in brackets is the value used in the
 * config file. Before, they were numbers, 0 to 2, which an older file may
 * still have.</p>
 */
public enum AreaRuleLevel {
    /**
     * The rules are not looked at. Camera mode works in every biome and in
     * every dimension, the two lists in the config file stay untouched.
     */
    OFF("false", 0),
    /**
     * Camera mode cannot be started in a forbidden area. Whoever started it
     * somewhere else may still fly into one.
     */
    START("start", 1),
    /**
     * Camera mode cannot be started in a forbidden area and cannot be carried
     * into one either: at its border the player simply does not get any
     * further, the way {@code camera-mode.max-distance} stops him.
     */
    START_AND_FLIGHT("always", 2);

    private final String configName;
    private final int oldId;

    AreaRuleLevel(String configName, int oldId) {
        this.configName = configName;
        this.oldId = oldId;
    }

    public String getConfigName() {
        return configName;
    }

    /** Whether a forbidden area keeps camera mode from being started. */
    public boolean blocksStart() {
        return this != OFF;
    }

    /** Whether a forbidden area cannot be flown into while in camera mode. */
    public boolean blocksFlight() {
        return this == START_AND_FLIGHT;
    }

    /** The value of every level in the config file. */
    public static String[] configNames() {
        return java.util.Arrays.stream(values()).map(AreaRuleLevel::getConfigName).toArray(String[]::new);
    }

    /** The numbers an older file has, each with the value it stands for now. */
    public static java.util.Map<String, String> oldValues() {
        java.util.Map<String, String> old = new java.util.HashMap<>();
        for (AreaRuleLevel level : values()) {
            old.put(String.valueOf(level.oldId), level.configName);
        }
        return old;
    }

    /**
     * The level of a value of the config file, which has to be one of
     * {@link #configNames()}; the strictest one otherwise - a rule nobody can
     * read is better kept than quietly dropped.
     */
    public static AreaRuleLevel byName(String name) {
        for (AreaRuleLevel level : values()) {
            if (level.configName.equalsIgnoreCase(name)) {
                return level;
            }
        }
        return START_AND_FLIGHT;
    }
}
