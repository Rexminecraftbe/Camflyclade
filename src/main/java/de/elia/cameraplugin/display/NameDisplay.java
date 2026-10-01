package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.NameStyle;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A name the plugin puts up: a text display of its own instead of the name tag
 * of an entity, looking the way a {@link NameStyle} says.
 *
 * <p>Only such a name can be kept from showing through walls, coloured, scaled
 * and written over several lines. It is also the only name an invisible entity
 * keeps: the client draws no name tag over it.</p>
 */
public final class NameDisplay {

    /**
     * How far over the head the name starts, in blocks. The game hangs the
     * name tag of an entity from half a block over its head; a text display
     * stands on its lowest line instead, so it is set that one line lower to
     * end up in the same place.
     */
    public static final double ABOVE_HEAD = 0.275;

    /**
     * Over how many ticks a name glides after what it stands over to a new
     * spot, instead of jumping there at once. Three is also how many ticks the
     * client takes to move another player or a mob to where the server says
     * it is, so a name that is put back every tick stays on the head.
     */
    public static final int FOLLOW_TICKS = 3;

    /** How far a text display is seen at a view range of 1, in blocks. */
    private static final double DISPLAY_RANGE_BLOCKS = 64.0;

    /** Light level 15 from blocks and sky: the name reads the same in a cave as in the sun. */
    private static final Display.Brightness FULL_BRIGHTNESS = new Display.Brightness(15, 15);

    /** Nothing behind the name, for {@code background: false}. */
    private static final Color NO_BACKGROUND = Color.fromARGB(0);

    /**
     * Colour codes at the very start of a text: the sixteen colours, a hex
     * colour and the reset, which takes the colour away as well.
     */
    private static final Pattern LEADING_COLOR =
            Pattern.compile("^(?:§[0-9a-fA-FrR]|§[xX](?:§[0-9a-fA-F]){6})+");

    private NameDisplay() {
    }

    /**
     * Puts up a name, see {@link #spawn(Location, String, NameStyle, NamespacedKey, Consumer)}.
     */
    public static TextDisplay spawn(Location location, String format, NameStyle style, NamespacedKey mark) {
        return spawn(location, format, style, mark, display -> {
        });
    }

    /**
     * Puts up a name.
     *
     * <p>It has no hitbox, so every click, hit and potion passes through it.
     * It is not saved with the world either: should its chunk be unloaded, it
     * is gone instead of coming back as a name over nothing.</p>
     *
     * @param format the text out of the messages, colour codes already turned
     *               into colours
     * @param mark   the mark the leftovers are found by after a crash
     * @param setup  whatever else the caller needs set before the name is sent
     *               to anybody
     * @return the name, or {@code null} when the text says nothing
     */
    public static TextDisplay spawn(Location location, String format, NameStyle style, NamespacedKey mark,
                                    Consumer<TextDisplay> setup) {
        if (ChatColor.stripColor(format).isBlank()) {
            // An empty box would float there, saying nothing.
            return null;
        }
        // The colour comes from the style. It takes the place of a colour code
        // at the very start of the text, where name-format carried it before
        // the setting existed (&e), so an older config file follows the setting
        // as well; codes further on still colour what comes after them.
        String text = style.color() + LEADING_COLOR.matcher(format).replaceFirst("");
        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.setText(text);
            // Turned towards whoever looks at it, like a name tag, and never
            // broken into lines on its own: only where the text says so.
            display.setBillboard(Display.Billboard.CENTER);
            display.setLineWidth(Integer.MAX_VALUE);
            display.setSeeThrough(style.throughWalls());
            display.setViewRange((float) (style.viewDistance() / DISPLAY_RANGE_BLOCKS));
            display.setDefaultBackground(style.background());
            if (!style.background()) {
                display.setBackgroundColor(NO_BACKGROUND);
            }
            display.setShadowed(style.shadowed());
            float scale = (float) style.scale();
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale), new AxisAngle4f()));
            // Always lit. Left to the light where it stands, the client dims the
            // name in the dark - but only while it is not seen through walls: that
            // kind of text the client always draws at full brightness. The name
            // would be bright or dark depending on through-walls instead of on the
            // light, so it is bright either way.
            display.setBrightness(FULL_BRIGHTNESS);
            display.setTeleportDuration(FOLLOW_TICKS);
            display.setPersistent(false);
            display.getPersistentDataContainer().set(mark, PersistentDataType.INTEGER, 1);
            setup.accept(display);
        });
    }
}
