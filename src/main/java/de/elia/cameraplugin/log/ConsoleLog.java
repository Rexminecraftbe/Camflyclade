package de.elia.cameraplugin.log;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.logging.Level;

/**
 * Writes the plugin's lines into the server console, in a form that console
 * can actually print.
 */
public final class ConsoleLog {

    /** What the server console writes with, see {@link #consoleCharset()}. */
    private static final Charset CONSOLE_CHARSET = consoleCharset();

    private final JavaPlugin plugin;

    public ConsoleLog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Puts one line into the server console, written so that console can
     * actually print it, see {@link #forConsole(String)}.
     */
    public void log(Level level, String text) {
        plugin.getLogger().log(level, forConsole(text));
    }

    /**
     * The charset the server console writes its lines with, which decides
     * whether an umlaut survives the way out.
     */
    private static Charset consoleCharset() {
        // Deliberately not System.out: the server puts a stream of its own in
        // its place early on, and that one answers with the charset of the log
        // framework rather than the one the line is written out with. These two
        // properties carry the charset of the console itself.
        for (String property : new String[] {"stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(property);
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // A name this Java does not know - the next one counts.
            }
        }
        // Nothing to go by: rewrite the umlauts rather than risk question marks.
        return StandardCharsets.US_ASCII;
    }

    /**
     * The same sentence, written so that the server console can print it.
     *
     * <p>A console that cannot write umlauts turns every one of them into a
     * question mark - "f?r" instead of "für" in a German language file. Which
     * characters it can write is decided by how the server was started and not
     * by this plugin, so the sentence is rewritten only when it really would
     * not survive: ä becomes ae, ß becomes ss, and whatever is left over loses
     * its accent. A console that can write them gets the sentence untouched,
     * and the chat keeps the umlauts either way.</p>
     */
    private static String forConsole(String text) {
        if (CONSOLE_CHARSET.newEncoder().canEncode(text)) {
            return text;
        }
        String plain = text
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        if (CONSOLE_CHARSET.newEncoder().canEncode(plain)) {
            return plain;
        }
        // Everything else loses its marks: é becomes e, a character without
        // a counterpart stays and turns into a question mark as before.
        return Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
