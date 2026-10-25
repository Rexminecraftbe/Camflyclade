package de.elia.cameraplugin.signal;

/**
 * What happens once the radio signal has been gone for
 * {@code signal.lost-seconds}, the values of {@code signal.lost}.
 */
public enum SignalLoss {
    /** {@code back}: the camera goes back to its body, as with {@code /cam back}, and camera mode runs on. */
    BACK,
    /** {@code end}: camera mode ends. */
    END
}
