package de.elia.cameraplugin.timelimit;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;


/**
 * The section {@code time-limit} of the config file, while
 * {@code flight-limit: time-limit}: how long a flight may last, and how long a
 * player waits before the next. See {@link de.elia.cameraplugin.config.CamSettings#timeLimit()}.
 */
public final class TimeLimitSettings {

    // Time limit and cooldown settings
    private boolean cooldownsEnabled;
    private int durationSeconds;
    private int cooldownSeconds;
    private boolean showBossbar;
    /** Whether the cooldown shows as a bossbar until it is over, {@code time-limit.show-bossbar-cooldown}. */
    private boolean cooldownBossbar;
    private BarColor bossbarColor;
    /** The notches of both bossbars of the time limit, {@code time-limit.bossbar-notches}. */
    private BarStyle bossbarStyle;
    /** Where the cooldowns are kept, {@code time-limit.storage}. */
    private StorageMode cooldownStorage;

    /** {@code flight-limit}, which says whether this section counts at all. */
    private final FlightLimit flightLimit;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public TimeLimitSettings(ConfigReader config, FlightLimit flightLimit) {
        this.flightLimit = flightLimit;
        cooldownsEnabled = config.getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        cooldownSeconds = config.getInt("time-limit.cooldown-seconds", 120, 0);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        cooldownBossbar = config.getBoolean("time-limit.show-bossbar-cooldown", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        bossbarStyle = SettingValues.notches(config, "time-limit.bossbar-notches", 0);
        cooldownStorage = SettingValues.storage(config, "time-limit.storage");
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

    public StorageMode cooldownStorage() {
        return cooldownStorage;
    }
}
