package de.elia.cameraplugin.battery;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The battery camera mode runs on, {@code flight-limit: battery} and the
 * section {@code battery}: it empties while the camera flies and fills up
 * again outside camera mode. A start needs {@code min-start} seconds of it,
 * and camera mode ends when it is empty.
 *
 * <p>Nothing counts while a player is not flying. His record holds the charge
 * and the moment it was worked out - or, on {@code storage: memory}, a map
 * here that {@code /cam reload} and a restart empty -, and whatever has come
 * back since is worked out from the time that has passed whenever somebody
 * asks. Only the bossbar runs a task, once a second: in flight with what is
 * left, and with {@code show-bossbar-charging} after the flight until the
 * battery is full again - also after he joins with one that is not full
 * yet.</p>
 *
 * <p>With {@code recharge-offline: false} only the time on the server
 * counts: the charge is settled when he leaves, and the clock starts again
 * when he joins.</p>
 */
public final class CamBattery {

    /** Ticks between two looks at a battery with a bossbar. */
    private static final long INTERVAL = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    /** The battery of every camera player while it runs, by player id. */
    private final Map<UUID, Flight> flights = new HashMap<>();
    /** The bossbar of every player whose battery is charging, by player id. */
    private final Map<UUID, Bar> chargingBars = new HashMap<>();
    /** What each battery held between two flights, on {@code storage: memory}. */
    private final Map<UUID, Stored> inMemory = new HashMap<>();

    /** What a battery held at a moment, as {@link System#currentTimeMillis()} counts it. */
    private record Stored(double charge, long at) {
    }

    /** A bossbar and the task that keeps it up to date. */
    private static class Bar {
        BossBar bar;
        BukkitRunnable task;

        void remove() {
            if (task != null) {
                task.cancel();
            }
            if (bar != null) {
                bar.removeAll();
            }
        }
    }

    /**
     * One battery in flight: what it held at the start, and when that was.
     * Whatever it holds now follows from those two.
     */
    private static final class Flight extends Bar {
        final double startCharge;
        final long startedAt;
        /** What it held at the last look, for {@code end-warning}. */
        double lastCharge;

        Flight(double startCharge, long startedAt) {
            this.startCharge = startCharge;
            this.startedAt = startedAt;
            this.lastCharge = startCharge;
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
        return chargedUntil(playerId, now);
    }

    /** What the battery of a player holds at {@code now}, outside camera mode. */
    private double chargedUntil(UUID playerId, long now) {
        Stored stored = stored(playerId);
        double capacity = settings.getBatteryCapacity();
        if (stored == null) {
            return capacity;
        }
        double back = Math.max(0L, now - stored.at()) / 1000.0 * settings.getBatteryRecharge();
        return Math.min(capacity, stored.charge() + back);
    }

    /**
     * What the battery of a player held when it was last worked out, from
     * his record or from memory as {@code storage} says, or {@code null} for
     * a battery nobody has touched yet.
     */
    private Stored stored(UUID playerId) {
        if (settings.getBatteryStorage() == StorageMode.MEMORY) {
            return inMemory.get(playerId);
        }
        PlayerRecord record = store.look(playerId);
        Double charge = record.getBatteryCharge();
        return charge == null ? null : new Stored(charge, record.getBatteryUpdated());
    }

    /**
     * Keeps what the battery of a player holds at a moment. Into his record
     * on {@code storage: file}, which is written when camera mode ends and
     * when he leaves.
     */
    private void keep(UUID playerId, double charge, long at) {
        if (settings.getBatteryStorage() == StorageMode.MEMORY) {
            inMemory.put(playerId, new Stored(charge, at));
        } else {
            store.get(playerId).setBattery(charge, at);
        }
    }

    /** How long until a battery holding {@code charge} holds {@code wanted}, in whole seconds. */
    private long secondsUntil(double charge, double wanted) {
        return Math.max(1L, (long) Math.ceil((wanted - charge) / settings.getBatteryRecharge()));
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
        messages.sendMessage(player, "battery-low",
                "{charge}", CamTimeLimit.formatDuration((long) Math.floor(charge)),
                "{needed}", CamTimeLimit.formatDuration((long) needed),
                "{time}", CamTimeLimit.formatDuration(secondsUntil(charge, Math.max(needed, 1.0))));
        return false;
    }

