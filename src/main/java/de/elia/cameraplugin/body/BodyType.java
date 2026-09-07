package de.elia.cameraplugin.body;

/**
 * Entity that is left behind as the player's body while camera mode is active.
 *
 * <p>The number in brackets is the value used for {@code body.type} in the
 * config file.</p>
 */
public enum BodyType {
    /** Armour stand wearing the player's head. Works on every supported version. */
    ARMOR_STAND(1),
    /** Mannequin using the player's own skin. Requires Minecraft 1.21.9 or newer. */
    MANNEQUIN(2);

    private final int id;

    BodyType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
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
