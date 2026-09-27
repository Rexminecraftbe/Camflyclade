package de.elia.cameraplugin.config;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * The config file itself: reading it, and saying what is wrong with it.
 */
public final class ConfigFile {

    /**
     * How much of the reason a broken config file gives is passed on. The rest
     * is cut off: a YAML error carries the offending line and a caret under it
     * and would otherwise fill the chat.
     */
    private static final int MAX_CONFIG_ERROR_LENGTH = 200;

    /**
     * How many config notes are sent into the chat of the player who reloaded.
     * The rest is only in the console, so that a thoroughly broken file does not
     * bury the chat.
     */
    private static final int MAX_CHAT_WARNINGS = 8;

    /**
     * What the parser says about a spot in the file, and what it means in
     * German. Matched by a piece of the sentence, because the exact wording
     * differs between versions of the parser. These are the mistakes that
     * really happen while editing the file by hand; anything else keeps the
     * parser's own words.
     */
    private static final String[][] CONFIG_PROBLEMS = {
            {"could not find expected ':'",
                    "Hier fehlt ein \"-\" am Zeilenanfang oder ein \":\" hinter dem Namen"},
            {"mapping values are not allowed",
                    "Hier steht ein \":\" zu viel, oder der Wert gehört in Anführungszeichen"},
            {"cannot start any token",
                    "Hier steht ein Tabulator; eingerückt wird nur mit Leerzeichen"},
            {"expected <block end>",
                    "Hier stimmt die Einrückung nicht mit den Zeilen darüber überein", "zweite"},
            {"found unexpected end of stream",
                    "Hier fehlt das schließende Anführungszeichen"},
    };

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final Messages messages;

    /** The config file as it was last read, see {@link #readConfigInto}. */
    private FileConfiguration config;

    public ConfigFile(JavaPlugin plugin, ConsoleLog log, Messages messages) {
        this.plugin = plugin;
        this.log = log;
        this.messages = messages;
    }

    /** The config file as it was last read, read now if it never was. */
    public FileConfiguration getConfig() {
        if (config == null) {
            readConfigInto(null);
        }
        return config;
    }

    /**
     * Reads the config file and puts it in place.
     *
     * <p>A file that cannot be read leaves the settings exactly as they are - a
     * forgotten dash must not put the whole server back to the default values.
     * Only while the plugin is starting is there nothing to keep, and the
     * values built into the jar have to carry it; that is what the two
     * different messages say.</p>
     *
     * @param initiator the player who asked for the reload, told about a broken
     *                  file as well, or {@code null} for the console alone
     * @return whether the file could be read
     */
    public boolean readConfigInto(CommandSender initiator) {
        YamlConfiguration loaded = new YamlConfiguration();
        File file = new File(plugin.getDataFolder(), "config.yml");
        Exception problem = null;
        if (file.exists()) {
            try {
                loaded.load(file);
            } catch (IOException | InvalidConfigurationException ex) {
                problem = ex;
            }
        }
        // Reported only once something is in place, because the report looks
        // its own wording up in the config file.
        if (problem != null && config != null) {
            reportBrokenConfig("config-broken", problem, initiator);
            return false;
        }
        InputStream defaults = plugin.getResource("config.yml");
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        config = loaded;
        if (problem != null) {
            reportBrokenConfig("config-broken-startup", problem, initiator);
            return false;
        }
        return true;
    }

