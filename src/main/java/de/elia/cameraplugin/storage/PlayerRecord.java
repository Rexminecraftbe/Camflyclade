package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.resume.LastFlight;
import de.elia.cameraplugin.spot.Spot;
import org.bukkit.WeatherType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the plugin keeps about a player from one camera mode to the next: his
 * statistics, the charge of his battery, when his last cooldown began, his
 * spots of {@code /cam spot}, the settings he picked for himself and where
 * his last flight ended. Kept in a file of its own by {@link PlayerStore}.
 */
public final class PlayerRecord {

    /** The name he had when the record was last written, for whoever opens the file. */
    private String name;
    /** How often camera mode has ended for him, which is how often he flew. */
    private long sessions;
    /** How long he has been in camera mode altogether, in seconds. */
    private long totalSeconds;
    /** His longest camera mode, in seconds. */
    private long longestSeconds;
    /** How far his camera has flown altogether, in blocks. */
    private double distanceFlown;
    /** The farthest his camera has ever been from his body, in blocks. */
    private double farthestFromBody;
    /**
     * The charge of his battery in seconds of flight, as it was at
     * {@link #batteryUpdated}, or {@code null} while it has never been used -
     * a battery nobody has touched yet is full.
     */
    private Double batteryCharge;
    /** When {@link #batteryCharge} was last worked out, as {@link System#currentTimeMillis()} counts. */
    private long batteryUpdated;
    /**
     * When his last cooldown of {@code time-limit} began, as
     * {@link System#currentTimeMillis()} counts, or {@code null} while he has
     * never had one. How long it lasts is up to the config file, so a new
     * {@code cooldown-seconds} counts for a cooldown that is running as well.
     */
    private Long cooldownStarted;
    /** His spots of {@code /cam spot}, by {@link Spot#key()}, in the order he saved them. */
    private final Map<String, Spot> spots = new LinkedHashMap<>();
    /**
     * The night vision he picked with {@code /cam nightvision}, or
     * {@code null} while he has picked none and {@code night-vision.mode}
     * holds for him, see {@link de.elia.cameraplugin.preferences.CamPreferences}.
     */
    private NightVisionMode nightVision;
    /** The speed he last flew at, 1 being the speed of the game, or {@code null}. */
    private Double flightSpeed;
    /** The time of {@code /cam time} he last picked, in ticks of the day, or {@code null}. */
    private Long camTime;
    /** The weather of {@code /cam weather} he last picked, or {@code null}. */
    private WeatherType camWeather;
    /** Where his last flight ended, for {@code /cam resume}, or {@code null}. */
    private LastFlight lastFlight;
    /** Whether something has changed since the file was last written. */
    private boolean dirty;

    PlayerRecord() {
    }

    PlayerRecord(String name, long sessions, long totalSeconds, long longestSeconds, double distanceFlown,
                 double farthestFromBody, Double batteryCharge, long batteryUpdated, Long cooldownStarted) {
        this.name = name;
        this.sessions = sessions;
        this.totalSeconds = totalSeconds;
        this.longestSeconds = longestSeconds;
        this.distanceFlown = distanceFlown;
        this.farthestFromBody = farthestFromBody;
        this.batteryCharge = batteryCharge;
        this.batteryUpdated = batteryUpdated;
        this.cooldownStarted = cooldownStarted;
    }

    /**
     * Adds one camera mode that has just ended.
     *
     * @param seconds  how long it ran
     * @param flown    how far the camera flew, in blocks
     * @param farthest the farthest it got from the body, in blocks
     */
    public void addSession(String playerName, long seconds, double flown, double farthest) {
        name = playerName;
        sessions++;
        totalSeconds += seconds;
        longestSeconds = Math.max(longestSeconds, seconds);
        distanceFlown += flown;
        farthestFromBody = Math.max(farthestFromBody, farthest);
        dirty = true;
    }

