package de.elia.cameraplugin.timelimit;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;


/**
 * The sections {@code time-limit} of the config file, while
 * {@code flight-limit: time-limit}: how long a flight may last - and
 * {@code cooldown}, whatever {@code flight-limit} says: how long a player
 * waits before the next. See {@link de.elia.cameraplugin.config.CamSettings#timeLimit()}.
 *
 * <p>The cooldown was part of {@code time-limit} before it got a section of
 * its own; an older file is read as it was written, see
 * {@link ConfigReader#renamed}.</p>
 */
public final class TimeLimitSettings {

    // Time limit and cooldown settings
    private boolean cooldownsEnabled;
    private int durationSeconds;
    private int cooldownSeconds;
    private boolean showBossbar;
    /** Whether the cooldown shows as a bossbar until it is over, {@code cooldown.show-bossbar}. */
    private boolean cooldownBossbar;
    private BarColor bossbarColor;
    /** The notches of the bossbar of the time limit, {@code time-limit.bossbar-notches}. */
    private BarStyle bossbarStyle;
    /** The colour of the bossbar of the cooldown, {@code cooldown.bossbar-color}. */
    private BarColor cooldownBossbarColor;
    /** The notches of the bossbar of the cooldown, {@code cooldown.bossbar-notches}. */
    private BarStyle cooldownBossbarStyle;
    /** Where the cooldowns are kept, {@code storage}. */
    private StorageMode cooldownStorage;

    /** {@code flight-limit}, which says whether this section counts at all. */
    private final FlightLimit flightLimit;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public TimeLimitSettings(ConfigReader config, FlightLimit flightLimit) {
        this.flightLimit = flightLimit;
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        bossbarStyle = SettingValues.notches(config, "time-limit.bossbar-notches", 0);
        cooldownsEnabled = config.getBoolean(config.renamed("cooldown.enabled", "time-limit.cooldowns-enabled"), false);
        cooldownSeconds = config.getInt(config.renamed("cooldown.seconds", "time-limit.cooldown-seconds"), 120, 0);
        cooldownBossbar = config.getBoolean(
                config.renamed("cooldown.show-bossbar", "time-limit.show-bossbar-cooldown"), true);
        // Both bossbars had the colour and the notches of time-limit, until
        // the cooldown got a section of its own.
        cooldownBossbarColor = config.getEnum(config.renamed("cooldown.bossbar-color", "time-limit.bossbar-color"),
                BarColor.class, BarColor.BLUE);
        cooldownBossbarStyle = SettingValues.notches(config,
                config.renamed("cooldown.bossbar-notches", "time-limit.bossbar-notches"), 0);
        cooldownStorage = SettingValues.storage(config, config.renamed("cooldown.storage", "time-limit.storage"));
    }

    /** Whether {@code flight-limit} is {@code time-limit}. */
    public boolean enabled() {
        return flightLimit == FlightLimit.TIME_LIMIT;
    }

    public boolean cooldownsEnabled() {
        return cooldownsEnabled;
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean showsBossbar() {
        return showBossbar;
    }

    public BarColor bossbarColor() {
        return bossbarColor;
    }

    public boolean showsCooldownBossbar() {
        return cooldownBossbar;
    }

    public BarStyle bossbarStyle() {
        return bossbarStyle;
    }

    public BarColor cooldownBossbarColor() {
        return cooldownBossbarColor;
    }

    public BarStyle cooldownBossbarStyle() {
        return cooldownBossbarStyle;
    }

    public StorageMode cooldownStorage() {
        return cooldownStorage;
    }
}
