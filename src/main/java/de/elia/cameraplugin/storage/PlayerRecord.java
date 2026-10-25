package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.spot.Spot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the plugin keeps about a player from one camera mode to the next: his
 * statistics, the charge of his battery, when his last cooldown began and
 * his spots of {@code /cam spot}. Kept in a file of its own by
 * {@link PlayerStore}.
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

    boolean isDirty() { return dirty; }
    void markSaved() { dirty = false; }
}
