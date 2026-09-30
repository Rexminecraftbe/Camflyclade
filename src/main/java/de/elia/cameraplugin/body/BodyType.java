package de.elia.cameraplugin.body;

/**
 * Entity that is left behind as the player's body while camera mode is active.
 * The name over it is a text display of its own for both types, see
 * {@link BodySpawner#spawnNameDisplay}.
 *
 * <p>The number in brackets is the value used for {@code body.type} in the
 * config file.</p>
 */
public enum BodyType {
    /**
     * Armour stand wearing the player's head, with an invisible mannequin
     * standing in the same spot that takes the hits. Switched invisible there
     * is no head left to show, and the body is built like
     * {@link #MANNEQUIN}.
     */
    ARMOR_STAND(1),
    /**
     * Mannequin using the player's own skin, which is hit directly. Switched
     * invisible it stays the body, it is only no longer drawn.
     */
    MANNEQUIN(2);

    private final int id;

    BodyType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Whether the body is a mannequin itself, rather than the armour stand of
     * a visible type 1 with the invisible mannequin standing in it. An
     * invisible body has no head to show, so it is the mannequin for both
     * types.
     */
    public boolean isMannequinBody(boolean visible) {
        return this == MANNEQUIN || !visible;
    }

    /**
     * Resolves the number configured in {@code body.type}.
     *
     * @return the matching type, or {@code null} if the number is unknown
     */
    public static BodyType fromId(int id) {
        for (BodyType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
