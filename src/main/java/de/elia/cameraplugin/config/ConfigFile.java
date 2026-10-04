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
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * The config file and the language file it names: reading them, and saying
 * what is wrong with them.
 */
public final class ConfigFile {

    /** The config file, in the data folder of the plugin and in the jar. */
    private static final String CONFIG_FILE = "config.yml";

    /** The folder of the language files, in the data folder of the plugin and in the jar. */
    static final String LANGUAGE_FOLDER = "lang";

    /**
     * The language built into the jar, the only one that comes with the
     * plugin - every other one is a copy of its file that somebody translated.
     */
    static final String DEFAULT_LANGUAGE = "en";

    /** The ending of a language file, after the name of its language. */
    private static final String LANGUAGE_FILE_ENDING = ".yml";

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
     * What the parser says about a spot in the file, and the message that says
     * what it means. Matched by a piece of the sentence, because the exact
     * wording differs between versions of the parser. These are the mistakes
     * that really happen while editing the file by hand; anything else keeps
     * the parser's own words.
     */
    private static final String[][] CONFIG_PROBLEMS = {
            {"could not find expected ':'", "config-syntax-missing-colon"},
            {"mapping values are not allowed", "config-syntax-extra-colon"},
            {"cannot start any token", "config-syntax-tab"},
            {"expected <block end>", "config-syntax-indentation", "second"},
            {"found unexpected end of stream", "config-syntax-missing-quote"},
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

    /** Where the file of a language stands, below the data folder and in the jar. */
    static String languagePath(String language) {
        return LANGUAGE_FOLDER + "/" + language + LANGUAGE_FILE_ENDING;
    }

    /**
     * Writes the English language file into the data folder unless it is there
     * already, the way {@link JavaPlugin#saveDefaultConfig()} does it for the
     * config file.
     */
    public void saveDefaultLanguage() {
        String path = languagePath(DEFAULT_LANGUAGE);
        if (!new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
        }
    }

    /** The config file as it was last read, read now if it never was. */
    public FileConfiguration getConfig() {
        if (config == null) {
            readConfigInto(null);
        }
        return config;
    }

    /**
     * Reads the config file and the language file it names, and puts both in
     * place.
     *
     * <p>A file that cannot be read leaves the settings exactly as they are - a
     * forgotten dash must not put the whole server back to the default values.
     * Only while the plugin is starting is there nothing to keep, and the
     * values built into the jar have to carry it; that is what the two
     * different messages say.</p>
     *
     * <p>The two files count as one here: a mistake in either leaves both as
     * they are, just as a mistake among the texts did while they still stood
     * in the config file.</p>
     *
     * @param initiator the player who asked for the reload, told about a broken
     *                  file as well, or {@code null} for the console alone
     * @return whether both files could be read
     */
    public boolean readConfigInto(CommandSender initiator) {
        YamlConfiguration loaded = new YamlConfiguration();
        String broken = CONFIG_FILE;
        Exception problem = load(loaded, new File(plugin.getDataFolder(), CONFIG_FILE));
        YamlConfiguration language = null;
        if (problem == null) {
            String languagePath = languagePath(languageOf(loaded));
            File languageFile = new File(plugin.getDataFolder(), languagePath);
            // Without a file the built-in English one is used, see Messages#use.
            if (languageFile.isFile()) {
                language = new YamlConfiguration();
                broken = languagePath;
                problem = load(language, languageFile);
            }
        }
        if (problem != null && config != null) {
            reportBrokenConfig("config-broken", broken, problem, initiator);
            return false;
        }
        InputStream defaults = plugin.getResource(CONFIG_FILE);
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        config = loaded;
        if (problem != null) {
            reportBrokenConfig("config-broken-startup", broken, problem, initiator);
            return false;
        }
        messages.use(language);
        return true;
    }

    /**
     * Reads one file into the configuration given. A file that is not there is
     * no mistake, it simply says nothing.
     *
     * @return why the file could not be read, or {@code null} when it could
     */
    private static Exception load(YamlConfiguration into, File file) {
        if (!file.exists()) {
            return null;
        }
        try {
            into.load(file);
            return null;
        } catch (IOException | InvalidConfigurationException ex) {
            return ex;
        }
    }

    /**
     * The language a config file asks for under {@code language}, spelt the
     * way its file is, or the built-in one when there is no file for it.
     * Capitals do not count, as with every other value - unless two files
     * differ in nothing else, then the one written exactly so wins. Read
     * without a word about a language nobody has a file for:
     * {@link #checkLanguage} says that, along with every other note.
     */
    private String languageOf(FileConfiguration loaded) {
        Object wanted = loaded.get("language");
        if (wanted == null) {
            return DEFAULT_LANGUAGE;
        }
        List<String> languages = availableLanguages();
        if (languages.contains(wanted.toString())) {
            return wanted.toString();
        }
        for (String language : languages) {
            if (language.equalsIgnoreCase(wanted.toString())) {
                return language;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * Notes a language in {@code language} that there is no file for. The
     * texts come from the English file then, see {@link #readConfigInto}.
     */
    public void checkLanguage(ConfigReader config) {
        config.getChoice("language", DEFAULT_LANGUAGE, availableLanguages().toArray(new String[0]));
    }

    /**
     * Every language there is a file for in the folder {@code lang}, and the
     * built-in one, which needs none. In alphabetical order, for the note that
     * lists them.
     */
    private List<String> availableLanguages() {
        Set<String> languages = new TreeSet<>();
        languages.add(DEFAULT_LANGUAGE);
        File[] files = new File(plugin.getDataFolder(), LANGUAGE_FOLDER).listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.endsWith(LANGUAGE_FILE_ENDING)
                        && name.length() > LANGUAGE_FILE_ENDING.length()) {
                    languages.add(name.substring(0, name.length() - LANGUAGE_FILE_ENDING.length()));
                }
            }
        }
        return new ArrayList<>(languages);
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
            texts.add(warning.format(messages.configMessage(warning.getMessageKey())));
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
                ? messages.configMessage("config-error-header-single")
                : messages.configMessage("config-error-header");
        initiator.sendMessage(header.replace("{count}", String.valueOf(texts.size())));
        int shown = Math.min(texts.size(), MAX_CHAT_WARNINGS);
        for (int i = 0; i < shown; i++) {
            initiator.sendMessage(texts.get(i));
        }
        if (texts.size() > shown) {
            initiator.sendMessage(messages.configMessage("config-error-more")
                    .replace("{count}", String.valueOf(texts.size() - shown)));
        }
    }

    /**
     * Says in one line why a file could not be read.
     *
     * @param file the file, the way it stands below the data folder
     */
    private void reportBrokenConfig(String key, String file, Exception problem, CommandSender initiator) {
        String text = messages.configMessage(key)
                .replace("{file}", file)
                .replace("{error}", describeProblem(problem.getMessage()));
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
    private String describeProblem(String message) {
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
                reason = messages.configMessage(known[1]);
                secondSpot = known.length > 2;
                break;
            }
        }
        List<String> spots = new ArrayList<>();
        java.util.regex.Matcher marks = java.util.regex.Pattern
                .compile("line (\\d+), column (\\d+)").matcher(message);
        while (marks.find()) {
            spots.add(messages.configMessage("config-syntax-spot")
                    .replace("{line}", marks.group(1))
                    .replace("{column}", marks.group(2)));
        }
        if (spots.isEmpty()) {
            return shorten(message.replaceAll("\\s+", " ").trim());
        }
        String spot = secondSpot && spots.size() > 1 ? spots.get(1) : spots.get(0);
        return shorten(spot + ": " + (reason.isEmpty() ? messages.configMessage("config-syntax-unreadable") : reason));
    }

    /** Cuts a reason that is longer than a line of chat. */
    private static String shorten(String text) {
        return text.length() <= MAX_CONFIG_ERROR_LENGTH
                ? text
                : text.substring(0, MAX_CONFIG_ERROR_LENGTH) + "...";
    }
}
