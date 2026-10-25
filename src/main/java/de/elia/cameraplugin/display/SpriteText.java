package de.elia.cameraplugin.display;

import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.lang.reflect.Method;

/**
 * A text for a text display or the action bar that holds pictures besides
 * letters, such as the hearts of the bar of hearts: {@code {"atlas":
 * "minecraft:gui", "sprite": "minecraft:hud/heart/full"}}. Colour codes
 * cannot write such a picture, it is a component of its own.
 *
 * <p>Only Paper lets a text display or the action bar take components,
 * through Adventure; the Spigot API the plugin is built against offers
 * {@code setText} with colour codes alone. Paper's way is therefore reached
 * by reflection, and where it is missing {@link #available()} says so and the
 * caller stays with letters.</p>
 *
 * <p>The text is put together as JSON: the letters through Adventure's own
 * serializers, the pictures by hand. BungeeCord's chat library, which the
 * Spigot API carries, has components for pictures as well, but the copy of
 * it Paper ships is older and lacks them - the call would fail there.</p>
 */
public final class SpriteText {

    /** The atlas the hearts of the bar come from. */
    public static final String GUI_ATLAS = "minecraft:gui";

    /** {@code TextDisplay#text(Component)}, or {@code null} where the server has none. */
    private static final Method SET_TEXT;
    /** {@code Player#sendActionBar(Component)}, from Adventure's audience. */
    private static final Method SEND_ACTION_BAR;
    /** {@code GsonComponentSerializer.gson()}. */
    private static final Object GSON;
    /** {@code GsonComponentSerializer#deserialize(String)}. */
    private static final Method DESERIALIZE;
    /** {@code GsonComponentSerializer#serialize(Component)}. */
    private static final Method SERIALIZE;
    /** {@code LegacyComponentSerializer.legacySection()}, for letters with colour codes. */
    private static final Object LEGACY;
    /** {@code LegacyComponentSerializer#deserialize(String)}. */
    private static final Method LEGACY_DESERIALIZE;

    static {
        Method setText = null;
        Method sendActionBar = null;
        Object gson = null;
        Method deserialize = null;
        Method serialize = null;
        Object legacy = null;
        Method legacyDeserialize = null;
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Class<?> json = Class.forName("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            Class<?> codes = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            gson = json.getMethod("gson").invoke(null);
            deserialize = json.getMethod("deserialize", Object.class);
            serialize = json.getMethod("serialize", component);
            legacy = codes.getMethod("legacySection").invoke(null);
            legacyDeserialize = codes.getMethod("deserialize", Object.class);
            sendActionBar = Player.class.getMethod("sendActionBar", component);
            // Last: only once everything else is there is the server able to show pictures.
            setText = TextDisplay.class.getMethod("text", component);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            setText = null;
        }
        SET_TEXT = setText;
        SEND_ACTION_BAR = sendActionBar;
        GSON = gson;
        DESERIALIZE = deserialize;
        SERIALIZE = serialize;
        LEGACY = legacy;
        LEGACY_DESERIALIZE = legacyDeserialize;
    }

    private final StringBuilder parts = new StringBuilder();
    /** Whether putting a part together went wrong, so that the text is not to be written. */
    private boolean broken;

    /** Whether this server can show pictures in a text display and the action bar at all. */
    public static boolean available() {
        return SET_TEXT != null;
    }

    /** Adds letters, colour codes already turned into colours. */
    public SpriteText text(String legacy) {
        if (legacy.isEmpty() || !available()) {
            return this;
        }
        try {
            add((String) SERIALIZE.invoke(GSON, LEGACY_DESERIALIZE.invoke(LEGACY, legacy)));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
        }
        return this;
    }

    /** Adds a picture out of an atlas of the game, e.g. {@code minecraft:hud/heart/full} out of the gui. */
    public SpriteText sprite(String atlas, String sprite) {
        add("{\"atlas\":\"" + atlas + "\",\"sprite\":\"" + sprite + "\"}");
        return this;
    }

    /** Starts a new line. */
    public SpriteText line() {
        add("\"\\n\"");
        return this;
    }

    private void add(String json) {
        if (!parts.isEmpty()) {
            parts.append(',');
        }
        parts.append(json);
    }

    /**
     * Writes the text into a text display. Every part sits under an empty
     * parent, so none of them passes its colour on to the next.
     *
     * @return whether it worked; if not, the display keeps the text it had
     */
    public boolean writeTo(TextDisplay display) {
        return deliver(SET_TEXT, display);
    }

    /**
     * Sends the text as the line above the hotbar of the player.
     *
     * @return whether it worked; if not, nothing was sent
     */
    public boolean sendActionBar(Player player) {
        return deliver(SEND_ACTION_BAR, player);
    }

    /** Hands the text as one component to this method of the target. */
    private boolean deliver(Method method, Object target) {
        if (!available() || broken) {
            return false;
        }
        try {
            method.invoke(target, DESERIALIZE.invoke(GSON, "{\"text\":\"\",\"extra\":[" + parts + "]}"));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