    /**
     * Writes the notes collected while the values were read into the console
     * and, after a reload, into the chat of the player who started it. Without
     * the second part a broken config file stays invisible in game: the reload
     * reports success while the server quietly runs on default values.
     */
    public void reportConfigWarnings(List<ConfigIssue> warnings, Player initiator) {
        List<String> texts = new ArrayList<>(warnings.size());
        for (ConfigIssue warning : warnings) {
            texts.add(warning.format(messages.configMessage(warning.getMessageKey(), warning.getFallback())));
        }
        for (String text : texts) {
            // The console has no use for colour codes, only for the sentence.
            log.log(Level.WARNING, ChatColor.stripColor(text));
        }
        if (initiator == null || texts.isEmpty() || !messages.isMessageEnabled("config-errors")) {
            return;
        }
        // The command reports success right after this, so the block needs a
        // headline of its own to not be mistaken for a clean reload.
        String header = texts.size() == 1
                ? messages.configMessage("config-error-header-single", "&cDie Konfiguration hat eine ungültige Stelle:")
                : messages.configMessage("config-error-header", "&cDie Konfiguration hat {count} ungültige Stellen:");
        initiator.sendMessage(header.replace("{count}", String.valueOf(texts.size())));
        int shown = Math.min(texts.size(), MAX_CHAT_WARNINGS);
        for (int i = 0; i < shown; i++) {
            initiator.sendMessage(texts.get(i));
        }
        if (texts.size() > shown) {
            initiator.sendMessage(messages.configMessage("config-error-more",
                    "&c... und {count} weitere. Alle stehen in der Server-Konsole.")
                    .replace("{count}", String.valueOf(texts.size() - shown)));
        }
    }

    /** Says in one line why the config file could not be read. */
    private void reportBrokenConfig(String key, Exception problem, CommandSender initiator) {
        String fallback = "config-broken".equals(key)
                ? "&cDie Konfiguration hat einen Fehler und wurde nicht übernommen,"
                        + " es gelten weiter die bisherigen Einstellungen: {error}"
                : "&cDie Konfiguration hat einen Fehler: {error}";
        String text = messages.configMessage(key, fallback).replace("{error}", describeProblem(problem.getMessage()));
        log.log(Level.SEVERE, ChatColor.stripColor(text));
        if (initiator != null && messages.isMessageEnabled("config-errors")) {
            initiator.sendMessage(text);
        }
    }

    /**
     * Turns what the parser reports into one short sentence: where it is, and
     * what is missing there.
     *
     * <p>The parser hands out several lines for one mistake - its own wording,
     * the offending line of the file, a caret underneath, and the spot where it
     * finally gave up. Only two of those are worth anything: the spot to
     * repair and the reason. Its own sentences are the ones that start at the
     * very left, the last of them names the problem; the spots are indented.
     * </p>
     *
     * <p>Read out of the text and not out of the parser's own error class,
     * which is none of the server API and need not be the same everywhere. A
     * text this cannot make sense of is passed on as it is.</p>
     */
    private static String describeProblem(String message) {
        if (message == null) {
            return "";
        }
        String reason = "";
        for (String line : message.split("\\R")) {
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                reason = line.trim();
            }
        }
        // Which of the two spots to name: usually the first, where the mistake
        // begins. Only a broken indentation is the other way round - there the
        // first spot is the block that was still fine.
        boolean secondSpot = false;
        for (String[] known : CONFIG_PROBLEMS) {
            if (message.contains(known[0])) {
                reason = known[1];
                secondSpot = known.length > 2;
                break;
            }
        }
        List<String> spots = new ArrayList<>();
        java.util.regex.Matcher marks = java.util.regex.Pattern
                .compile("line (\\d+), column (\\d+)").matcher(message);
        while (marks.find()) {
            spots.add("Zeile " + marks.group(1) + ", Spalte " + marks.group(2));
        }
        if (spots.isEmpty()) {
            return shorten(message.replaceAll("\\s+", " ").trim());
        }
        String spot = secondSpot && spots.size() > 1 ? spots.get(1) : spots.get(0);
        return shorten(spot + ": " + (reason.isEmpty() ? "unlesbar" : reason));
    }

    /** Cuts a reason that is longer than a line of chat. */
    private static String shorten(String text) {
        return text.length() <= MAX_CONFIG_ERROR_LENGTH
                ? text
                : text.substring(0, MAX_CONFIG_ERROR_LENGTH) + "...";
    }
}
