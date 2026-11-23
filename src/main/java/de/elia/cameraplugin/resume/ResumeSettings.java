package de.elia.cameraplugin.resume;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;

/**
 * The section {@code resume-command} of the config file, for
 * {@code /cam resume}: whether the end of the last flight is kept, and what of
 * it comes back. See {@link de.elia.cameraplugin.config.CamSettings#resume()}.
 */
public final class ResumeSettings {

    /** Whether {@code /cam resume} goes on where the last flight ended, {@code resume-command.enabled}. */
    private boolean resumeEnabled;
    /** Where the end of the last flight is kept, {@code resume-command.storage}. */
    private StorageMode resumeStorage;
    /** Whether {@code /cam resume} shows the time of day of the last flight again, {@code resume-command.time}. */
    private boolean resumeTime;
    /** Whether it shows the weather of the last flight again, {@code resume-command.weather}. */
    private boolean resumeWeather;
    /** Whether it gives the night vision of the last flight again, {@code resume-command.night-vision}. */
    private boolean resumeNightVision;

    /** {@code cam-time.enabled}, without which no time comes back either. */
    private final boolean camTimeEnabled;
    /** {@code cam-weather.enabled}, without which no weather comes back either. */
    private final boolean camWeatherEnabled;
    /** {@code night-vision.player-choice}, without which no choice of night vision comes back either. */
    private final boolean nightVisionPlayerChoice;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public ResumeSettings(ConfigReader config, boolean camTimeEnabled, boolean camWeatherEnabled, boolean nightVisionPlayerChoice) {
        this.camTimeEnabled = camTimeEnabled;
        this.camWeatherEnabled = camWeatherEnabled;
        this.nightVisionPlayerChoice = nightVisionPlayerChoice;
        resumeEnabled = config.getBoolean("resume-command.enabled", true);
        resumeStorage = SettingValues.storage(config, "resume-command.storage");
        resumeTime = config.getBoolean("resume-command.time", true);
        resumeWeather = config.getBoolean("resume-command.weather", true);
        resumeNightVision = config.getBoolean("resume-command.night-vision", true);
    }

    public boolean enabled() {
        return resumeEnabled;
    }

    public StorageMode storage() {
        return resumeStorage;
    }

    /** Whether {@code /cam resume} shows the time of day of the last flight again, while {@code cam-time} is on. */
    public boolean time() {
        return resumeTime && camTimeEnabled;
    }

    /** Whether {@code /cam resume} shows the weather of the last flight again, while {@code cam-weather} is on. */
    public boolean weather() {
        return resumeWeather && camWeatherEnabled;
    }

    /**
     * Whether {@code /cam resume} gives the night vision of the last flight
     * again, while {@code night-vision.player-choice} lets anybody have one of
     * his own - as a spot gives its night vision.
     */
    public boolean nightVision() {
        return resumeNightVision && nightVisionPlayerChoice;
    }
}
