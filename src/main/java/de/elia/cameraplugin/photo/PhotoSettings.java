package de.elia.cameraplugin.photo;

import de.elia.cameraplugin.config.ConfigReader;

/**
 * The section {@code photo-mode} of the config file, for {@code /cam photo}:
 * what disappears for the picture. See {@link de.elia.cameraplugin.config.CamSettings#photo()}.
 */
public final class PhotoSettings {

    /** Whether {@code /cam photo} is there, {@code photo-mode.enabled}. */
    private boolean photoModeEnabled;
    /** Whether it hides the other players, {@code photo-mode.hide-players}. */
    private boolean photoHidePlayers;
    /** Whether it hides the bodies, {@code photo-mode.hide-bodies}. */
    private boolean photoHideBodies;
    /** Whether it hides the mobs and animals, {@code photo-mode.hide-mobs}. */
    private boolean photoHideMobs;
    /** Whether it hides items lying about and experience orbs, {@code photo-mode.hide-items}. */
    private boolean photoHideItems;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public PhotoSettings(ConfigReader config) {
        photoModeEnabled = config.getBoolean("photo-mode.enabled", true);
        photoHidePlayers = config.getBoolean("photo-mode.hide-players", true);
        photoHideBodies = config.getBoolean("photo-mode.hide-bodies", true);
        photoHideMobs = config.getBoolean("photo-mode.hide-mobs", true);
        photoHideItems = config.getBoolean("photo-mode.hide-items", true);
    }

    public boolean enabled() {
        return photoModeEnabled;
    }

    public boolean hidesPlayers() {
        return photoHidePlayers;
    }

    public boolean hidesBodies() {
        return photoHideBodies;
    }

    public boolean hidesMobs() {
        return photoHideMobs;
    }

    public boolean hidesItems() {
        return photoHideItems;
    }
}
