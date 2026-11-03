package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.nightvision.NightVisionMode;
import org.bukkit.WeatherType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

/**
 * How a spot shows its build, besides where it is and which way it looks:
 * the time of day, the weather and the night vision its owner had picked for
 * himself in camera mode when he saved it - with {@code /cam time},
 * {@code /cam weather} and {@code /cam nightvision} -, so that whoever jumps
 * there sees the build at sunset in the rain, the way it was
 * meant to be seen.
 *
 * <p>Only what he picked himself goes into it, never the time and weather of
 * the world: a spot saved at noon on the server is no noon spot, it shows
 * whatever time it is when somebody comes. Each part is {@code null} when he
 * had picked none.</p>
 *
 * <p>Whether a part holds for whoever comes is decided only then, by the
 * config file as it is at that moment: with {@code cam-time},
 * {@code cam-weather} or {@code night-vision.player-choice} switched off,
 * that part is left out - a spot cannot hand out what the server no longer
 * lets anybody pick. It stays in the spot all the same and holds again once
 * the switch is back on.</p>
 *
 * @param time        a time of day in ticks, 0 to 23999, standing still
 * @param weather     clear or rain
 * @param nightVision when the camera sees in the dark
 */
public record SpotLook(Long time, WeatherType weather, NightVisionMode nightVision) {

    /** A spot that shows nothing of its own: the time and weather of the world, the visitor's own night vision. */
    public static final SpotLook NONE = new SpotLook(null, null, null);

    /** The length of a day in ticks: a time of a spot lies below it. */
    private static final long DAY = 24000L;

    public SpotLook {
        if (time != null && (time < 0L || time >= DAY)) {
            time = null;
        }
    }

    /** Whether it shows nothing of its own. */
    public boolean isEmpty() {
        return time == null && weather == null && nightVision == null;
    }

    /**
     * Reads the look written under a spot in a file of the plugin; a part
     * that is missing, or says something that makes no sense, is left out.
     */
    public static SpotLook read(ConfigurationSection spot) {
        if (spot == null) {
            return NONE;
        }
        Long time = spot.isInt("time") || spot.isLong("time") ? spot.getLong("time") : null;
        SpotLook look = new SpotLook(time, readWeather(spot.getString("weather")),
                readNightVision(spot.getString("night-vision")));
        return look.isEmpty() ? NONE : look;
    }

    /** Writes the look under a spot, {@code path} being the spot; parts it does not have are left out. */
    public void write(YamlConfiguration file, String path) {
        if (time != null) {
            file.set(path + ".time", time);
        }
        if (weather != null) {
            file.set(path + ".weather", weatherWord(weather));
        }
        if (nightVision != null) {
            file.set(path + ".night-vision", nightVisionWord(nightVision));
        }
    }

    /** What a file says about the weather: clear or rain, {@code null} for anything else. */
    public static WeatherType readWeather(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "clear" -> WeatherType.CLEAR;
            case "rain" -> WeatherType.DOWNFALL;
            default -> null;
        };
    }

    /** How a file and {@code /cam weather} name a weather. */
    public static String weatherWord(WeatherType weather) {
        return weather == WeatherType.CLEAR ? "clear" : "rain";
    }

    /** What a file says about the night vision: on, off or dark, {@code null} for anything else. */
    public static NightVisionMode readNightVision(String word) {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "on" -> NightVisionMode.ALWAYS;
            case "off" -> NightVisionMode.OFF;
            case "dark" -> NightVisionMode.DARK;
            default -> null;
        };
    }

    /** How a file and {@code /cam nightvision} name a mode. */
    public static String nightVisionWord(NightVisionMode mode) {
        return switch (mode) {
            case ALWAYS -> "on";
            case OFF -> "off";
            case DARK -> "dark";
        };
    }
}
