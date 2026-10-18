package de.elia.cameraplugin.storage;

/**
 * What the plugin keeps about a player from one camera mode to the next: his
 * statistics and the charge of his battery. Kept in a file of its own by
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
    /** Whether something has changed since the file was last written. */
    private boolean dirty;

    PlayerRecord() {
    }

    PlayerRecord(String name, long sessions, long totalSeconds, long longestSeconds, double distanceFlown,
                 double farthestFromBody, Double batteryCharge, long batteryUpdated) {
        this.name = name;
        this.sessions = sessions;
        this.totalSeconds = totalSeconds;
        this.longestSeconds = longestSeconds;
        this.distanceFlown = distanceFlown;
        this.farthestFromBody = farthestFromBody;
        this.batteryCharge = batteryCharge;
        this.batteryUpdated = batteryUpdated;
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

    /** Sets the charge of the battery, as worked out at that moment. */
    public void setBattery(double charge, long at) {
        batteryCharge = charge;
        batteryUpdated = at;
        dirty = true;
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

    boolean isDirty() { return dirty; }
    void markSaved() { dirty = false; }
}
