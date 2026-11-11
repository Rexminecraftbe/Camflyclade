package de.elia.cameraplugin.preferences;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.nightvision.NightVisionMode;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The settings each player picks for himself, {@code player-settings}: his
 * night vision of {@code /cam nightvision}, the time and weather of
 * {@code /cam time} and {@code /cam weather}, and the speed he last flew at.
 *
 * <p>The night vision, the time and the weather are always kept - that is
 * what picking them is for, before camera mode as well as in it. The speed
 * only while {@code player-settings.flight-speed} is on; otherwise every
 * flight starts with {@code flight-speed.start}.</p>
 *
 * <p>Where they are kept is {@code player-settings.storage}: in the file of the
 * player, where they outlast his leaving and a restart, or in memory until
 * he leaves the server.</p>
 */
public final class CamPreferences {

    private final CamSettings settings;
    private final PlayerStore store;
    /** What each player picked, on {@code storage: memory}, until he leaves. */
    private final Map<UUID, Picked> inMemory = new HashMap<>();

    /** What one player picked, on {@code storage: memory}; {@code null} for nothing. */
    private static final class Picked {
        NightVisionMode nightVision;
        Double flightSpeed;
        Long time;
        WeatherType weather;
    }

    public CamPreferences(CamSettings settings, PlayerStore store) {
        this.settings = settings;
        this.store = store;
    }

    private boolean inFile() {
        return settings.getPlayerSettingsStorage() == StorageMode.FILE;
    }

    /** Whether what he picks stays after he left the server, for the messages that tell him. */
    public boolean keepsForGood() {
        return inFile();
    }

    private PlayerRecord record(Player player) {
        return store.get(player.getUniqueId());
    }

    private Picked picked(Player player) {
        return inMemory.computeIfAbsent(player.getUniqueId(), id -> new Picked());
    }

    /** The night vision he picked, or {@code null} for none. */
    public NightVisionMode nightVision(Player player) {
        if (inFile()) {
            return record(player).getNightVision();
        }
        Picked picked = inMemory.get(player.getUniqueId());
        return picked == null ? null : picked.nightVision;
    }

    /**
     * Keeps the night vision he picked, {@code null} for none. Written at
     * once: he picks it as often before camera mode as in it.
     */
    public void setNightVision(Player player, NightVisionMode mode) {
        if (inFile()) {
            record(player).setNightVision(mode);
            store.save(player.getUniqueId());
        } else {
            picked(player).nightVision = mode;
        }
    }

    /** Whether the speed he flies at is kept for the next flight, {@code player-settings.flight-speed}. */
    public boolean keepsFlightSpeed() {
        return settings.isPlayerSettingsFlightSpeed();
    }

    /** The speed he last flew at, or {@code null} for none or while it is not kept. */
    public Double flightSpeed(Player player) {
        if (!keepsFlightSpeed()) {
            return null;
        }
        if (inFile()) {
            return record(player).getFlightSpeed();
        }
        Picked picked = inMemory.get(player.getUniqueId());
        return picked == null ? null : picked.flightSpeed;
    }

    /**
     * Keeps the speed he flies at now. Only noted, not written: a turn of the
     * wheel comes often, and the end of camera mode writes his file anyway.
     */
    public void setFlightSpeed(Player player, double speed) {
        if (!keepsFlightSpeed()) {
            return;
        }
        if (inFile()) {
            record(player).setFlightSpeed(speed);
        } else {
            picked(player).flightSpeed = speed;
        }
    }

    /** The time of {@code /cam time} he picked, or {@code null} for none. */
    public Long time(Player player) {
        if (inFile()) {
            return record(player).getCamTime();
        }
        Picked picked = inMemory.get(player.getUniqueId());
        return picked == null ? null : picked.time;
    }

    /**
     * Keeps the time he picked, {@code null} for none. Written at once, as
     * the night vision is: he picks it before camera mode as well as in it.
     */
    public void setTime(Player player, Long ticks) {
        if (inFile()) {
            record(player).setCamTime(ticks);
            store.save(player.getUniqueId());
        } else {
            picked(player).time = ticks;
        }
    }

    /** The weather of {@code /cam weather} he picked, or {@code null} for none. */
    public WeatherType weather(Player player) {
        if (inFile()) {
            return record(player).getCamWeather();
        }
        Picked picked = inMemory.get(player.getUniqueId());
        return picked == null ? null : picked.weather;
    }

    /** Keeps the weather he picked, {@code null} for none; written at once, as the time is. */
    public void setWeather(Player player, WeatherType weather) {
        if (inFile()) {
            record(player).setCamWeather(weather);
            store.save(player.getUniqueId());
        } else {
            picked(player).weather = weather;
        }
    }

    /** Forgets what a player who left the server picked, on {@code storage: memory}. His file keeps its own. */
    public void forget(UUID playerId) {
        inMemory.remove(playerId);
    }

    public void onDisable() {
        inMemory.clear();
    }
}
