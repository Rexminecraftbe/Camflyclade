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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
     * What is put behind the name of a file to keep it the way it was before
     * {@link ConfigUpdater} completed it. Not {@code .yml}: a copy of a
     * language file would otherwise count as a language of its own.
     */
    private static final String BACKUP_ENDING = ".bak";

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
        String languagePath = null;
        if (problem == null) {
            languagePath = languagePath(languageOf(loaded));
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
        if (problem == null) {
            loaded = complete(CONFIG_FILE, CONFIG_FILE, loaded, ConfigMigrations.CONFIG,
                    CamSettings.UNLISTED_KEYS, initiator);
            // Only the English file, which the plugin writes whole: another
            // language may well be a file with a few texts on purpose, the rest
            // coming from the English one - filled up, it would be that no more.
            if (language != null && languagePath.equals(languagePath(DEFAULT_LANGUAGE))) {
                language = complete(languagePath, languagePath, language, ConfigMigrations.LANGUAGE, Set.of(),
                        initiator);
            }
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
     * Writes into a file what the same file in the jar has and it lacks, see
     * {@link ConfigUpdater}, and says so. The file as it was stays next to it,
     * with {@link #BACKUP_ENDING} behind its name.
     *
     * <p>Run each time the files are read, at the start and with
     * {@code /cam reload}: a new version of the plugin brings its new settings
     * into the file at the first start, and a key that was deleted by mistake
     * comes back with the next reload.</p>
     *
     * @param path       the file, the way it stands below the data folder
     * @param builtIn    the file in the jar to complete it from
     * @param read       the file as it was just read
     * @param migrations changes for files of older versions, see {@link ConfigMigrations}
     * @param ownKeys    sections whose keys the file chooses itself
     * @return the file as it is now: the one read, or the completed one
     */
    private YamlConfiguration complete(String path, String builtIn, YamlConfiguration read,
                                       List<ConfigUpdater.Migration> migrations, Set<String> ownKeys,
                                       CommandSender initiator) {
        Path file = plugin.getDataFolder().toPath().resolve(path);
        String text;
        String model;
        try (InputStream stream = plugin.getResource(builtIn)) {
            if (stream == null || !Files.isRegularFile(file)) {
                return read;
            }
            model = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = Files.readAllBytes(file);
            try {
                // Strictly: Bukkit reads a file in another encoding all the
                // same, with a question mark for each umlaut it cannot make
                // sense of. Written back, those would be lost for good.
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ex) {
                ConfigUpdater.Result lacking = ConfigUpdater.update(new String(bytes, StandardCharsets.UTF_8), model,
                        ownKeys, migrations);
                if (lacking.changed() || lacking.failure() != null) {
                    reportUpdate("config-update-failed", path, initiator, "{error}",
                            messages.configMessage("config-update-encoding"));
                }
                return read;
            }
        } catch (IOException ex) {
            return read;
        }
        ConfigUpdater.Result result = ConfigUpdater.update(text, model, ownKeys, migrations);
        if (result.failure() != null) {
            String reason = result.failure().equals(ConfigUpdater.Result.LAYOUT)
                    ? messages.configMessage("config-update-layout")
                    : messages.configMessage("config-update-check").replace("{path}", result.failure());
            reportUpdate("config-update-failed", path, initiator, "{error}", reason);
            return read;
        }
        if (!result.changed()) {
            return read;
        }
        YamlConfiguration updated = new YamlConfiguration();
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_ENDING);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            updated.loadFromString(result.text());
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(temp, result.text(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | InvalidConfigurationException ex) {
            reportUpdate("config-update-failed", path, initiator, "{error}",
                    messages.configMessage("config-update-write").replace("{error}", String.valueOf(ex.getMessage())));
            return read;
        }
        String backupName = path + BACKUP_ENDING;
        for (Map.Entry<String, String> rename : result.renamed().entrySet()) {
            reportUpdate("config-renamed", path, initiator, "{old}", rename.getKey(), "{new}", rename.getValue(),
                    "{backup}", backupName);
        }
        Collection<String> removed = result.removed();
        if (removed.size() == 1) {
            reportUpdate("config-removed-single", path, initiator, "{keys}", removed.iterator().next(),
                    "{backup}", backupName);
        } else if (!removed.isEmpty()) {
            reportUpdate("config-removed", path, initiator, "{count}", String.valueOf(removed.size()),
                    "{keys}", String.join(", ", removed), "{backup}", backupName);
        }
        for (Map.Entry<String, List<String>> value : result.values().entrySet()) {
            reportUpdate("config-value-renamed", path, initiator, "{path}", value.getKey(),
                    "{old}", value.getValue().get(0), "{new}", value.getValue().get(1), "{backup}", backupName);
        }
        Collection<String> added = result.added();
        if (added.size() == 1) {
            reportUpdate("config-updated-single", path, initiator, "{keys}", added.iterator().next(),
                    "{backup}", backupName);
        } else if (!added.isEmpty()) {
            reportUpdate("config-updated", path, initiator, "{count}", String.valueOf(added.size()),
                    "{keys}", String.join(", ", added), "{backup}", backupName);
        }
        return updated;
    }

    /**
     * Says what {@link #complete} did: in the console, and after a reload in
     * the chat of who started it, unless the switch {@code config-updated}
     * is off.
     *
     * @param fills placeholder and value, one pair after the other; {@code {file}} is filled already
     */
    private void reportUpdate(String key, String file, CommandSender initiator, String... fills) {
        String text = messages.configMessage(key).replace("{file}", file);
        for (int i = 0; i + 1 < fills.length; i += 2) {
            text = text.replace(fills[i], fills[i + 1]);
        }
        boolean failed = key.equals("config-update-failed");
        log.log(failed ? Level.WARNING : Level.INFO, ChatColor.stripColor(text));
        if (initiator instanceof Player && messages.isMessageEnabled("config-updated")) {
            initiator.sendMessage(text);
        }
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
