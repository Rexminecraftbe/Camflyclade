package de.elia.cameraplugin.stats;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.list.ListAccess;

/**
 * The section {@code statistics} of the config file, for {@code /cam stats}:
 * what is counted and who sees the numbers of others. See
 * {@link CamSettings#statistics()}.
 */
public final class StatsSettings {

    /** Whether camera mode is counted for {@code /cam stats}, {@code statistics.enabled}. */
    private boolean statsEnabled;
    /** Who may look at the statistics of somebody else, {@code statistics.view-others}. */
    private ListAccess statsViewOthers;
    /** How long a camera mode has to run to be counted, in seconds, {@code statistics.min-seconds}. */
    private int statsMinSeconds;
    /**
     * Whether the way a tour takes the camera of a guest along counts for his
     * statistics, {@code statistics.count-follow-distance}.
     */
    private boolean statsCountFollowDistance;
    /** Whether time without doing anything is left out of the statistics, {@code statistics.skip-afk-time}. */
    private boolean statsSkipAfkTime;
    /** After how many seconds without doing anything it is, {@code statistics.afk-seconds}. */
    private int statsAfkSeconds;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public StatsSettings(ConfigReader config) {
        statsEnabled = config.getBoolean("statistics.enabled", true);
        statsViewOthers = SettingValues.access(config, "statistics.view-others");
        statsMinSeconds = config.getInt("statistics.min-seconds", 5, 0);
        statsCountFollowDistance = config.getBoolean("statistics.count-follow-distance", true);
        statsSkipAfkTime = config.getBoolean("statistics.skip-afk-time", false);
        statsAfkSeconds = config.getInt("statistics.afk-seconds", 60, CamSettings.MIN_AFK_SECONDS);
    }

    public boolean enabled() {
        return statsEnabled;
    }

    public ListAccess viewOthers() {
        return statsViewOthers;
    }

    /** How long a camera mode has to run to be counted, in seconds; 0 counts every one. */
    public int minSeconds() {
        return statsMinSeconds;
    }

    /**
     * Whether the way the tour of {@code /cam follow} takes the camera of a
     * guest along counts for his statistics: for the way flown and how far
     * from the body. Otherwise only what he flies himself.
     */
    public boolean countFollowDistance() {
        return statsCountFollowDistance;
    }

    /**
     * Whether time in camera mode without doing anything is left out of the
     * statistics once it has gone on for {@link #afkMillis()}:
     * out of the time, the average and the longest flight.
     */
    public boolean skipAfkTime() {
        return statsSkipAfkTime;
    }

    /** After how long without doing anything time stops counting for the statistics, in milliseconds. */
    public long afkMillis() {
        return statsAfkSeconds * 1000L;
    }
}
