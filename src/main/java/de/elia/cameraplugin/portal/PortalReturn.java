package de.elia.cameraplugin.portal;

/**
 * Where a camera player is put down who has to be brought back from the other
 * side of a portal - the values of {@code portals.return-to}.
 */
public enum PortalReturn {
    /** Back to his body, the mannequin or armour stand he left behind. */
    BODY("body"),
    /** Back to the portal he set out through. */
    PORTAL("portal");

    private final String configName;

    PortalReturn(String configName) {
        this.configName = configName;
    }

    /** The name of this target the way it is written in the config file. */
    public String getConfigName() {
        return configName;
    }

    /** The names of all targets, in the order they are written above. */
    public static String[] configNames() {
        String[] names = new String[values().length];
        for (int i = 0; i < names.length; i++) {
            names[i] = values()[i].configName;
        }
        return names;
    }

    /**
     * Resolves the name out of the config file, ignoring case. Falls back to
     * {@link #BODY}, which is the one target that is always there.
     */
    public static PortalReturn byName(String name) {
        for (PortalReturn target : values()) {
            if (target.configName.equalsIgnoreCase(name)) {
                return target;
            }
        }
        return BODY;
    }
}
