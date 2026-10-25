package de.elia.cameraplugin.movement;

/**
 * How {@code camera-mode.max-distance} holds the camera to its body, the
 * values of {@code camera-mode.max-distance-mode}.
 */
public enum DistanceMode {
    /**
     * {@code fixed}: as far as {@code max-distance} and not a block further,
     * stopped there the way {@code camera-mode.border-mode} has it - the way
     * camera mode always did it before there was a choice.
     */
    FIXED,
    /**
     * {@code signal}: as far as a radio signal from the body reaches, see
     * {@link de.elia.cameraplugin.signal.CamSignal}. {@code max-distance} is
     * its range in the open; every block in between takes some of it away.
     * There is no border to run into: once the signal is gone, the camera goes
     * back to its body or camera mode ends, as {@code signal.lost} has it.
     */
    SIGNAL
}
