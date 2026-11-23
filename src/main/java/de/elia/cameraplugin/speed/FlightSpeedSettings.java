package de.elia.cameraplugin.speed;

import de.elia.cameraplugin.config.ConfigReader;

/**
 * The section {@code flight-speed} of the config file: how fast the camera
 * flies at the start, and how far the mouse wheel turns it up and down. See
 * {@link de.elia.cameraplugin.config.CamSettings#flightSpeed()}.
 */
public final class FlightSpeedSettings {

    /**
     * Fastest speed {@code flight-speed} accepts, 1 being the speed of the
     * game. Sprinting at it the camera covers more than five blocks a tick:
     * any faster and the world does not load in time ahead of it, nor does
     * the wall of {@code border-mode: barrier} get built.
     */
    public static final double MAX_FLIGHT_SPEED = 5.0;

    /** Slowest speed {@code flight-speed} accepts: slower than that hardly moves at all. */
    private static final double MIN_FLIGHT_SPEED = 0.1;

    /** Whether the mouse wheel changes the speed, {@code flight-speed.mouse-wheel}. */
    private boolean flightSpeedWheel;
    /** The speeds of {@code flight-speed}, 1 being the speed of the game. */
    private double flightSpeedStart;
    private double flightSpeedMin;
    private double flightSpeedMax;
    private double flightSpeedStep;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public FlightSpeedSettings(ConfigReader config) {
        readFlightSpeed(config);
    }

    /**
     * Reads the section {@code flight-speed}. The slowest speed is read first,
     * the others are held to it: the fastest is no slower, and the one at the
     * start lies between the two. Their defaults follow along, so that a
     * slowest speed above 3 does not set off a note about a fastest speed that
     * is not even in the file.
     */
    private void readFlightSpeed(ConfigReader config) {
        flightSpeedWheel = config.getBoolean("flight-speed.mouse-wheel", true);
        flightSpeedMin = config.getDouble("flight-speed.min", 0.5, MIN_FLIGHT_SPEED, MAX_FLIGHT_SPEED);
        flightSpeedMax = config.getDouble("flight-speed.max", Math.max(3.0, flightSpeedMin),
                flightSpeedMin, MAX_FLIGHT_SPEED);
        flightSpeedStart = config.getDouble("flight-speed.start",
                Math.min(Math.max(1.0, flightSpeedMin), flightSpeedMax), flightSpeedMin, flightSpeedMax);
        flightSpeedStep = config.getDouble("flight-speed.step", 0.5, 0.05, MAX_FLIGHT_SPEED);
    }

    public boolean mouseWheel() {
        return flightSpeedWheel;
    }

    public double start() {
        return flightSpeedStart;
    }

    public double min() {
        return flightSpeedMin;
    }

    public double max() {
        return flightSpeedMax;
    }

    public double step() {
        return flightSpeedStep;
    }
}
