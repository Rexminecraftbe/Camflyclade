package de.elia.cameraplugin.timelimit;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.storage.StorageMode;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How long camera mode may run at a time and how long a player waits before
 * he may start it again, the section {@code time-limit}.
 *
 * <p>A cooldown is kept as the moment it began, in the file of the player or
 * only in memory, as {@code time-limit.storage} says - in the file it
 * outlasts {@code /cam reload} and a restart, the way the charge of the
 * battery does. How long it lasts is worked out from that moment whenever
 * somebody asks, so a new {@code cooldown-seconds} counts for a cooldown
 * that is running as well. Only the bossbar of {@code show-bossbar-cooldown}
 * runs a task, once a second, and says when it is over - also after the
 * player joins with a cooldown that is not over yet.</p>
 */
public final class CamTimeLimit {

    /** Ticks between two looks at a cooldown. */
    private static final long INTERVAL = 20L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    private final PlayerStore store;
    private final Map<UUID, BukkitRunnable> timeLimitTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    /** When the cooldown of each player began, on {@code storage: memory}. */
    private final Map<UUID, Long> cooldownsInMemory = new HashMap<>();
    /** The task watching each cooldown that is running for somebody online, and its bossbar. */
    private final Map<UUID, Watch> cooldownWatches = new HashMap<>();

    /** A cooldown being watched: the task, and the bossbar while there is one. */
    private static final class Watch {
        BukkitRunnable task;
        BossBar bar;

        void remove() {
            task.cancel();
            if (bar != null) {
                bar.removeAll();
            }
        }
    }

