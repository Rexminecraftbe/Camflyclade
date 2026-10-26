package de.elia.cameraplugin.speed;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How fast the camera flies, {@code flight-speed}: the speed it starts with,
 * and the mouse wheel to change it in flight.
 *
 * <p>Turning the mouse wheel changes the slot of the hotbar, and the client
 * tells the server about it in every game mode, the adventure mode included.
 * The hotbar is empty in camera mode, so the slot is not needed for anything
 * else: turned up, the camera gets faster, turned down slower - the way the
 * spectator mode of the game does it. The slot itself stays where it was, so
 * the player holds the same item as before once camera mode ends.</p>
 *
 * <p>The server cannot tell the wheel from the number keys, both only change
 * the slot. A number key therefore changes the speed by one step as well, in
 * the direction of the shorter way round the hotbar.</p>
 *
 * <p>With {@code player-settings.flight-speed} the next flight starts at the
 * speed the last one ended with, see
 * {@link de.elia.cameraplugin.preferences.CamPreferences} - held to
 * {@code min} and {@code max} as they are now, in case they changed since.</p>
 */
public final class CamFlightSpeed implements Listener {

    /** The fly speed of the game, the speed 1 in the config file stands for. */
    private static final float NORMAL_FLY_SPEED = 0.1f;

    /** The slots of the hotbar, which the wheel goes round and round. */
    private static final int HOTBAR_SLOTS = 9;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The speed each camera player flies at now, 1 being the speed of the game. */
    private final Map<UUID, Double> speeds = new HashMap<>();
    /** The fly speed each camera player had before, which he gets back at the end. */
    private final Map<UUID, Float> originalSpeeds = new HashMap<>();
    /** The camera players whose keys move them no longer: guests of a tour, which their guide steers. */
    private final Set<UUID> held = new HashSet<>();

    public CamFlightSpeed(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /**
     * Sets the speed of {@code flight-speed.start} on a player entering camera
     * mode, or the one he flew at last, while that is kept.
     */
    public void startFor(Player player) {
        originalSpeeds.putIfAbsent(player.getUniqueId(), player.getFlySpeed());
        Double own = plugin.getPreferences().flightSpeed(player);
        setSpeed(player, own == null ? settings.getFlightSpeedStart()
                : Math.min(settings.getFlightSpeedMax(), Math.max(settings.getFlightSpeedMin(), own)));
    }

    /**
     * Takes away what his keys do to the camera, for a guest of a tour: his
     * fly speed is 0, and the mouse wheel changes it no more, until
     * {@link #release}.
     */
    public void hold(Player player) {
        held.add(player.getUniqueId());
        player.setFlySpeed(0.0f);
    }

    /** Gives a guest whose tour is over the speed back that he flew at. */
    public void release(Player player) {
        if (!held.remove(player.getUniqueId())) {
            return;
        }
        Double speed = speeds.get(player.getUniqueId());
        if (speed != null) {
            player.setFlySpeed((float) (NORMAL_FLY_SPEED * speed));
        }
    }

    /** Gives a player leaving camera mode the fly speed back that he had before. */
    public void stopFor(Player player) {
        held.remove(player.getUniqueId());
        speeds.remove(player.getUniqueId());
        Float original = originalSpeeds.remove(player.getUniqueId());
        if (original != null) {
            player.setFlySpeed(original);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSlotChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Double current = speeds.get(player.getUniqueId());
        if (current == null || !settings.isFlightSpeedWheel()) {
            return;
        }
        int ahead = Math.floorMod(event.getNewSlot() - event.getPreviousSlot(), HOTBAR_SLOTS);
        if (ahead == 0) {
            return;
        }
        if (held.contains(player.getUniqueId())) {
            // On a tour the guide sets the pace; the slot stays all the same.
            event.setCancelled(true);
            return;
        }
        // The slot stays where it was. That also keeps every turn of the
        // wheel one step: the next one starts from the same slot again.
        event.setCancelled(true);
        // Up the wheel the slots count down, and that is the faster way.
        double step = ahead > HOTBAR_SLOTS / 2 ? settings.getFlightSpeedStep() : -settings.getFlightSpeedStep();
        double speed = Math.min(settings.getFlightSpeedMax(),
                Math.max(settings.getFlightSpeedMin(), round(current + step)));
        setSpeed(player, speed);
        plugin.getPreferences().setFlightSpeed(player, speed);
        if (messages.isMessageEnabled("flight-speed")) {
            plugin.getActionBar().flash(player,
                    messages.getMessage("flight-speed").replace("{speed}", format(speed)));
        }
    }

    private void setSpeed(Player player, double speed) {
        speeds.put(player.getUniqueId(), speed);
        if (!held.contains(player.getUniqueId())) {
            player.setFlySpeed((float) (NORMAL_FLY_SPEED * speed));
        }
    }

    /** Rounded to two places, so that steps of 0.1 add up to 0.3 and not to 0.30000000000000004. */
    private static double round(double speed) {
        return BigDecimal.valueOf(speed).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** The speed for the message: 1, 1.5 or 0.25, without zeros at the end. */
    private static String format(double speed) {
        return BigDecimal.valueOf(speed).stripTrailingZeros().toPlainString();
    }
}
