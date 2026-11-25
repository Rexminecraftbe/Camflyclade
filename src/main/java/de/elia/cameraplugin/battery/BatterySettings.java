package de.elia.cameraplugin.battery;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.SettingValues;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.timelimit.FlightLimit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

/**
 * The section {@code battery} of the config file, while
 * {@code flight-limit: battery}: how much flight the battery holds and how fast
 * it charges. See {@link de.elia.cameraplugin.config.CamSettings#battery()}.
 */
public final class BatterySettings {

    /** A full battery, in seconds of flight. */
    private int batteryCapacity;
    /** Seconds of flight that come back for every second outside camera mode. */
    private double batteryRecharge;
    /** The charge a start needs at least, in seconds of flight. */
    private int batteryMinStart;
    /** Whether the battery charges while the player is not on the server. */
    private boolean batteryRechargeOffline;
    private boolean batteryBossbar;
    /** Whether the bossbar stays after camera mode until the battery is full, {@code show-bossbar-charging}. */
    private boolean batteryBossbarCharging;
    private BarColor batteryBossbarColor;
    /** The notches of both bossbars of the battery, {@code battery.bossbar-notches}. */
    private BarStyle batteryBossbarStyle;
    /** Where the charge is kept, {@code storage}. */
    private StorageMode batteryStorage;

    /** {@code flight-limit}, which says whether this section counts at all. */
    private final FlightLimit flightLimit;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public BatterySettings(ConfigReader config, FlightLimit flightLimit) {
        this.flightLimit = flightLimit;
        readBattery(config);
    }

    /**
     * Reads the section {@code battery}. The charge a start needs is held to
     * the capacity: more than a full battery would let nobody start at all.
     */
    private void readBattery(ConfigReader config) {
        batteryCapacity = config.getInt("battery.capacity", 300, 1);
        // Without any charging an empty battery would stay empty for good.
        batteryRecharge = config.getDouble("battery.recharge-per-second", 0.5, 0.01);
        batteryMinStart = config.getInt("battery.min-start", Math.min(10, batteryCapacity), 0, batteryCapacity);
        batteryRechargeOffline = config.getBoolean("battery.recharge-offline", true);
        batteryBossbar = config.getBoolean("battery.show-bossbar", true);
        batteryBossbarCharging = config.getBoolean("battery.show-bossbar-charging", true);
        batteryBossbarColor = config.getEnum("battery.bossbar-color", BarColor.class, BarColor.GREEN);
        batteryBossbarStyle = SettingValues.notches(config, "battery.bossbar-notches", 10);
        batteryStorage = SettingValues.storage(config, "battery.storage");
    }

    /** Whether {@code flight-limit} is {@code battery}. */
    public boolean enabled() {
        return flightLimit == FlightLimit.BATTERY;
    }

    public int capacity() {
        return batteryCapacity;
    }

    public double recharge() {
        return batteryRecharge;
    }

    public int minStart() {
        return batteryMinStart;
    }

    public boolean rechargesOffline() {
        return batteryRechargeOffline;
    }

    public boolean showsBossbar() {
        return batteryBossbar;
    }

    public boolean showsBossbarCharging() {
        return batteryBossbarCharging;
    }

    public BarColor bossbarColor() {
        return batteryBossbarColor;
    }

    public BarStyle bossbarStyle() {
        return batteryBossbarStyle;
    }

    public StorageMode storage() {
        return batteryStorage;
    }
}
