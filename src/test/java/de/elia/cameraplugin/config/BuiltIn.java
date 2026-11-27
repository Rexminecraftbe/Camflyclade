package de.elia.cameraplugin.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The files built into the jar, and YAML read the way Bukkit reads it, for the tests. */
final class BuiltIn {

    static final String CONFIG = "config.yml";
    static final String LANGUAGE = "lang/en.yml";

    private BuiltIn() {
    }

    /**
     * A file of src/main/resources, as the jar has it, with its lines ending in
     * \n: a checkout on Windows gives the files \r\n, and the tests that edit
     * them look for their lines by \n. That the updater copes with \r\n is
     * tested on its own in ConfigUpdaterTest.
     */
    static String text(String path) {
        try (InputStream in = BuiltIn.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IllegalStateException("not in the jar: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A text read as Bukkit reads a config file; a text it cannot read fails the test. */
    static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (InvalidConfigurationException ex) {
            throw new AssertionError("not readable as YAML: " + ex.getMessage(), ex);
        }
        return config;
    }
}
