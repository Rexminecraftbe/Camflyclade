package de.elia.cameraplugin.timelimit;

/**
 * What holds a camera mode to a time, the values of {@code flight-limit}:
 * time-limit, battery and false. Only one of the two at a time - a fixed time
 * per flight and a battery would each say something else about how long he may
 * fly.
 */
public enum FlightLimit {
    /** {@code false}: he flies as long as he likes. */
    OFF,
    /** {@code time-limit}: each flight lasts at most {@code time-limit.duration-seconds}. */
    TIME_LIMIT,
    /** {@code battery}: a battery that empties in flight and charges again afterwards. */
    BATTERY
}
