package de.elia.cameraplugin.sky;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.preferences.CamPreferences;
import de.elia.cameraplugin.spot.SpotLook;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A time of day and a weather of his own for the camera player,
 * {@code /cam time} and {@code /cam weather}: his own build at sunset, or in
 * the sun while it rains on the server.
 *
 * <p>Only his client is told about it, nobody else sees anything of it, and
 * the world goes on with its own time and weather - it changes what he sees,
 * not what happens. That is also why he only sees it in camera mode: out
 * there on his feet, a night of his own would be a way to look through the
 * dark of the server.</p>
 *
 * <p>He picks them as he picks his night vision: in camera mode, where they
 * hold at once, and before it, for the next flight. Either way they are
 * kept, and every flight starts with them, until he resets them - kept as
 * {@code player-settings.storage} says, see
 * {@link de.elia.cameraplugin.preferences.CamPreferences}. When camera mode
 * ends he sees what he saw before it again: the time and weather of the
 * world, or one another plugin gave him.</p>
 *
 * <p>A spot saved with a time or a weather of its own, see
 * {@link de.elia.cameraplugin.spot.SpotLook}, shows them to whoever jumps
 * there, in place of his own - until his camera is put down
 * somewhere else, see {@link #showSpot} and {@link #leaveSpot}, or he picks
 * one himself. His own comes back then; what a spot showed him is never kept
 * for his next flight. {@code /cam resume} shows him the time and weather of
 * his last flight again the same way, see
 * {@link de.elia.cameraplugin.resume.CamResume}.</p>
 *
 * <p>A guest on a tour sees the time and weather of his guide in place of
 * his own, {@code follow.guide-time} and {@code follow.guide-weather}: the
 * ones the guide sees in camera mode, the ones of the world while the guide
 * leads out of it, see {@link #showGuide}. He may pick his own all the same,
 * in camera mode as before it: it is kept, but he goes on seeing the guide's
 * until he is off the tour; then his own comes back, with what he picked on
 * the way, see {@link #leaveGuide}.</p>
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
    /** The time each camera player sees as his own now, in ticks, for the menu. */
    private final Map<UUID, Long> shownTime = new HashMap<>();
    /** The weather each camera player sees as his own now, for the menu. */
    private final Map<UUID, WeatherType> shownWeather = new HashMap<>();
    /**
     * What stands in for the own time and weather of each camera player: the
     * spot he was put down at last, see {@link #showSpot}, or the guide he
     * follows, see {@link #showGuide}.
     */
    private final Map<UUID, StandIn> standIns = new HashMap<>();
    /** The camera players on a tour whose guide's sky stands in for their own, see {@link #showGuide}. */
    private final Set<UUID> following = new HashSet<>();

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

    /**
     * Which parts of what a player sees a spot or a guide stands in for, each
     * with his own under it - {@code null} for the time or weather of the
     * world -, to be given back once the spot or the guide no longer counts.
     */
    private static final class StandIn {
        boolean time;
        Long ownTime;
        boolean weather;
        WeatherType ownWeather;
    }

    public CamSky(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /** Whether {@code /cam time} is there: in camera mode and before it, for the next flight. */
    public boolean mayUseTime() {
        return settings.isCamTimeEnabled();
    }

    /** Whether {@code /cam weather} is there: in camera mode and before it, for the next flight. */
    public boolean mayUseWeather() {
        return settings.isCamWeatherEnabled();
    }

    /** The words that may follow {@code /cam time}, for the tab completion. */
    public static String[] timeWords() {
        String[] words = TIMES.keySet().toArray(new String[TIMES.size() + 1]);
        words[TIMES.size()] = "reset";
        return words;
    }

    /** The times {@code /cam time} knows by name, in the order of the day, with their ticks, for the menu. */
    public static Map<String, Long> namedTimes() {
        return Collections.unmodifiableMap(TIMES);
    }

    /** The words that may follow {@code /cam weather}, for the tab completion. */
    public static String[] weatherWords() {
        return new String[]{"clear", "rain", "reset"};
    }

    /**
     * {@code /cam time}: a time by name or in ticks, 0 to 23999, which stands
     * still - the sun does not move on while he takes his picture -, or
     * {@code reset} for the time of the world again. In camera mode it holds
     * at once; before it, from the next flight on. It is kept either way.
     */
    public void timeCommand(Player player, String[] args) {
        if (!settings.isCamTimeEnabled()) {
            messages.sendConfiguredMessage(player, "time-off");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(player, "time-usage");
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        CamPreferences preferences = plugin.getPreferences();
        boolean inCam = plugin.isInCameraMode(player);
        if (word.equals("reset")) {
            StandIn guide = guideStandIn(player, true);
            if (guide != null) {
                guide.ownTime = null;
            } else if (inCam) {
                dropStandIn(player, true, false);
                resetTime(player);
            }
            preferences.setTime(player, null);
            messages.sendConfiguredMessage(player, "time-reset");
            tellLater(player, guide);
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
        StandIn guide = guideStandIn(player, true);
        if (guide != null) {
            guide.ownTime = ticks;
        } else if (inCam) {
            dropStandIn(player, true, false);
            showTime(player, ticks);
        }
        preferences.setTime(player, ticks);
        messages.sendConfiguredMessage(player, preferences.keepsForGood() ? "time-set" : "time-set-memory",
                "{time}", word);
        tellLater(player, guide);
    }

    /**
     * The stand-in of the guide he follows, while the guide's time - or
     * weather - stands in for his own; {@code null} otherwise. What he picks
     * then goes under it: it is his own once the tour is over.
     */
    private StandIn guideStandIn(Player player, boolean time) {
        if (!following.contains(player.getUniqueId())) {
            return null;
        }
        StandIn in = standIns.get(player.getUniqueId());
        return in != null && (time ? in.time : in.weather) ? in : null;
    }

    /** Tells him that what he picked holds once he stops following, while the guide's stands in for it. */
    private void tellLater(Player player, StandIn guide) {
        if (guide != null) {
            messages.sendConfiguredMessage(player, "tour-own-later");
        }
    }

    /**
     * Whether the guide he follows shows him his time, or his weather, in
     * place of his own right now: what he picks holds only after the tour.
     * For the menu.
     */
    public boolean guideStandsIn(Player player, boolean time) {
        return guideStandIn(player, time) != null;
    }

    /** Shows him a time of his own, standing still, after noting what he saw before. */
    private void showTime(Player player, long ticks) {
        Before was = before.computeIfAbsent(player.getUniqueId(), id -> new Before());
        if (!was.time) {
            was.time = true;
            was.timeOffset = player.getPlayerTimeOffset();
            was.timeRelative = player.isPlayerTimeRelative();
        }
        player.setPlayerTime(ticks, false);
        shownTime.put(player.getUniqueId(), ticks);
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
     * gives a single player no thunderstorm of his own. In camera mode and
     * before it, as {@link #timeCommand} has it.
     */
    public void weatherCommand(Player player, String[] args) {
        if (!settings.isCamWeatherEnabled()) {
            messages.sendConfiguredMessage(player, "weather-off");
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(player, "weather-usage");
            return;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        CamPreferences preferences = plugin.getPreferences();
        boolean inCam = plugin.isInCameraMode(player);
        if (word.equals("reset")) {
            StandIn guide = guideStandIn(player, false);
            if (guide != null) {
                guide.ownWeather = null;
            } else if (inCam) {
                dropStandIn(player, false, true);
                resetWeather(player);
            }
            preferences.setWeather(player, null);
            messages.sendConfiguredMessage(player, "weather-reset");
            tellLater(player, guide);
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
        StandIn guide = guideStandIn(player, false);
        if (guide != null) {
            guide.ownWeather = type;
        } else if (inCam) {
            dropStandIn(player, false, true);
            showWeather(player, type);
        }
        preferences.setWeather(player, type);
        messages.sendConfiguredMessage(player, preferences.keepsForGood() ? "weather-set" : "weather-set-memory",
                "{weather}", word);
        tellLater(player, guide);
    }

    /** Shows him a weather of his own, after noting what he saw before. */
    private void showWeather(Player player, WeatherType type) {
        Before was = before.computeIfAbsent(player.getUniqueId(), id -> new Before());
        if (!was.weather) {
            was.weather = true;
            was.weatherType = player.getPlayerWeather();
        }
        player.setPlayerWeather(type);
        shownWeather.put(player.getUniqueId(), type);
    }

    /**
     * The time of his own, for the menu: the one he sees in camera mode, the
     * one kept for his next flight before it, or {@code null} for the time of
     * the world. While the guide he follows shows him his, the one that holds
     * for him again after the tour - which is what he changes there.
     */
    public Long ownTime(Player player) {
        StandIn guide = guideStandIn(player, true);
        if (guide != null) {
            return guide.ownTime;
        }
        if (plugin.isInCameraMode(player)) {
            return shownTime.get(player.getUniqueId());
        }
        return settings.isCamTimeEnabled() ? plugin.getPreferences().time(player) : null;
    }

    /** The weather of his own, for the menu, the way {@link #ownTime} has it; {@code null} for the world's. */
    public WeatherType ownWeather(Player player) {
        StandIn guide = guideStandIn(player, false);
        if (guide != null) {
            return guide.ownWeather;
        }
        if (plugin.isInCameraMode(player)) {
            return shownWeather.get(player.getUniqueId());
        }
        return settings.isCamWeatherEnabled() ? plugin.getPreferences().weather(player) : null;
    }

    /**
     * The time he sees in camera mode, standing still: his own, or that of a
     * spot or of the guide he follows; {@code null} for the time of the world,
     * and out of camera mode. What a spot saved now takes along.
     */
    public Long seenTime(Player player) {
        return shownTime.get(player.getUniqueId());
    }

    /** The weather he sees in camera mode, the way {@link #seenTime} has it; {@code null} for the world's. */
    public WeatherType seenWeather(Player player) {
        return shownWeather.get(player.getUniqueId());
    }

    /** The name {@code /cam time} knows a time by, or {@code null} for a time it has no name for. */
    public static String timeWord(long ticks) {
        for (Map.Entry<String, Long> named : TIMES.entrySet()) {
            if (named.getValue() == ticks) {
                return named.getKey();
            }
        }
        return null;
    }

    /**
     * At the start of camera mode: the time and the weather he picked, in an
     * earlier flight or before this one, while {@code /cam time} and
     * {@code /cam weather} are there at all.
     */
    public void startFor(Player player) {
        CamPreferences preferences = plugin.getPreferences();
        Long ticks = settings.isCamTimeEnabled() ? preferences.time(player) : null;
        if (ticks != null && ticks >= 0 && ticks < DAY) {
            showTime(player, ticks);
        }
        WeatherType weather = settings.isCamWeatherEnabled() ? preferences.weather(player) : null;
        if (weather != null) {
            showWeather(player, weather);
        }
    }

    /** Gives him back the time he saw before his first {@code /cam time}. */
    private void resetTime(Player player) {
        Before was = before.get(player.getUniqueId());
        if (was == null || !was.time) {
            return;
        }
        was.time = false;
        shownTime.remove(player.getUniqueId());
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
        shownWeather.remove(player.getUniqueId());
        if (was.weatherType == null) {
            player.resetPlayerWeather();
        } else {
            player.setPlayerWeather(was.weatherType);
        }
    }

    /**
     * The camera of a player was put down at a spot he jumped to: its time
     * and its weather stand in for his own, as far as
     * {@code cam-time} and {@code cam-weather} let anybody have one. A part
     * the spot does not have shows him his own again, should an earlier spot
     * have stood in for it.
     */
    public void showSpot(Player player, SpotLook look) {
        if (!plugin.isInCameraMode(player)) {
            return;
        }
        Long time = settings.isCamTimeEnabled() ? look.time() : null;
        WeatherType weather = settings.isCamWeatherEnabled() ? look.weather() : null;
        standIn(player, time != null, time, weather != null, weather);
    }

    /**
     * A guest follows his guide on a tour, every tick: the guide's time and
     * weather stand in for his own, as far as {@code follow.guide-time} and
     * {@code follow.guide-weather} say - those the guide sees in camera mode,
     * the ones of the world while he leads out of it. What the guest picks
     * meanwhile goes under them, see {@link #guideStandIn}. Only a change goes
     * to his client.
     */
    public void showGuide(Player guest, Player guide) {
        if (!plugin.isInCameraMode(guest)) {
            return;
        }
        following.add(guest.getUniqueId());
        boolean guideInCam = plugin.isInCameraMode(guide);
        standIn(guest, settings.isTourGuideTime(), guideInCam ? shownTime.get(guide.getUniqueId()) : null,
                settings.isTourGuideWeather(), guideInCam ? shownWeather.get(guide.getUniqueId()) : null);
    }

    /** He is off the tour he followed: his own time and weather hold again, with what he picked on it. */
    public void leaveGuide(Player guest) {
        if (following.remove(guest.getUniqueId())) {
            leaveSpot(guest);
        }
    }

    /**
     * Lets a time and a weather stand in for his own, each with his own noted
     * under it the first time, or shows him his own again for a part nothing
     * stands in for any more. Only a change goes to his client.
     *
     * @param time    whether a time stands in
     * @param ticks   the time that stands in, {@code null} for the one of the world
     * @param weather whether a weather stands in
     * @param type    the weather that stands in, {@code null} for the one of the world
     */
    private void standIn(Player player, boolean time, Long ticks, boolean weather, WeatherType type) {
        UUID id = player.getUniqueId();
        StandIn in = standIns.get(id);
        if (in == null && (time || weather)) {
            in = new StandIn();
            standIns.put(id, in);
        }
        if (in == null) {
            return;
        }
        if (time) {
            if (!in.time) {
                in.time = true;
                in.ownTime = shownTime.get(id);
            }
            if (!Objects.equals(shownTime.get(id), ticks)) {
                restoreTime(player, ticks);
            }
        } else if (in.time) {
            in.time = false;
            restoreTime(player, in.ownTime);
        }
        if (weather) {
            if (!in.weather) {
                in.weather = true;
                in.ownWeather = shownWeather.get(id);
            }
            if (shownWeather.get(id) != type) {
                restoreWeather(player, type);
            }
        } else if (in.weather) {
            in.weather = false;
            restoreWeather(player, in.ownWeather);
        }
        if (!in.time && !in.weather) {
            standIns.remove(id);
        }
    }

    /**
     * His camera was put down somewhere that is no spot - at his body, at a
     * player he follows -, or he is off a tour: what a spot or a guide showed
     * him gives way to his own time and weather again.
     */
    public void leaveSpot(Player player) {
        StandIn in = standIns.remove(player.getUniqueId());
        if (in == null) {
            return;
        }
        if (in.time) {
            restoreTime(player, in.ownTime);
        }
        if (in.weather) {
            restoreWeather(player, in.ownWeather);
        }
    }

    /**
     * He picks a time or a weather himself: for that part no spot stands in
     * any more, and his own from before is forgotten - the new one is his own
     * now. A guide does not give way, see {@link #guideStandIn}.
     */
    private void dropStandIn(Player player, boolean time, boolean weather) {
        StandIn in = standIns.get(player.getUniqueId());
        if (in == null) {
            return;
        }
        if (time) {
            in.time = false;
        }
        if (weather) {
            in.weather = false;
        }
        if (!in.time && !in.weather) {
            standIns.remove(player.getUniqueId());
        }
    }

    /** Shows him a time: the one given - his own, say -, or the one of the world for {@code null}. */
    private void restoreTime(Player player, Long own) {
        if (own != null) {
            showTime(player, own);
        } else {
            resetTime(player);
        }
    }

    /** Shows him a weather: the one given - his own, say -, or the one of the world for {@code null}. */
    private void restoreWeather(Player player, WeatherType own) {
        if (own != null) {
            showWeather(player, own);
        } else {
            resetWeather(player);
        }
    }

    /** At the end of camera mode: the time and the weather he saw before it. */
    public void stopFor(Player player) {
        standIns.remove(player.getUniqueId());
        following.remove(player.getUniqueId());
        resetTime(player);
        resetWeather(player);
        before.remove(player.getUniqueId());
        shownTime.remove(player.getUniqueId());
        shownWeather.remove(player.getUniqueId());
    }
}
