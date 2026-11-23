package de.elia.cameraplugin.signal;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * The section {@code signal} of the config file, for
 * {@code camera-mode.max-distance-mode: signal}: what the blocks between body
 * and camera cost, the levels of the display, and what happens when the signal
 * is gone. See {@link de.elia.cameraplugin.config.CamSettings#signal()}.
 */
public final class SignalSettings {

    /** The levels of {@code signal.levels}, from the most bars down, as the config file names them. */
    private static final String[] SIGNAL_LEVEL_NAMES = {"excellent", "good", "medium", "weak"};

    /** From how many percent each of {@link #SIGNAL_LEVEL_NAMES} counts out of the box. */
    private static final double[] DEFAULT_SIGNAL_LEVELS = {80.0, 60.0, 40.0, 20.0};

    /**
     * The longest {@code signal.lost-seconds} accepts. Longer than a minute,
     * a camera without a signal is no longer looking for it but flying on
     * without one.
     */
    private static final int MAX_SIGNAL_LOST_SECONDS = 60;

    /** Blocks of range each block in the way takes off the signal, {@code signal.block-cost} and {@code -costs}. */
    private SignalCosts signalCosts;
    /**
     * From how many percent each level of the signal display counts,
     * {@code signal.levels}: excellent, good, medium and weak, going down.
     */
    private double[] signalLevels;
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
        readSignalLevels(config);
        signalLoss = config.getChoice("signal.lost", "back", "back", "end").equalsIgnoreCase("end")
                ? SignalLoss.END
                : SignalLoss.BACK;
        signalLostSeconds = config.getInt("signal.lost-seconds", 3, 0, MAX_SIGNAL_LOST_SECONDS);
    }

    /**
     * Reads {@code signal.levels}: from how many percent the signal display
     * shows which level. Each has to be lower than the one before; if one is
     * not, they are reported together and all of them keep their defaults.
     */
    private void readSignalLevels(ConfigReader config) {
        double[] levels = new double[SIGNAL_LEVEL_NAMES.length];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = config.getDouble("signal.levels." + SIGNAL_LEVEL_NAMES[i], DEFAULT_SIGNAL_LEVELS[i],
                    0.0, 100.0);
        }
        for (int i = 1; i < levels.length; i++) {
            if (levels[i] >= levels[i - 1]) {
                config.warnNotDescending("signal.levels", signalLevelsText(levels),
                        signalLevelsText(DEFAULT_SIGNAL_LEVELS));
                levels = DEFAULT_SIGNAL_LEVELS.clone();
                break;
            }
        }
        signalLevels = levels;
    }

    /** The levels as the note names them, e.g. {@code excellent 80, good 60, medium 40, weak 20}. */
    private static String signalLevelsText(double[] levels) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            double level = levels[i];
            parts.add(SIGNAL_LEVEL_NAMES[i] + " "
                    + (level == Math.rint(level) ? String.valueOf((long) level) : String.valueOf(level)));
        }
        return String.join(", ", parts);
    }

    public SignalCosts costs() {
        return signalCosts;
    }

    /**
     * From how many percent each level of the signal display counts,
     * {@code signal.levels}: excellent, good, medium and weak, each lower
     * than the one before. Not to be changed.
     */
    public double[] levels() {
        return signalLevels;
    }

    public SignalLoss loss() {
        return signalLoss;
    }

    public int lostSeconds() {
        return signalLostSeconds;
    }
}
