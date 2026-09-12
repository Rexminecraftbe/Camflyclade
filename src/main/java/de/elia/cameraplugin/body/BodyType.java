package de.elia.cameraplugin.body;

/**
 * Entity that is left behind as the player's body while camera mode is active.
 *
 * <p>The number in brackets is the value used for {@code body.type} in the
 * config file.</p>
 */
public enum BodyType {
    /**
     * Armour stand wearing the player's head, with an invisible mannequin
     * standing in the same spot that takes the hits.
     */
    ARMOR_STAND(1),
    /**
     * Visible mannequin using the player's own skin, which is hit directly.
     * Switched invisible it is built like {@link #ARMOR_STAND}, because a
     * mannequin that is not drawn does not show its name either.
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
