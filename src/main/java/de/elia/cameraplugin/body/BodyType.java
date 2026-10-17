package de.elia.cameraplugin.body;

/**
 * Entity that is left behind as the player's body while camera mode is active.
 * The name over it is a text display of its own for both types, see
 * {@link BodySpawner#spawnNameDisplay}.
 *
 * <p>The name in brackets is the value used for {@code body.type} in the
 * config file.</p>
 */
public enum BodyType {
    /**
     * Armour stand wearing the player's head, with an invisible mannequin
     * standing in the same spot that takes the hits. Switched invisible there
     * is no head left to show, and the body is built like
     * {@link #MANNEQUIN}.
     */
    ARMOR_STAND("armorstand"),
    /**
     * Mannequin using the player's own skin, which is hit directly. Switched
     * invisible it stays the body, it is only no longer drawn.
     */
    MANNEQUIN("mannequin");

    private final String configName;

    BodyType(String configName) {
        this.configName = configName;
    }

    /** The value that stands for this type in {@code body.type}. */
    public String getConfigName() {
        return configName;
    }

    /**
     * Whether the body is a mannequin itself, rather than the armour stand of
     * a visible armorstand body with the invisible mannequin standing in it.
     * An invisible body has no head to show, so it is the mannequin for both
     * types.
     */
    public boolean isMannequinBody(boolean visible) {
        return this == MANNEQUIN || !visible;
    }

    /**
     * Resolves the value configured in {@code body.type}: the name of a type,
     * whatever its case.
     *
     * @return the matching type, or {@code null} if the value is unknown
     */
    public static BodyType fromConfig(String value) {
        String text = value.trim();
        for (BodyType type : values()) {
            if (type.configName.equalsIgnoreCase(text)) {
                return type;
            }
        }
        return null;
    }

    /** The names {@code body.type} accepts, for a message about a value it does not. */
    public static String configNames() {
        StringBuilder names = new StringBuilder();
        for (BodyType type : values()) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(type.configName);
        }
        return names.toString();
    }
}
