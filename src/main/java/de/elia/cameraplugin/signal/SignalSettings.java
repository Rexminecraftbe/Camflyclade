package de.elia.cameraplugin.signal;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;

/**
 * The section {@code signal} of the config file, for
 * {@code camera-mode.max-distance-mode: signal}: what the blocks between body
 * and camera cost, and what happens when the signal
 * is gone. See {@link de.elia.cameraplugin.config.CamSettings#signal()}.
 */
public final class SignalSettings {

    /**
     * From how many percent each level of the signal display counts: five
     * bars (excellent), four (good), three (medium) and two (weak), going
     * down; below, one. Evenly spread, as the bars are. The section
     * {@code signal.levels} set them once.
     */
    private static final double[] SIGNAL_LEVELS = {80.0, 60.0, 40.0, 20.0};

    /**
     * The longest {@code signal.lost-seconds} accepts. Longer than a minute,
     * a camera without a signal is no longer looking for it but flying on
     * without one.
     */
    private static final int MAX_SIGNAL_LOST_SECONDS = 60;

    /** Blocks of range each block in the way takes off the signal, {@code signal.block-cost} and {@code -costs}. */
    private SignalCosts signalCosts;
    /** What happens once the signal is lost, {@code signal.lost}. */
    private SignalLoss signalLoss;
    /** How long the camera has to fly back into the signal, in seconds, {@code signal.lost-seconds}. */
    private int signalLostSeconds;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public SignalSettings(ConfigReader config, Messages messages) {
        readSignal(config, messages);
    }

    /**
     * Reads the section {@code signal}, for {@code max-distance-mode: signal}.
     * Read whatever the mode, so that a value that does not fit is reported
     * now and not only once somebody switches the signal on.
     */
    private void readSignal(ConfigReader config, Messages messages) {
        signalCosts = SignalCosts.read(config, messages.configMessage("config-allowed-signal-costs"));
        signalLoss = config.getChoice("signal.lost", "back", "back", "end").equalsIgnoreCase("end")
                ? SignalLoss.END
                : SignalLoss.BACK;
        signalLostSeconds = config.getInt("signal.lost-seconds", 3, 0, MAX_SIGNAL_LOST_SECONDS);
    }

    public SignalCosts costs() {
        return signalCosts;
    }

    /**
     * From how many percent each level of the signal display counts:
     * excellent, good, medium and weak, each lower than the one before. Not to
     * be changed.
     */
    public double[] levels() {
        return SIGNAL_LEVELS;
    }

    public SignalLoss loss() {
        return signalLoss;
    }

    public int lostSeconds() {
        return signalLostSeconds;
    }
}
