package de.elia.cameraplugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the config file and writes down every value that does not fit.
 *
 * <p>Bukkit hands back the default without a word when a value has the wrong
 * type, so a typo in the config file is invisible: the server simply behaves
 * differently than the file says. Every value therefore goes through this
 * reader, which checks it and collects a note for the ones it had to replace.
 * The notes are reported once after loading - into the console, and into the
 * chat of whoever started a reload.</p>
 *
 * <p>A value that is not in the file at all is not a mistake: it keeps its
 * default silently, so that a config file from an older version does not
 * produce a wall of notes.</p>
 *
 * <p>The notes carry no wording of their own, only the message key and the
 * pieces that belong into it; see {@link ConfigIssue}.</p>
 */
public final class ConfigReader {

    /** Message keys of the notes, matching the {@code messages} block. */
    public static final String EXPECTED_BOOLEAN = "config-expected-boolean";
    public static final String EXPECTED_NUMBER = "config-expected-number";
    public static final String EXPECTED_TEXT = "config-expected-text";
    public static final String UNKNOWN_VALUE = "config-unknown-value";
    public static final String TOO_SMALL = "config-too-small";

    /** Wording used when a key is missing from the config file. */
    private static final Map<String, String> FALLBACKS = Map.of(
            EXPECTED_BOOLEAN, "&cFalscher Wert für {path}: '{value}'. Erwartet wird true oder false."
                    + " Es wird {used} verwendet.",
            EXPECTED_NUMBER, "&cFalscher Wert für {path}: '{value}'. Erwartet wird eine Zahl."
                    + " Es wird {used} verwendet.",
            EXPECTED_TEXT, "&cFalscher Wert für {path}: '{value}'. Erwartet wird Text."
                    + " Es wird {used} verwendet.",
            UNKNOWN_VALUE, "&cUnbekannter Wert für {path}: '{value}'. Erlaubt sind: {allowed}."
                    + " Es wird {used} verwendet.",
            TOO_SMALL, "&cWert für {path} ist zu klein: {value}. Es wird {min} verwendet.");

    private final FileConfiguration config;
    private final List<ConfigIssue> warnings = new ArrayList<>();

    public ConfigReader(FileConfiguration config) {
        this.config = config;
    }

    /** The collected notes, in the order in which the values were read. */
    public List<ConfigIssue> getWarnings() {
        return warnings;
    }

    /** Adds a note from a check the caller does on its own. */
    public void warn(ConfigIssue issue) {
        warnings.add(issue);
    }

    // ------------------------------------------------------------- Wahrheitswerte

    public boolean getBoolean(String path, boolean def) {
        Object raw = config.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        warnWrongType(path, raw, EXPECTED_BOOLEAN, String.valueOf(def));
        return def;
    }

    /**
     * Checks that every value of a section is a truth value. Used for sections
     * that are read entry by entry while the server runs, where a note would
     * otherwise appear over and over again.
     */
    public void checkBooleanSection(String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(true)) {
            Object raw = section.get(key);
            if (raw == null || raw instanceof Boolean || section.isConfigurationSection(key)) {
                continue;
            }
            warnWrongType(path + "." + key, raw, EXPECTED_BOOLEAN, "true");
        }
    }

    // -------------------------------------------------------------------- Zahlen

    public int getInt(String path, int def) {
        Number value = readNumber(path, def);
        return value == null ? def : value.intValue();
    }

    /** Reads a whole number and raises it when it is below {@code min}. */
    public int getInt(String path, int def, int min) {
        int value = getInt(path, def);
        if (value < min) {
            warnTooSmall(path, String.valueOf(value), String.valueOf(min));
            return min;
        }
        return value;
    }

    /** Reads a whole number and raises it when it is below {@code min}. */
    public long getLong(String path, long def, long min) {
        Number raw = readNumber(path, def);
        long value = raw == null ? def : raw.longValue();
        if (value < min) {
            warnTooSmall(path, String.valueOf(value), String.valueOf(min));
            return min;
        }
        return value;
    }

    public double getDouble(String path, double def) {
        Number value = readNumber(path, def);
        return value == null ? def : value.doubleValue();
    }

    /** Reads a decimal number and raises it when it is below {@code min}. */
    public double getDouble(String path, double def, double min) {
        double value = getDouble(path, def);
        if (value < min) {
            warnTooSmall(path, String.valueOf(value), String.valueOf(min));
            return min;
        }
        return value;
    }

    private Number readNumber(String path, Object def) {
        Object raw = config.get(path);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number value) {
            return value;
        }
        warnWrongType(path, raw, EXPECTED_NUMBER, String.valueOf(def));
        return null;
    }

    // --------------------------------------------------------------------- Text

    /**
     * Reads a piece of text. Truth values and numbers are accepted and turned
     * into text, just like Bukkit does it, so that an unquoted {@code true} in
     * the config file keeps working.
     */
    public String getString(String path, String def) {
        Object raw = config.get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean || raw instanceof Number || raw instanceof String) {
            return raw.toString();
        }
        warnWrongType(path, raw, EXPECTED_TEXT, def);
        return def;
    }

    /** Reads a piece of text that has to be one of {@code allowed}, ignoring case. */
    public String getChoice(String path, String def, String... allowed) {
        String value = getString(path, def);
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return value;
            }
        }
        warnUnknownValue(path, value, String.join(", ", allowed), def);
        return def;
    }

    /** Reads the name of an enum constant, ignoring case. */
    public <T extends Enum<T>> T getEnum(String path, Class<T> type, T def) {
        String value = getString(path, def.name());
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            String allowed = String.join(", ",
                    Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
            warnUnknownValue(path, value, allowed, def.name());
            return def;
        }
    }

    // ------------------------------------------------------------------ Notizen

    private void warnWrongType(String path, Object raw, String messageKey, String used) {
        warnings.add(ConfigIssue.of(messageKey, FALLBACKS.get(messageKey))
                .with("path", path)
                .with("value", raw)
                .with("used", used));
    }

    /**
     * Adds a note that a value is none of the ones the plugin knows. Public so
     * that checks the plugin does on its own read the same as these here.
     *
     * @param allowed the permitted values, already joined for the message
     * @param used    what is used instead
     */
    public void warnUnknownValue(String path, Object value, String allowed, String used) {
        warnings.add(ConfigIssue.of(UNKNOWN_VALUE, FALLBACKS.get(UNKNOWN_VALUE))
                .with("path", path)
                .with("value", value)
                .with("allowed", allowed)
                .with("used", used));
    }

    private void warnTooSmall(String path, String value, String min) {
        warnings.add(ConfigIssue.of(TOO_SMALL, FALLBACKS.get(TOO_SMALL))
                .with("path", path)
                .with("value", value)
                .with("min", min));
    }
}