    /** Sets the statistics back to nothing, {@code /cam stats reset}. The battery stays as it is. */
    public void resetStats() {
        sessions = 0;
        totalSeconds = 0;
        longestSeconds = 0;
        distanceFlown = 0.0;
        farthestFromBody = 0.0;
        dirty = true;
    }

    /** Sets the charge of the battery, as worked out at that moment. */
    public void setBattery(double charge, long at) {
        batteryCharge = charge;
        batteryUpdated = at;
        dirty = true;
    }

    /** Notes that a cooldown begins now. */
    public void setCooldownStarted(long at) {
        cooldownStarted = at;
        dirty = true;
    }

    /** Saves a spot, in place of one of the same name. */
    public void putSpot(String playerName, Spot spot) {
        name = playerName;
        spots.put(spot.key(), spot);
        dirty = true;
    }

    /** Takes a spot away, by {@link Spot#key()}; whether there was one. */
    public boolean removeSpot(String key) {
        if (spots.remove(key) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /** A spot read from the file, which changes nothing that would have to be written. */
    void loadSpot(Spot spot) {
        spots.put(spot.key(), spot);
    }

    /** Sets the night vision he picked, {@code null} for none. */
    public void setNightVision(NightVisionMode mode) {
        nightVision = mode;
        dirty = true;
    }

    /** Sets the speed he last flew at, {@code null} for none. */
    public void setFlightSpeed(Double speed) {
        flightSpeed = speed;
        dirty = true;
    }

    /** Sets the time he last picked, {@code null} for none. */
    public void setCamTime(Long ticks) {
        camTime = ticks;
        dirty = true;
    }

    /** Sets the weather he last picked, {@code null} for none. */
    public void setCamWeather(WeatherType weather) {
        camWeather = weather;
        dirty = true;
    }

    /** Notes where his last flight ended, {@code null} for nowhere. */
    public void setLastFlight(LastFlight flight) {
        lastFlight = flight;
        dirty = true;
    }

    /** His own settings and his last flight as read from the file, which changes nothing to be written. */
    void load(NightVisionMode nightVision, Double flightSpeed, Long camTime, WeatherType camWeather,
              LastFlight lastFlight) {
        this.nightVision = nightVision;
        this.flightSpeed = flightSpeed;
        this.camTime = camTime;
        this.camWeather = camWeather;
        this.lastFlight = lastFlight;
    }

    public String getName() { return name; }
    public long getSessions() { return sessions; }
    public long getTotalSeconds() { return totalSeconds; }
    public long getLongestSeconds() { return longestSeconds; }
    public double getDistanceFlown() { return distanceFlown; }
    public double getFarthestFromBody() { return farthestFromBody; }
    /** The charge of the battery at {@link #getBatteryUpdated()}, or {@code null} for a battery never used. */
    public Double getBatteryCharge() { return batteryCharge; }
    public long getBatteryUpdated() { return batteryUpdated; }
    /** When the last cooldown began, or {@code null} for a player who never had one. */
    public Long getCooldownStarted() { return cooldownStarted; }
    /** His spots, by {@link Spot#key()}, in the order he saved them; not to be changed. */
    public Map<String, Spot> getSpots() { return Collections.unmodifiableMap(spots); }
    /** The night vision he picked, or {@code null} for none. */
    public NightVisionMode getNightVision() { return nightVision; }
    /** The speed he last flew at, or {@code null} for none. */
    public Double getFlightSpeed() { return flightSpeed; }
    /** The time he last picked, or {@code null} for none. */
    public Long getCamTime() { return camTime; }
    /** The weather he last picked, or {@code null} for none. */
    public WeatherType getCamWeather() { return camWeather; }
    /** Where his last flight ended, or {@code null} for nowhere known. */
    public LastFlight getLastFlight() { return lastFlight; }

    boolean isDirty() { return dirty; }
    void markSaved() { dirty = false; }
}
