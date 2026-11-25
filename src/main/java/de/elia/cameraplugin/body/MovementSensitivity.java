package de.elia.cameraplugin.body;

/**
 * How strongly the camera body reacts to being pushed out of its spot.
 *
 * <p>The levels build on each other: each one allows a kind of movement that
 * the level below it does not. The word in brackets is the value used for
 * {@code body.movement-sensitivity} in the config file. Before, they were
 * numbers, 0 to 2, which an older file may still have.</p>
 */
public enum MovementSensitivity {
    /**
     * Nothing moves the body: neither gravity nor water nor pistons. The
     * movement check is therefore switched off, camera mode never ends because
     * the body was moved.
     */
    FIXED("fixed", 0),
    /**
     * Gravity, water and pistons move the body and every such movement ends
     * camera mode. Players and mobs cannot push it.
     */
    NORMAL("normal", 1),
    /** Players and mobs push the body as well, which also ends camera mode. */
    PUSHABLE("pushable", 2);

    private final String configName;
    private final int oldId;

    MovementSensitivity(String configName, int oldId) {
        this.configName = configName;
        this.oldId = oldId;
    }

    public String getConfigName() {
        return configName;
    }

    /** Whether the body is nailed to its spot and cannot be moved at all. */
    public boolean isFixed() {
        return this == FIXED;
    }

    /** Whether players and mobs are allowed to push the body. */
    public boolean allowsEntityPush() {
        return this == PUSHABLE;
    }

    /**
     * Adjusts the level to what the body can actually show.
     *
     * <p>Being pushed only makes sense where the mannequin is the body itself:
     * the visible mannequin body and the invisible body of either type, see
     * {@link BodyType#isMannequinBody(boolean)}. Behind the visible armour
     * stand of the armorstand body the push would move the invisible mannequin
     * standing inside it while the body everyone sees stays where it is, so
     * the level falls back to {@link #NORMAL} there.</p>
     */
    public MovementSensitivity forBody(boolean mannequinBody) {
        if (this == PUSHABLE && !mannequinBody) {
            return NORMAL;
        }
        return this;
    }

    /** The value of every level in the config file. */
    public static String[] configNames() {
        return java.util.Arrays.stream(values()).map(MovementSensitivity::getConfigName).toArray(String[]::new);
    }

    /** The numbers an older file has, each with the value it stands for now. */
    public static java.util.Map<String, String> oldValues() {
        java.util.Map<String, String> old = new java.util.HashMap<>();
        for (MovementSensitivity sensitivity : values()) {
            old.put(String.valueOf(sensitivity.oldId), sensitivity.configName);
        }
        return old;
    }

    /** The level of a value of the config file, which has to be one of {@link #configNames()}. */
    public static MovementSensitivity byName(String name) {
        for (MovementSensitivity sensitivity : values()) {
            if (sensitivity.configName.equalsIgnoreCase(name)) {
                return sensitivity;
            }
        }
        return NORMAL;
    }
}
