package de.elia.cameraplugin.display;

/**
 * How the name over the camera player keeps to him, see {@link CamNameTag}.
 *
 * <p>The word in brackets is the value used for
 * {@code camera-mode.name-mode} in the config file. Before, they were
 * numbers, 1 and 2, which an older file may still have.</p>
 */
public enum NameMode {
    /**
     * Put back over his head every tick. Always to be seen, but the client
     * moves it a moment after the player, so in fast flight it trails behind
     * a little.
     */
    FOLLOW("move", 1),
    /**
     * Sits on him as a passenger, so the client carries it along with him and
     * it never trails behind. A player with a passenger cannot be teleported,
     * though, so it is taken off before CamFly teleports him and before he
     * changes worlds, and put back on a tick later - gone for that moment.
     * Other plugins cannot teleport him while it sits there: Spigot turns
     * every such teleport down, Paper the ones into another world.
     */
    RIDE("ride", 2);

    private final String configName;
    private final int oldId;

    NameMode(String configName, int oldId) {
        this.configName = configName;
        this.oldId = oldId;
    }

    public String getConfigName() {
        return configName;
    }

    /** The value of every mode in the config file. */
    public static String[] configNames() {
        return java.util.Arrays.stream(values()).map(NameMode::getConfigName).toArray(String[]::new);
    }

    /** The numbers an older file has, each with the value it stands for now. */
    public static java.util.Map<String, String> oldValues() {
        java.util.Map<String, String> old = new java.util.HashMap<>();
        for (NameMode mode : values()) {
            old.put(String.valueOf(mode.oldId), mode.configName);
        }
        return old;
    }

    /** The mode of a value of the config file, which has to be one of {@link #configNames()}. */
    public static NameMode byName(String name) {
        for (NameMode mode : values()) {
            if (mode.configName.equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return FOLLOW;
    }
}
