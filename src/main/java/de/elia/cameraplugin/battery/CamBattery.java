package de.elia.cameraplugin.battery;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The battery camera mode runs on, the section {@code battery}: it empties
 * while the camera flies and fills up again outside camera mode. A start
 * needs {@code min-start} seconds of it, and camera mode ends when it is
 * empty.
 *
 * <p>Nothing counts while a player is not flying. His record holds the charge
 * and the moment it was worked out, and whatever has come back since is
 * worked out from the time that has passed whenever somebody asks. Only
 * camera mode itself runs a task, once a second, for the bossbar and for the
 * moment it runs empty.</p>
 *
 * <p>With {@code recharge-offline: false} only the time on the server
 * counts: the charge is settled when he leaves, and the clock starts again
 * when he joins.</p>
 */
public final class CamBattery {

    /** Ticks between two looks at a flying battery. */
    private static final long INTERVAL = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    /** The battery of every camera player while it runs, by player id. */
    private final Map<UUID, Flight> flights = new HashMap<>();

    /**
     * One battery in flight: what it held at the start, and when that was.
     * Whatever it holds now follows from those two.
     */
    private static final class Flight {
        final double startCharge;
        final long startedAt;
        BukkitRunnable task;
        BossBar bar;

        Flight(double startCharge, long startedAt) {
            this.startCharge = startCharge;
            this.startedAt = startedAt;
        }

        double chargeAt(long now) {
            return Math.max(0.0, startCharge - (now - startedAt) / 1000.0);
        }
    }

    public CamBattery(CameraPlugin plugin, PlayerStore store) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.store = store;
    }

    /**
     * What the battery of a player holds right now, in seconds of flight: in
     * camera mode what is left of it, outside of it what has come back since
     * it was last worked out, up to a full battery.
     */
    public double charge(UUID playerId) {
        long now = System.currentTimeMillis();
        Flight flight = flights.get(playerId);
        if (flight != null) {
            return Math.min(flight.chargeAt(now), settings.getBatteryCapacity());
        }
        return chargedUntil(store.peek(playerId), now);
    }

    /** What a record holds at {@code now}, outside camera mode. */
    private double chargedUntil(PlayerRecord record, long now) {
        Double stored = record.getBatteryCharge();
        double capacity = settings.getBatteryCapacity();
        if (stored == null) {
            return capacity;
        }
        double back = Math.max(0L, now - record.getBatteryUpdated()) / 1000.0 * settings.getBatteryRecharge();
        return Math.min(capacity, stored + back);
    }

    /**
     * Checks whether the battery holds enough for a start, {@code min-start}.
     *
     * @return whether he may start; if not, he has been told why and how long
     *         it takes until he can
     */
    public boolean checkStart(Player player) {
        if (!settings.isBatteryEnabled()) {
            return true;
        }
        double charge = charge(player.getUniqueId());
        double needed = settings.getBatteryMinStart();
        // Any charge at all is needed, also with min-start: 0 - an empty
        // battery would end camera mode in the very second it starts.
        if (charge >= needed && charge > 0.0) {
            return true;
        }
        double missing = Math.max(needed - charge, 1.0 - charge);
        long wait = (long) Math.ceil(missing / settings.getBatteryRecharge());
        messages.sendMessage(player, "battery-low",
                "{charge}", CamTimeLimit.formatDuration((long) Math.floor(charge)),
                "{needed}", CamTimeLimit.formatDuration((long) needed),
                "{time}", CamTimeLimit.formatDuration(Math.max(1L, wait)));
        return false;
    }

    /** Sets the battery of a player entering camera mode going. */
    public void startFor(Player player) {
        if (!settings.isBatteryEnabled()) {
            return;
        }
        stopFor(player);
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        PlayerRecord record = store.get(id);
        Flight flight = new Flight(chargedUntil(record, now), now);
        record.setBattery(flight.startCharge, now);
        if (settings.showsBatteryBossbar()) {
            flight.bar = Bukkit.createBossBar("", settings.getBatteryBossbarColor(), BarStyle.SEGMENTED_10);
            flight.bar.addPlayer(player);
            updateBar(flight, now);
        }
        flight.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !plugin.isInCameraMode(player)) {
                    stopFor(player);
                    return;
                }
                long at = System.currentTimeMillis();
                updateBar(flight, at);
                if (flight.chargeAt(at) <= 0.0) {
                    plugin.exitCameraMode(player, EndReason.BATTERY);
                    messages.sendMessage(player, "battery-empty");
                }
            }
        };
        flight.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        flights.put(id, flight);
    }

    private void updateBar(Flight flight, long now) {
        if (flight.bar == null) {
            return;
        }
        double charge = flight.chargeAt(now);
        flight.bar.setProgress(Math.max(0.0, Math.min(1.0, charge / settings.getBatteryCapacity())));
        flight.bar.setTitle(messages.isMessageEnabled("battery-bossbar")
                ? messages.getMessage("battery-bossbar")
                        .replace("{time}", CamTimeLimit.formatDuration((long) Math.ceil(charge)))
                : "");
    }

    /**
     * Stops the battery of a player leaving camera mode and writes down what
     * is left of it. The record is written to its file by whoever ends camera
     * mode, together with the statistics.
     */
    public void stopFor(Player player) {
        Flight flight = flights.remove(player.getUniqueId());
        if (flight == null) {
            return;
        }
        if (flight.task != null) {
            flight.task.cancel();
        }
        if (flight.bar != null) {
            flight.bar.removeAll();
        }
        long now = System.currentTimeMillis();
        store.get(player.getUniqueId()).setBattery(flight.chargeAt(now), now);
    }

    /**
     * A player joins: with {@code recharge-offline: false} the time he was
     * away does not count, so his clock starts again now.
     */
    public void onJoin(Player player) {
        if (!settings.isBatteryEnabled() || settings.batteryRechargesOffline()) {
            return;
        }
        PlayerRecord record = store.get(player.getUniqueId());
        if (record.getBatteryCharge() != null) {
            record.setBattery(record.getBatteryCharge(), System.currentTimeMillis());
        }
    }

    /**
     * A player leaves, after his camera mode has ended: with
     * {@code recharge-offline: false} what he has charged on the server so
     * far is settled, since the time from now on will not count.
     */
    public void onQuit(Player player) {
        if (!settings.isBatteryEnabled() || settings.batteryRechargesOffline()) {
            return;
        }
        PlayerRecord record = store.get(player.getUniqueId());
        if (record.getBatteryCharge() != null) {
            long now = System.currentTimeMillis();
            record.setBattery(chargedUntil(record, now), now);
        }
    }

    /** Settles the battery of everybody online, when the plugin is switched off. */
    public void onDisable() {
        for (UUID id : flights.keySet().toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                stopFor(player);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            onQuit(player);
        }
    }
}
