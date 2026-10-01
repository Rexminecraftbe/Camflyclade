package de.elia.cameraplugin.display;

/**
 * How the name over the camera player keeps to him, see {@link CamNameTag}.
 *
 * <p>The number in brackets is the value used for
 * {@code camera-mode.name-mode} in the config file.</p>
 */
public enum NameMode {
    /**
     * Put back over his head every tick. Always to be seen, but the client
     * moves it a moment after the player, so in fast flight it trails behind
     * a little.
     */
    FOLLOW(1),
    /**
     * Sits on him as a passenger, so the client carries it along with him and
     * it never trails behind. A player with a passenger cannot be teleported,
     * though, so it is taken off before CamFly teleports him and before he
     * changes worlds, and put back on a tick later - gone for that moment.
     * Other plugins cannot teleport him while it sits there: Spigot turns
     * every such teleport down, Paper the ones into another world.
     */
    RIDE(2);

    private final int id;

    NameMode(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Resolves the number configured in {@code camera-mode.name-mode}.
     *
     * @return the matching mode, or {@code null} if the number is unknown
     */
    public static NameMode fromId(int id) {
        for (NameMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return null;
    }
}