    /** Sets the battery of a player entering camera mode going. */
    public void startFor(Player player) {
        if (!settings.isBatteryEnabled()) {
            return;
        }
        stopFor(player);
        stopCharging(player);
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Flight flight = new Flight(chargedUntil(id, now), now);
        keep(id, flight.startCharge, now);
        if (settings.showsBatteryBossbar()) {
            flight.bar = Bukkit.createBossBar("", settings.getBatteryBossbarColor(),
                    settings.getBatteryBossbarStyle());
            flight.bar.addPlayer(player);
            updateFlightBar(flight, now);
        }
        flight.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !plugin.isInCameraMode(player)) {
                    stopFor(player);
                    return;
                }
                long at = System.currentTimeMillis();
                updateFlightBar(flight, at);
                double left = flight.chargeAt(at);
                plugin.getEndWarning().check(player, flight.lastCharge, left, "end-warning-battery");
                flight.lastCharge = left;
                if (left <= 0.0) {
                    plugin.exitCameraMode(player, EndReason.BATTERY);
                    messages.sendMessage(player, "battery-empty");
                }
            }
        };
        flight.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        flights.put(id, flight);
    }

    private void updateFlightBar(Flight flight, long now) {
        if (flight.bar == null) {
            return;
        }
        double charge = flight.chargeAt(now);
        flight.bar.setProgress(progress(charge));
        flight.bar.setTitle(messages.isMessageEnabled("battery-bossbar")
                ? messages.getMessage("battery-bossbar")
                        .replace("{time}", CamTimeLimit.formatDuration((long) Math.ceil(charge)))
                : "");
    }

    private double progress(double charge) {
        return Math.max(0.0, Math.min(1.0, charge / settings.getBatteryCapacity()));
    }

    /**
     * Stops the battery of a player leaving camera mode and writes down what
     * is left of it. On {@code storage: file} the record is written to its
     * file by whoever ends camera mode, together with the statistics. From here on it charges, and the
     * bossbar of {@code show-bossbar-charging} shows that.
     */
    public void stopFor(Player player) {
        Flight flight = flights.remove(player.getUniqueId());
        if (flight == null) {
            return;
        }
        flight.remove();
        long now = System.currentTimeMillis();
        keep(player.getUniqueId(), flight.chargeAt(now), now);
        if (!plugin.isShuttingDown()) {
            // A tick later: camera mode is still ending right now, and a
            // player who is leaving the server is gone by then.
            Bukkit.getScheduler().runTask(plugin, () -> startCharging(player));
        }
    }

    /**
     * Shows the bossbar of a battery that is charging, until it is full, with
     * {@code show-bossbar-charging}. Nothing for a full battery or a camera
     * player, whose battery is not charging.
     */
    private void startCharging(Player player) {
        if (!showsChargingBar() || !player.isOnline() || plugin.isInCameraMode(player)
                || chargingBars.containsKey(player.getUniqueId())) {
            return;
        }
        UUID id = player.getUniqueId();
        double charge = charge(id);
        if (charge >= settings.getBatteryCapacity()) {
            return;
        }
        Bar charging = new Bar();
        charging.bar = Bukkit.createBossBar("", settings.getBatteryBossbarColor(),
                settings.getBatteryBossbarStyle());
        charging.bar.addPlayer(player);
        updateChargingBar(charging, charge);
        charging.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !showsChargingBar() || plugin.isInCameraMode(player)) {
                    stopCharging(player);
                    return;
                }
                double now = charge(id);
                if (now >= settings.getBatteryCapacity()) {
                    stopCharging(player);
                    return;
                }
                updateChargingBar(charging, now);
            }
        };
        charging.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        chargingBars.put(id, charging);
    }

    private boolean showsChargingBar() {
        return settings.isBatteryEnabled() && settings.showsBatteryBossbarCharging();
    }

    private void updateChargingBar(Bar charging, double charge) {
        charging.bar.setProgress(progress(charge));
        charging.bar.setTitle(messages.isMessageEnabled("battery-bossbar-charging")
                ? messages.getMessage("battery-bossbar-charging")
                        .replace("{charge}", CamTimeLimit.formatDuration((long) Math.floor(charge)))
                        .replace("{time}", CamTimeLimit.formatDuration(
                                secondsUntil(charge, settings.getBatteryCapacity())))
                : "");
    }

    private void stopCharging(Player player) {
        Bar charging = chargingBars.remove(player.getUniqueId());
        if (charging != null) {
            charging.remove();
        }
    }

    /**
     * After a reload: on {@code storage: memory} every battery is full again,
     * on {@code file} each holds what it held. Whoever has one that is not
     * full sees it charging the way the config file now says - a bossbar
     * that is up already goes and comes back in its new colour and notches.
     */
    public void onReload() {
        for (Bar charging : chargingBars.values()) {
            charging.remove();
        }
        chargingBars.clear();
        inMemory.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            startCharging(player);
        }
    }

    /**
     * A player joins: with {@code recharge-offline: false} the time he was
     * away does not count, so his clock starts again now. A battery that is
     * not full yet shows its bossbar again.
     */
    public void onJoin(Player player) {
        if (!settings.isBatteryEnabled()) {
            return;
        }
        if (!settings.batteryRechargesOffline()) {
            Stored stored = stored(player.getUniqueId());
            if (stored != null) {
                keep(player.getUniqueId(), stored.charge(), System.currentTimeMillis());
            }
        }
        startCharging(player);
    }

    /**
     * A player leaves, after his camera mode has ended: with
     * {@code recharge-offline: false} what he has charged on the server so
     * far is settled, since the time from now on will not count.
     */
    public void onQuit(Player player) {
        stopCharging(player);
        if (!settings.isBatteryEnabled() || settings.batteryRechargesOffline()) {
            return;
        }
        if (stored(player.getUniqueId()) != null) {
            long now = System.currentTimeMillis();
            keep(player.getUniqueId(), chargedUntil(player.getUniqueId(), now), now);
        }
    }

    /** Settles the battery of everybody online and takes every bossbar down, when the plugin is switched off. */
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
        for (Bar charging : chargingBars.values()) {
            charging.remove();
        }
        chargingBars.clear();
    }
}
