package de.elia.cameraplugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every text the code asks {@link Messages} for by a fixed key is in the
 * language file. A key that is not there makes no error: the message is
 * simply empty and never shows up, so a typo would go unnoticed.
 */
class MessageKeysTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    /**
     * A call with a key written out: {@code messages.getMessage("key")},
     * {@code messages.sendConfiguredMessage(player, "key")} and the like, the
     * first argument before the key being anything without a quote.
     * {@code sendMessage} only on {@code messages}, as Bukkit has one too.
     */
    private static final Pattern CALL = Pattern.compile(
            "(?:\\b(?:getMessage|configMessage|sendConfiguredMessage|isMessageEnabled|isActionBar|logStartupMessage)"
                    + "|\\bmessages\\s*\\.\\s*(?:sendMessage|deliver)"
                    + "|\\bgetMessages\\(\\)\\s*\\.\\s*(?:sendMessage|deliver))"
                    + "\\(\\s*(?:[^,\"();]*(?:\\([^()\"]*\\)[^,\"();]*)*,\\s*)?\"([a-z0-9][a-z0-9._-]*)\"");

    /** The calls that ask for the switch of a message in {@code message-settings} instead of its text. */
    private static final Pattern SWITCH_CALL = Pattern.compile("\\b(?:isMessageEnabled|isActionBar)\\(");

    @Test
    void everyKeyTheCodeAsksForIsInTheLanguageFile() throws IOException {
        YamlConfiguration language = BuiltIn.yaml(BuiltIn.text(BuiltIn.LANGUAGE));
        ConfigurationSection messages = language.getConfigurationSection("messages");
        ConfigurationSection switches = language.getConfigurationSection("message-settings");
        Set<String> texts = messages.getKeys(true);

        int found = 0;
        Set<String> missing = new TreeSet<>();
        for (Path file : javaFiles()) {
            String code = Files.readString(file, StandardCharsets.UTF_8);
            Matcher call = CALL.matcher(code);
            while (call.find()) {
                found++;
                String key = call.group(1);
                boolean isSwitch = SWITCH_CALL.matcher(call.group()).lookingAt();
                if (!(texts.contains(key) || (isSwitch && switches.contains(key)) || completedLater(key, texts))) {
                    missing.add(key + " (" + SOURCES.relativize(file) + ")");
                }
            }
        }

        // The pattern has to find the calls at all, or the test would pass on nothing.
        assertTrue(found > 300, "only " + found + " calls with a key found");
        assertEquals(Set.of(), missing, "keys the code asks for that lang/en.yml does not have");
    }

    /**
     * A key the code completes while running, such as
     * {@code "menu-time-" + time}: then at least one key has to start so.
     */
    private static boolean completedLater(String key, Set<String> texts) {
        if (!key.endsWith("-") && !key.endsWith(".")) {
            return false;
        }
        return texts.stream().anyMatch(text -> text.startsWith(key));
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return new ArrayList<>(files.filter(f -> f.toString().endsWith(".java")).sorted().toList());
        }
    }
}
