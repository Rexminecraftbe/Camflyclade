package de.elia.cameraplugin.locator;

import org.bukkit.ChatColor;

import java.util.UUID;

/**
 * The colour a point on the locator bar is drawn in, worked out the way the
 * game does it: a colour of its own, set with {@code /waypoint modify}, or
 * the colour of the team of whoever it stands for, or else one the client
 * makes up from his id.
 *
 * <p>The body has an id of its own, and every start a new one. Left to the
 * client, its point would come up in a different colour each time, not in
 * the one the player has on the bar. So the body is given his colour
 * outright, and it has to be the very one the client would draw for him.</p>
 */
final class WaypointColors {

    /**
     * How bright the client draws the colour it makes up from an id, as
     * {@code LocatorBar} passes it to {@code ARGB.setBrightness}.
     */
    private static final float ID_BRIGHTNESS = 0.9F;

    /**
     * The point of a player in a black team: the game draws it dark grey
     * instead, black would vanish against the bar.
     */
    private static final int BLACK_TEAM = 0x303030;

    private WaypointColors() {
    }

    /**
     * The colour the client gives the point of a player who has neither a
     * colour of his own nor a team with one: the hash of his id with the
     * brightness set to {@link #ID_BRIGHTNESS}, the way {@code LocatorBar}
     * and {@code ARGB.setBrightness} work it out.
     *
     * @return the colour as {@code 0xRRGGBB}
     */
    static int ofId(UUID id) {
        return withBrightness(id.hashCode() & 0xFFFFFF, ID_BRIGHTNESS);
    }

    /**
     * The colour of a team on the bar: its colour of chat, black excepted,
     * see {@link #BLACK_TEAM}.
     *
     * @return the colour as {@code 0xRRGGBB}, or {@code null} when the team
     *         has none - formats like bold and {@code RESET} are none either
     */
    static Integer ofTeam(ChatColor color) {
        if (color == null) {
            return null;
        }
        return switch (color) {
            case BLACK -> BLACK_TEAM;
            case DARK_BLUE -> 0x0000AA;
            case DARK_GREEN -> 0x00AA00;
            case DARK_AQUA -> 0x00AAAA;
            case DARK_RED -> 0xAA0000;
            case DARK_PURPLE -> 0xAA00AA;
            case GOLD -> 0xFFAA00;
            case GRAY -> 0xAAAAAA;
            case DARK_GRAY -> 0x555555;
            case BLUE -> 0x5555FF;
            case GREEN -> 0x55FF55;
            case AQUA -> 0x55FFFF;
            case RED -> 0xFF5555;
            case LIGHT_PURPLE -> 0xFF55FF;
            case YELLOW -> 0xFFFF55;
            case WHITE -> 0xFFFFFF;
            default -> null;
        };
    }

    /**
     * A colour with its brightness, the value of HSV, set anew: step for step
     * as the client's {@code ARGB.setBrightness}, in {@code float} like there,
     * so that every id comes out the same to the last bit.
     *
     * @param rgb the colour as {@code 0xRRGGBB}
     * @return the colour as {@code 0xRRGGBB}
     */
    static int withBrightness(int rgb, float brightness) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        int max = Math.max(Math.max(red, green), blue);
        int min = Math.min(Math.min(red, green), blue);
        float range = max - min;
        float saturation = max != 0 ? range / max : 0.0F;
        if (saturation == 0.0F) {
            int grey = Math.round(brightness * 255.0F);
            return grey << 16 | grey << 8 | grey;
        }
        float redShare = (max - red) / range;
        float greenShare = (max - green) / range;
        float blueShare = (max - blue) / range;
        float hue;
        if (red == max) {
            hue = blueShare - greenShare;
        } else if (green == max) {
            hue = 2.0F + redShare - blueShare;
        } else {
            hue = 4.0F + greenShare - redShare;
        }
        hue /= 6.0F;
        if (hue < 0.0F) {
            hue += 1.0F;
        }
        float sector = (hue - (float) Math.floor(hue)) * 6.0F;
        float within = sector - (float) Math.floor(sector);
        float lowest = brightness * (1.0F - saturation);
        float falling = brightness * (1.0F - saturation * within);
        float rising = brightness * (1.0F - saturation * (1.0F - within));
        switch ((int) sector) {
            case 0 -> {
                red = channel(brightness);
                green = channel(rising);
                blue = channel(lowest);
            }
            case 1 -> {
                red = channel(falling);
                green = channel(brightness);
                blue = channel(lowest);
            }
            case 2 -> {
                red = channel(lowest);
                green = channel(brightness);
                blue = channel(rising);
            }
            case 3 -> {
                red = channel(lowest);
                green = channel(falling);
                blue = channel(brightness);
            }
            case 4 -> {
                red = channel(rising);
                green = channel(lowest);
                blue = channel(brightness);
            }
            case 5 -> {
                red = channel(brightness);
                green = channel(lowest);
                blue = channel(falling);
            }
            default -> {
                // Never reached: the sector lies between 0 and 6. The client
                // keeps the colour as it was then, and so does this.
            }
        }
        return red << 16 | green << 8 | blue;
    }

    private static int channel(float value) {
        return Math.round(value * 255.0F);
    }
}
