package de.elia.cameraplugin.sky;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A time of day and a weather of his own for the camera player,
 * {@code /cam time} and {@code /cam weather}: his own build at sunset, or in
 * the sun while it rains on the server.
 *
 * <p>Only his client is told about it, nobody else sees anything of it, and
 * the world goes on with its own time and weather - it changes what he sees,
 * not what happens. That is also why it is for camera mode alone: out there
 * on his feet, a night of his own would be a way to look through the dark of
 * the server.</p>
 *
 * <p>None of it is written anywhere. It ends with camera mode, and he sees
 * what he saw before it again: the time and weather of the world, or one
 * another plugin gave him.</p>
 */
public final class CamSky {

    /** The times {@code /cam time} knows by name, in ticks of the day, as {@code /time set} has most of them. */
    private static final Map<String, Long> TIMES = new LinkedHashMap<>();

    static {
        TIMES.put("day", 1000L);
        TIMES.put("noon", 6000L);
        TIMES.put("sunset", 12000L);
        TIMES.put("night", 13000L);
        TIMES.put("midnight", 18000L);
        TIMES.put("sunrise", 23000L);
    }

    /** The length of a day in ticks: a time of {@code /cam time} lies below it. */
    private static final long DAY = 24000L;

    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlugin plugin;
    /** What each camera player saw before his first {@code /cam time} or {@code /cam weather}. */
    private final Map<UUID, Before> before = new HashMap<>();

    /**
     * What a player saw before camera mode gave him a time or a weather of
     * his own, to be given back at its end. Each part only once it is
     * changed: until then it is none of this class's business.
     */
    private static final class Before {
        boolean time;
        long timeOffset;
        boolean timeRelative;
        boolean weather;
        /** The weather another plugin gave him, or {@code null} for the one of the world. */
        WeatherType weatherType;
    }

    public CamSky(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /** Whether {@code /cam time} does anything for this player right now. */
    public boolean mayUseTime(Player player) {
        return settings.isCamTimeEnabled() && plugin.isInCameraMode(player);
    }

    /** Whether {@code /cam weather} does anything for this player right now. */
    public boolean mayUseWeather(Player player) {
        return settings.isCamWeatherEnabled() && plugin.isInCameraMode(player);
    }

    /** The words that may follow {@code /cam time}, for the tab completion. */
    public static String[] timeWords() {
        String[] words = TIMES.keySet().toArray(new String[TIMES.size() + 1]);
        words[TIMES.size()] = "reset";
        return words;
    }

    /** The words that may follow {@code /cam weather}, for the tab completion. */
    public static String[] weatherWords() {
        return new String[]{"clear", "rain", "reset"};
    }

    /**
     * {@code /cam time}: a time by name or in ticks, 0 to 23999, which stands
     * still - the sun does not move on while he takes his picture -, or
     * {@code reset} for the time of the world again.
     */
    public void timeCommand(Player player, String[] args) {
        if (!settings.isCamTimeEnabled()) {
            messages.sendConfiguredMessage(player, "time-off");
            return;
        }
        if (!plugin.isInCameraMode(player)) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(player, "time-usage");
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        if (word.equals("reset")) {
            resetTime(player);
            messages.sendConfiguredMessage(player, "time-reset");
            return;
        }
        Long ticks = TIMES.get(word);
        if (ticks == null) {
            ticks = parseTicks(word);
        }
        if (ticks == null) {
            messages.sendConfiguredMessage(player, "time-usage");
            return;
        }
        Before was = before.computeIfAbsent(player.getUniqueId(), id -> new Before());
        if (!was.time) {
            was.time = true;
            was.timeOffset = player.getPlayerTimeOffset();
            was.timeRelative = player.isPlayerTimeRelative();
        }
        player.setPlayerTime(ticks, false);
        messages.sendConfiguredMessage(player, "time-set", "{time}", word);
    }

    /** A time in ticks of the day, or {@code null} when the word is none. */
    private static Long parseTicks(String word) {
        try {
            long ticks = Long.parseLong(word);
            return ticks >= 0 && ticks < DAY ? ticks : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * {@code /cam weather}: {@code clear} or {@code rain} - snow where it
     * snows -, or {@code reset} for the weather of the world again. The game
     * gives a single player no thunderstorm of his own.
     */
    public void weatherCommand(Player player, String[] args) {
        if (!settings.isCamWeatherEnabled()) {
            messages.sendConfiguredMessage(player, "weather-off");
            return;
        }
        if (!plugin.isInCameraMode(player)) {
            messages.sendConfiguredMessage(player, "not-in-cam");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(player, "weather-usage");
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        if (word.equals("reset")) {
            resetWeather(player);
            messages.sendConfiguredMessage(player, "weather-reset");
            return;
        }
        WeatherType type = switch (word) {
            case "clear" -> WeatherType.CLEAR;
            case "rain" -> WeatherType.DOWNFALL;
            default -> null;
        };
        if (type == null) {
            messages.sendConfiguredMessage(player, "weather-usage");
            return;
        }
        Before was = before.computeIfAbsent(player.getUniqueId(), id -> new Before());
        if (!was.weather) {
            was.weather = true;
            was.weatherType = player.getPlayerWeather();
        }
        player.setPlayerWeather(type);
        messages.sendConfiguredMessage(player, "weather-set", "{weather}", word);
    }

    /** Gives him back the time he saw before his first {@code /cam time}. */
    private void resetTime(Player player) {
        Before was = before.get(player.getUniqueId());
        if (was == null || !was.time) {
            return;
        }
        was.time = false;
        // A relative offset of 0 is what the server keeps for a player
        // without a time of his own.
        if (was.timeRelative && was.timeOffset == 0L) {
            player.resetPlayerTime();
        } else {
            player.setPlayerTime(was.timeOffset, was.timeRelative);
        }
    }

    /** Gives him back the weather he saw before his first {@code /cam weather}. */
    private void resetWeather(Player player) {
        Before was = before.get(player.getUniqueId());
        if (was == null || !was.weather) {
            return;
        }
        was.weather = false;
        if (was.weatherType == null) {
            player.resetPlayerWeather();
        } else {
            player.setPlayerWeather(was.weatherType);
        }
    }

    /** At the end of camera mode: the time and the weather he saw before it. */
    public void stopFor(Player player) {
        resetTime(player);
        resetWeather(player);
        before.remove(player.getUniqueId());
    }
}
