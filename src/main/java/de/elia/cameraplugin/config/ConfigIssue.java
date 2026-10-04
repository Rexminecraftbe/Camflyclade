package de.elia.cameraplugin.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One value from the config file that did not fit, together with everything
 * needed to describe it.
 *
 * <p>The text itself is not part of this: the reader only says which kind of
 * problem it found and which pieces belong into the sentence, the plugin looks
 * the wording up in {@code messages} of the language file and fills the
 * placeholders. That way the notes can be translated like every other message
 * the plugin sends.</p>
 */
public final class ConfigIssue {

    private final String messageKey;
    private final Map<String, String> placeholders = new LinkedHashMap<>();

    private ConfigIssue(String messageKey) {
        this.messageKey = messageKey;
    }

    /** @param messageKey key below {@code messages} in the language file */
    public static ConfigIssue of(String messageKey) {
        return new ConfigIssue(messageKey);
    }

    /** Adds a placeholder, written as <code>{name}</code> in the message. */
    public ConfigIssue with(String name, Object value) {
        placeholders.put(name, String.valueOf(value));
        return this;
    }

    public String getMessageKey() {
        return messageKey;
    }

    /** Puts the collected values into an already looked up message. */
    public String format(String message) {
        String text = message;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            text = text.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return text;
    }
}
