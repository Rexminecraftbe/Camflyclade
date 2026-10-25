package de.elia.cameraplugin.config;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** Message keys of the notes, matching the {@code messages} block of the language file. */
    private static final String EXPECTED_BOOLEAN = "config-expected-boolean";
    private static final String EXPECTED_NUMBER = "config-expected-number";
    private static final String EXPECTED_WHOLE_NUMBER = "config-expected-whole-number";
    private static final String EXPECTED_TEXT = "config-expected-text";
    private static final String EXPECTED_LIST = "config-expected-list";
    private static final String EXPECTED_AMOUNTS = "config-expected-amounts";
    private static final String UNKNOWN_VALUE = "config-unknown-value";
    private static final String UNKNOWN_ENTRY = "config-unknown-entry";
    private static final String UNKNOWN_KEY = "config-unknown-key";
    private static final String ENTRY_IN_BOTH_LISTS = "config-entry-in-both-lists";
    private static final String TOO_SMALL = "config-too-small";
    private static final String TOO_LARGE = "config-too-large";
    private static final String OUT_OF_RANGE = "config-out-of-range";

    private final FileConfiguration config;
    private final List<ConfigIssue> warnings = new ArrayList<>();

    public ConfigReader(FileConfiguration config) {
        this.config = config;
    }

    /** The collected notes, in the order in which the values were read. */
    public List<ConfigIssue> getWarnings() {
        return warnings;
    }

    // ------------------------------------------------------------ truth values

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

    // ----------------------------------------------------------------- numbers

    public int getInt(String path, int def) {
        Long value = readWholeNumber(path, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
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

    /**
     * Reads a whole number and keeps it between {@code min} and
     * {@code max}, for a value that costs more work the larger it gets.
     */
    public int getInt(String path, int def, int min, int max) {
        int value = getInt(path, def, min);
        if (value > max) {
            warnTooLarge(path, String.valueOf(value), String.valueOf(max));
            return max;
        }
        return value;
    }

    /** Reads a whole number and raises it when it is below {@code min}. */
    public long getLong(String path, long def, long min) {
        Long raw = readWholeNumber(path, def, Long.MIN_VALUE, Long.MAX_VALUE);
        long value = raw == null ? def : raw;
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

    /**
     * Reads a decimal number and keeps it between {@code min} and
     * {@code max}, for a value that costs more work the larger it gets.
     */
    public double getDouble(String path, double def, double min, double max) {
        double value = getDouble(path, def, min);
        if (value > max) {
            warnTooLarge(path, String.valueOf(value), String.valueOf(max));
            return max;
        }
        return value;
    }

    /**
     * Reads a number of any kind. {@code .nan} and {@code .inf} are numbers to
     * YAML, but none the plugin can work with: not-a-number compares as false
     * with everything, so it would slip past every limit, and an endless value
     * keeps no limit either.
     */
    private Number readNumber(String path, Object def) {
        Object raw = config.get(path);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number value) || Double.isNaN(value.doubleValue())) {
            warnWrongType(path, raw, EXPECTED_NUMBER, String.valueOf(def));
            return null;
        }
        if (Double.isInfinite(value.doubleValue())) {
            warnOutOfRange(path, raw, String.valueOf(def));
            return null;
        }
        return value;
    }

    /**
     * Reads a whole number that fits between {@code lowest} and
     * {@code highest}, the range of the type it is kept in.
     *
     * <p>A decimal number is not cut down to a whole one, and one that does not
     * fit is not wrapped around, the way Java would do it: both would quietly
     * turn into a value the file never said, 2.5 into 2 and 3000000000 into a
     * negative one. Neither says which whole number was meant, so the default
     * is used instead.</p>
     */
    private Long readWholeNumber(String path, Object def, long lowest, long highest) {
        Number value = readNumber(path, def);
        if (value == null) {
            return null;
        }
        BigDecimal exact = new BigDecimal(value.toString());
        if (exact.remainder(BigDecimal.ONE).signum() != 0) {
            warnWrongType(path, value, EXPECTED_WHOLE_NUMBER, String.valueOf(def));
            return null;
        }
        if (exact.compareTo(BigDecimal.valueOf(lowest)) < 0
                || exact.compareTo(BigDecimal.valueOf(highest)) > 0) {
            warnOutOfRange(path, value, String.valueOf(def));
            return null;
        }
        return exact.longValueExact();
    }

    // -------------------------------------------------------------------- text

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

    /**
     * Reads a list of text entries. Truth values and numbers inside the list
     * are turned into text, the way {@link #getString(String, String)} does it;
     * an entry that is neither - a whole section, say - is left out and
     * reported.
     *
     * @param def what is used when the key is not in the file
     */
    public List<String> getStringList(String path, List<String> def) {
        Object raw = config.get(path);
        if (raw == null) {
            return def;
        }
        if (!(raw instanceof List<?> list)) {
            warnWrongType(path, raw, EXPECTED_LIST, String.join(", ", def));
            return def;
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Boolean || entry instanceof Number || entry instanceof String) {
                values.add(entry.toString());
            } else if (entry != null) {
                warnUnknownEntry(path, entry);
            }
        }
        return values;
    }

    /**
     * Reads a section of names, each with a whole number, such as
     * {@code ender_eye: 1}. The names are left to the caller to check; each
     * number is read like {@link #getInt(String, int, int, int)}, with 1 for
     * one that is no whole number. Anything other than such a section counts
     * as none and is reported.
     *
     * @return the names as they stand in the file and their numbers, in the
     *         order of the file
     */
    public Map<String, Integer> getAmounts(String path, int min, int max) {
        Map<String, Integer> amounts = new LinkedHashMap<>();
        Object raw = config.get(path);
        if (raw == null) {
            return amounts;
        }
        if (!(raw instanceof ConfigurationSection section)) {
            warnWrongType(path, raw, EXPECTED_AMOUNTS, "{}");
            return amounts;
        }
        for (String key : section.getKeys(false)) {
            amounts.put(key, getInt(path + "." + key, 1, min, max));
        }
        return amounts;
    }

    /**
     * Reads a section of entries such as {@code name: number}, the way
     * {@link #getAmounts} does, where a number of at least 0 may also be
     * the word {@code word}: that is read as
     * {@link Double#POSITIVE_INFINITY}. An entry that is neither is reported
     * and left out.
     *
     * @param allowed what the note names as allowed for an entry
     * @param used    what the note names as used in its place
     */
    public Map<String, Double> getCosts(String path, String word, String allowed, String used) {
        Map<String, Double> costs = new LinkedHashMap<>();
        Object raw = config.get(path);
        if (raw == null) {
            return costs;
        }
        if (!(raw instanceof ConfigurationSection section)) {
            warnWrongType(path, raw, EXPECTED_AMOUNTS, "{}");
            return costs;
        }
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof String text && text.trim().equalsIgnoreCase(word)) {
                costs.put(key, Double.POSITIVE_INFINITY);
            } else if (value instanceof Number number && number.doubleValue() >= 0.0
                    && Double.isFinite(number.doubleValue())) {
                costs.put(key, number.doubleValue());
            } else {
                warnUnknownValue(path + "." + key, value, allowed, used);
            }
        }
        return costs;
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

    // -------------------------------------------------------------------- keys

    /**
     * Adds a note for every key in the file that the plugin does not know.
     * Bukkit leaves such a key alone without a word, so a misspelt name, or
     * one indented into the wrong section, would quietly leave its setting at
     * the default - and so would a setting that has been renamed or dropped
     * since the file was written.
     *
     * <p>Known are the keys of the config file built into the jar, which are
     * the defaults of the file, and {@code unlisted}. An unknown section is one
     * note, not one for every line in it.</p>
     *
     * @param unlisted keys the plugin reads although the built-in file leaves
     *                 them out
     */
    public void warnUnknownKeys(Set<String> unlisted) {
        Configuration known = config.getDefaults();
        // Without the built-in file every key would look unknown.
        if (known != null) {
            warnUnknownKeys(config, "", known, unlisted);
        }
    }

    private void warnUnknownKeys(ConfigurationSection section, String prefix,
                                 Configuration known, Set<String> unlisted) {
        for (String key : section.getKeys(false)) {
            String path = prefix + key;
            if (unlisted.contains(path)) {
                continue;
            }
            if (!known.contains(path)) {
                warnings.add(ConfigIssue.of(UNKNOWN_KEY).with("path", path));
            } else if (known.isConfigurationSection(path) && section.isConfigurationSection(key)) {
                warnUnknownKeys(section.getConfigurationSection(key), path + ".", known, unlisted);
            }
        }
    }

    // ------------------------------------------------------------------- notes

    private void warnWrongType(String path, Object raw, String messageKey, String used) {
        warnings.add(ConfigIssue.of(messageKey)
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
        warnings.add(ConfigIssue.of(UNKNOWN_VALUE)
                .with("path", path)
                .with("value", value)
                .with("allowed", allowed)
                .with("used", used));
    }

    /**
     * Adds a note that one entry of a list says nothing the plugin knows, so
     * that a mistyped entry does not simply go unnoticed. Public so that the
     * checks the plugin does on its own read the same as these here.
     */
    public void warnUnknownEntry(String path, Object value) {
        warnings.add(ConfigIssue.of(UNKNOWN_ENTRY)
                .with("path", path)
                .with("value", value));
    }

    /**
     * Adds a note that one entry stands in two lists that rule each other out.
     * Saying two things at once about the same entry is a mistake, not a choice
     * between them, so neither list is followed for it.
     *
     * @param other the other list the entry stands in
     */
    public void warnEntryInBothLists(String path, String other, Object value) {
        warnings.add(ConfigIssue.of(ENTRY_IN_BOTH_LISTS)
                .with("path", path)
                .with("value", value)
                .with("other", other));
    }

    private void warnTooSmall(String path, String value, String min) {
        warnings.add(ConfigIssue.of(TOO_SMALL)
                .with("path", path)
                .with("value", value)
                .with("min", min));
    }

    private void warnTooLarge(String path, String value, String max) {
        warnings.add(ConfigIssue.of(TOO_LARGE)
                .with("path", path)
                .with("value", value)
                .with("max", max));
    }

    /**
     * Adds a note that a number lies beyond anything that can be kept, as
     * opposed to beyond the limit a setting draws for itself.
     *
     * @param used the default, which is used instead
     */
    private void warnOutOfRange(String path, Object value, String used) {
        warnings.add(ConfigIssue.of(OUT_OF_RANGE)
                .with("path", path)
                .with("value", value)
                .with("used", used));
    }
}