    public CamTimeLimit(CameraPlugin plugin, PlayerStore store) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.store = store;
    }

    public static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return h + "h " + m + "m " + s + "s";
        } else if (seconds >= 60) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + "m " + s + "s";
        } else {
            return seconds + " seconds";
        }
    }

    public void startTimeLimit(Player player) {
        if (!settings.isTimeLimitEnabled()) return;
        cancelTimeLimit(player);
        BossBar bar = null;
        if (settings.showsBossbar()) {
            bar = Bukkit.createBossBar("", settings.getBossbarColor(), settings.getBossbarStyle());
            bar.addPlayer(player);
            bossBars.put(player.getUniqueId(), bar);
        }
        long total = settings.getDurationSeconds();
        BossBar finalBar = bar;
        BukkitRunnable task = new BukkitRunnable() {
            long remaining = total;
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    cancel();
                    if (finalBar != null) finalBar.removeAll();
                    return;
                }
                long before = remaining;
                remaining--;
                plugin.getEndWarning().check(player, before, remaining, "end-warning-time-limit");
                if (finalBar != null) {
                    finalBar.setProgress(Math.max(0.0, remaining / (double) total));
                    if (messages.isMessageEnabled("bossbar-text")) {
                        finalBar.setTitle(messages.getMessage("bossbar-text").replace("%time%", formatDuration(remaining)));
                    }
                }
                if (remaining <= 0) {
                    cancel();
                    if (finalBar != null) finalBar.removeAll();
                    plugin.exitCameraMode(player, EndReason.TIME_LIMIT);
                    messages.sendConfiguredMessage(player, "time-limit-expired");
                }
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
        timeLimitTasks.put(player.getUniqueId(), task);
    }

    public void cancelTimeLimit(Player player) {
        BukkitRunnable t = timeLimitTasks.remove(player.getUniqueId());
        if (t != null) t.cancel();
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    /**
     * Begins the cooldown of a player whose camera mode has just ended. On
     * {@code storage: file} it goes into his record, which whoever ends
     * camera mode writes right after this.
     *
     * <p>Not after a {@code /cam reload}: the reload ended camera mode, not
     * the player, and he is not to wait for it.</p>
     */
    public void startCooldown(Player player, EndReason reason) {
        if (!settings.isCooldownsEnabled() || settings.getCooldownSeconds() <= 0 || reason == EndReason.RELOAD) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (settings.getCooldownStorage() == StorageMode.MEMORY) {
            cooldownsInMemory.put(id, now);
        } else {
            store.get(id).setCooldownStarted(now);
        }
        stopWatching(id);
        // A tick later: camera mode is still ending right now, and a player
        // who is leaving the server is gone by then.
        Bukkit.getScheduler().runTask(plugin, () -> watch(player));
    }

    /**
     * How long the cooldown of a player still runs, in milliseconds, 0 when
     * there is none. Works for a player who is not online as well, for
     * {@code /cam stats player}.
     */
    private long remainingMillis(UUID playerId) {
        if (!settings.isCooldownsEnabled()) {
            return 0L;
        }
        Long started = settings.getCooldownStorage() == StorageMode.MEMORY
                ? cooldownsInMemory.get(playerId)
                : store.look(playerId).getCooldownStarted();
        if (started == null) {
            return 0L;
        }
        long remaining = started + settings.getCooldownSeconds() * 1000L - System.currentTimeMillis();
        if (remaining <= 0L) {
            // Over, and of no further use in memory.
            cooldownsInMemory.remove(playerId);
            return 0L;
        }
        return remaining;
    }

    public boolean isCooldownActive(Player player) {
        return remainingMillis(player.getUniqueId()) > 0L;
    }

    /** How long the cooldown of a player still runs, in whole seconds rounded up, 0 when there is none. */
    public long getCooldownRemaining(UUID playerId) {
        return (remainingMillis(playerId) + 999L) / 1000L;
    }

    public long getCooldownRemaining(Player player) {
        return getCooldownRemaining(player.getUniqueId());
    }

    /**
     * Watches the cooldown of a player who is online, until it is over: says
     * so with {@code cooldown-available}, and shows the bossbar of
     * {@code show-bossbar-cooldown} until then. Nothing when he has none, or
     * when it is watched already.
     */
    private void watch(Player player) {
        UUID id = player.getUniqueId();
        if (!player.isOnline() || cooldownWatches.containsKey(id) || remainingMillis(id) <= 0L) {
            return;
        }
        Watch watch = new Watch();
        if (settings.showsCooldownBossbar()) {
            watch.bar = Bukkit.createBossBar("", settings.getBossbarColor(), settings.getBossbarStyle());
            watch.bar.addPlayer(player);
            updateCooldownBar(watch.bar, remainingMillis(id));
        }
        watch.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopWatching(id);
                    return;
                }
                long remaining = remainingMillis(id);
                if (remaining <= 0L) {
                    stopWatching(id);
                    if (messages.isMessageEnabled("cooldown-available")) {
                        player.sendMessage(messages.getMessage("cooldown-available"));
                    }
                    return;
                }
                if (watch.bar != null) {
                    updateCooldownBar(watch.bar, remaining);
                }
            }
        };
        watch.task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        cooldownWatches.put(id, watch);
    }

    /** The bossbar of a cooldown fills up the way a battery charges, until he may start again. */
    private void updateCooldownBar(BossBar bar, long remainingMillis) {
        double total = settings.getCooldownSeconds() * 1000.0;
        bar.setProgress(Math.max(0.0, Math.min(1.0, 1.0 - remainingMillis / total)));
        bar.setTitle(messages.isMessageEnabled("cooldown-bossbar")
                ? messages.getMessage("cooldown-bossbar")
                        .replace("{time}", formatDuration((remainingMillis + 999L) / 1000L))
                : "");
    }

    private void stopWatching(UUID playerId) {
        Watch watch = cooldownWatches.remove(playerId);
        if (watch != null) {
            watch.remove();
        }
    }

    /** A player joins: a cooldown that is not over yet is watched again, with its bossbar. */
    public void onJoin(Player player) {
        watch(player);
    }

    /** A player leaves: his cooldown goes on without him, only the watch stops. */
    public void onQuit(Player player) {
        stopWatching(player.getUniqueId());
    }

    /**
     * After a reload: on {@code storage: memory} every cooldown is forgotten,
     * on {@code file} they all go on. Whoever has one left sees it again the
     * way the config file now says.
     */
    public void onReload() {
        for (Watch watch : cooldownWatches.values()) {
            watch.remove();
        }
        cooldownWatches.clear();
        cooldownsInMemory.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            watch(player);
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : timeLimitTasks.values()) {
            task.cancel();
        }
        timeLimitTasks.clear();
        for (Watch watch : cooldownWatches.values()) {
            watch.remove();
        }
        cooldownWatches.clear();
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
    }
}
