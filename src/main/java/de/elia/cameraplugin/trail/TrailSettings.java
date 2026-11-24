package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;

/**
 * The section {@code cam-trail} of the config file, for {@code /cam trail}:
 * how many flights are kept, where and for how long, and how long their line
 * is shown. See {@link de.elia.cameraplugin.config.CamSettings#trail()}.
 */
public final class TrailSettings {

    /**
     * The longest {@code cam-trail.show-seconds}, {@code show-min-seconds}
     * and {@code show-max-seconds} accept: ten minutes.
     */
    private static final int MAX_TRAIL_SHOW_SECONDS = 600;

    /** The most {@code cam-trail.show-min-blocks} and {@code show-max-blocks} accept. */
    private static final int MAX_TRAIL_SHOW_BLOCKS = 100_000;

    /**
     * The most flights {@code cam-trail.flights} keeps of each player in
     * memory only, {@code storage: memory}. Kept in files there is no limit
     * but the one {@code flights} sets.
     */
    public static final int MAX_TRAIL_FLIGHTS = 20;

    /** Whether flights are recorded for {@code /cam trail}, {@code cam-trail.enabled}. */
    private boolean trailEnabled;
    /** What decides how long {@code /cam trail} shows a flight, {@code cam-trail.show-mode}. */
    private TrailShowMode trailShowMode;
    /** How long {@code fixed} shows every flight, in seconds, {@code cam-trail.show-seconds}. */
    private int trailShowSeconds;
    /** Up to how many blocks {@code dynamic} shows a flight the shortest, {@code cam-trail.show-min-blocks}. */
    private int trailShowMinBlocks;
    /** How long {@code dynamic} shows a flight at least, in seconds, {@code cam-trail.show-min-seconds}. */
    private int trailShowMinSeconds;
    /** From how many blocks on {@code dynamic} shows a flight the longest, {@code cam-trail.show-max-blocks}. */
    private int trailShowMaxBlocks;
    /** How long {@code dynamic} shows a flight at most, in seconds, {@code cam-trail.show-max-seconds}. */
    private int trailShowMaxSeconds;
    /** How many of his last flights are kept of each player, {@code cam-trail.flights}; 0 for all of them. */
    private int trailFlights;
    /** Where the flights are kept, {@code cam-trail.storage}. */
    private StorageMode trailStorage;
    /** After how many days a flight is deleted, {@code cam-trail.keep-days}; 0 for never. */
    private int trailKeepDays;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public TrailSettings(ConfigReader config) {
        trailEnabled = config.getBoolean("cam-trail.enabled", true);
        // An older file, without the switch but with show-seconds, goes on as
        // it was written: every line as long as that, whatever the flight.
        trailShowMode = !config.has("cam-trail.show-mode") && config.has("cam-trail.show-seconds")
                ? TrailShowMode.FIXED
                : config.getEnum("cam-trail.show-mode", TrailShowMode.class, TrailShowMode.DYNAMIC);
        trailShowSeconds = config.getInt("cam-trail.show-seconds", 30, 1, MAX_TRAIL_SHOW_SECONDS);
        trailShowMinBlocks = config.getInt("cam-trail.show-min-blocks", 50, 0, MAX_TRAIL_SHOW_BLOCKS - 1);
        trailShowMinSeconds = config.getInt("cam-trail.show-min-seconds", 10, 1, MAX_TRAIL_SHOW_SECONDS);
        trailShowMaxBlocks = config.getInt("cam-trail.show-max-blocks", 1500, trailShowMinBlocks + 1,
                MAX_TRAIL_SHOW_BLOCKS);
        trailShowMaxSeconds = config.getInt("cam-trail.show-max-seconds", 300, trailShowMinSeconds,
                MAX_TRAIL_SHOW_SECONDS);
        trailStorage = SettingValues.storage(config, "cam-trail.storage");
        // In files as many as he says, 0 for all of them; in memory up to
        // MAX_TRAIL_FLIGHTS, and 0 is as many as that.
        if (trailStorage == StorageMode.FILE) {
            trailFlights = config.getInt("cam-trail.flights", 5, 0);
        } else {
            int flights = config.getInt("cam-trail.flights", 5, 0, MAX_TRAIL_FLIGHTS);
            trailFlights = flights == 0 ? MAX_TRAIL_FLIGHTS : flights;
        }
        trailKeepDays = config.getInt("cam-trail.keep-days", 30, 0);
    }

    public boolean enabled() {
        return trailEnabled;
    }

    public TrailShowMode showMode() {
        return trailShowMode;
    }

    public int showSeconds() {
        return trailShowSeconds;
    }

    public int showMinBlocks() {
        return trailShowMinBlocks;
    }

    public int showMinSeconds() {
        return trailShowMinSeconds;
    }

    public int showMaxBlocks() {
        return trailShowMaxBlocks;
    }

    public int showMaxSeconds() {
        return trailShowMaxSeconds;
    }

    /** How many flights are kept of each player; 0 for all of them, kept in files. */
    public int flights() {
        return trailFlights;
    }

    public StorageMode storage() {
        return trailStorage;
    }

    /** After how many days a flight is deleted; 0 for never. */
    public int keepDays() {
        return trailKeepDays;
    }
}
