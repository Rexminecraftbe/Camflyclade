package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.ConfigReader;

import java.util.Map;

/**
 * The section {@code action-bar} of the config file: what stands above the
 * hotbar while camera mode runs. See {@link de.elia.cameraplugin.config.CamSettings#actionBar()}.
 */
public final class ActionBarSettings {

    /** The value of {@code action-bar.display} for the line at the start alone. */
    public static final String START_ONLY = "start-only";

    /** What {@link #START_ONLY} was called before: {@code false}, which an older file may still have. */
    public static final Map<String, String> OLD_DISPLAY_VALUES = Map.of("false", START_ONLY);

    private boolean actionBarEnabled;
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
        actionBarOnDuration = config.getInt("action-bar.on-duration", 60, 0);
        String display = config.getChoice("action-bar.display", "distance", OLD_DISPLAY_VALUES,
                "distance", "text", START_ONLY);
        actionBarDisplay = switch (display) {
            case "text" -> ActionBarDisplay.TEXT;
            case START_ONLY -> ActionBarDisplay.START;
            default -> ActionBarDisplay.DISTANCE;
        };
    }

    public boolean enabled() {
        return actionBarEnabled;
    }

    public int onDuration() {
        return actionBarOnDuration;
    }

    public ActionBarDisplay display() {
        return actionBarDisplay;
    }
}
