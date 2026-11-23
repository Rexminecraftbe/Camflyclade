package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.ConfigReader;

import java.util.Locale;

/**
 * The section {@code action-bar} of the config file: what stands above the
 * hotbar while camera mode runs. See {@link de.elia.cameraplugin.config.CamSettings#actionBar()}.
 */
public final class ActionBarSettings {

    private boolean actionBarEnabled;
    private int actionBarOffDuration;
    /** How long {@code actionbar-on} stands at the start before the distance, in ticks, {@code action-bar.on-duration}. */
    private int actionBarOnDuration;
    /** What the action bar shows while camera mode runs, {@code action-bar.display}. */
    private ActionBarDisplay actionBarDisplay;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public ActionBarSettings(ConfigReader config) {
        actionBarEnabled = config.getBoolean("action-bar.enabled", true);
        actionBarOffDuration = config.getInt("action-bar.off-duration", 10, 0);
        actionBarOnDuration = config.getInt("action-bar.on-duration", 60, 0);
        String display = config.getChoice("action-bar.display", "distance", "distance", "text", "false")
                .toLowerCase(Locale.ROOT);
        actionBarDisplay = switch (display) {
            case "text" -> ActionBarDisplay.TEXT;
            case "false" -> ActionBarDisplay.START;
            default -> ActionBarDisplay.DISTANCE;
        };
    }

    public boolean enabled() {
        return actionBarEnabled;
    }

    public int offDuration() {
        return actionBarOffDuration;
    }

    public int onDuration() {
        return actionBarOnDuration;
    }

    public ActionBarDisplay display() {
        return actionBarDisplay;
    }
}
