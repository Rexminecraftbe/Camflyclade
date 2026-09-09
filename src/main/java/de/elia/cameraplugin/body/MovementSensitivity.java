package de.elia.cameraplugin.body;

/**
 * How strongly the camera body reacts to being pushed out of its spot.
 *
 * <p>The levels build on each other: each one allows a kind of movement that
 * the level below it does not. The number in brackets is the value used for
 * {@code body.movement-sensitivity} in the config file.</p>
 */
public enum MovementSensitivity {
    /**
     * Nothing moves the body: neither gravity nor water nor pistons. The
     * movement check is therefore switched off, camera mode never ends because
     * the body was moved.
     */
    FIXED(0),
    /**
     * Gravity, water and pistons move the body and every such movement ends
     * camera mode. Players and mobs cannot push it.
     */
    NORMAL(1),
    /** Players and mobs push the body as well, which also ends camera mode. */
    PUSHABLE(2);

    private final int id;

    MovementSensitivity(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
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
     * Adjusts the level to what the body type can actually show.
     *
     * <p>Being pushed only makes sense for the visible mannequin. With an
     * armour stand body the push would move the invisible mannequin standing
     * inside it while the body everyone sees stays where it is, so the level
     * falls back to {@link #NORMAL} there.</p>
     */
    public MovementSensitivity forBodyType(BodyType bodyType) {
        if (this == PUSHABLE && bodyType != BodyType.MANNEQUIN) {
            return NORMAL;
        }
        return this;
    }

    /**
     * Resolves the number configured in {@code body.movement-sensitivity}.
     *
     * @return the matching level, or {@code null} if the number is unknown
     */
    public static MovementSensitivity fromId(int id) {
        for (MovementSensitivity sensitivity : values()) {
            if (sensitivity.id == id) {
                return sensitivity;
            }
        }
        return null;
    }
}
