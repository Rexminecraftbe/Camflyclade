package de.elia.cameraplugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
 */
public final class ConfigReader {

    /** Above this many constants an enum is not listed in the note any more. */
    private static final int MAX_LISTED_CONSTANTS = 10;

    private final FileConfiguration config;
    private final List<String> warnings = new ArrayList<>();

    public ConfigReader(FileConfiguration config) {
        this.config = config;
    }

    /** The collected notes, in the order in which the values were read. */
    public List<String> getWarnings() {
        return warnings;
    }

    /** Adds a note from a check the caller does on its own. */
    public void warn(String message) {
        warnings.add(message);
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
        warnWrongType(path, raw, "true oder false", String.valueOf(def));
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
            warnWrongType(path + "." + key, raw, "true oder false", "true");
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
        warnWrongType(path, raw, "eine Zahl", String.valueOf(def));
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
        warnWrongType(path, raw, "Text", def);
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
        warnings.add("Unbekannter Wert für " + path + ": '" + value + "'. Erlaubt sind: "
                + String.join(", ", allowed) + ". Es wird " + def + " verwendet.");
        return def;
    }

    /** Reads the name of an enum constant, ignoring case. */
    public <T extends Enum<T>> T getEnum(String path, Class<T> type, T def) {
        String value = getString(path, def.name());
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            T[] constants = type.getEnumConstants();
            String allowed = constants.length <= MAX_LISTED_CONSTANTS
                    ? " Erlaubt sind: " + String.join(", ", Arrays.stream(constants).map(Enum::name).toList()) + "."
                    : "";
            warnings.add("Unbekannter Wert für " + path + ": '" + value + "'." + allowed
                    + " Es wird " + def.name() + " verwendet.");
            return def;
        }
    }

    // ------------------------------------------------------------------ Notizen

    private void warnWrongType(String path, Object raw, String expected, String used) {
        warnings.add("Falscher Wert für " + path + ": '" + raw + "'. Erwartet wird "
                + expected + ". Es wird " + used + " verwendet.");
    }

    private void warnTooSmall(String path, String value, String min) {
        warnings.add("Wert für " + path + " ist zu klein: " + value
                + ". Es wird " + min + " verwendet.");
    }
}
