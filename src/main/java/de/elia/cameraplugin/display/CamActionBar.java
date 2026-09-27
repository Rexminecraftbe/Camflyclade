package de.elia.cameraplugin.display;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The line above the hotbar while camera mode runs and right after it, {@code action-bar}. */
public final class CamActionBar {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> offMessageTasks = new HashMap<>();

    public CamActionBar(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    public void startActionBar(Player player) {
        if (!settings.isActionBarEnabled()) return;
        stopActionBar(player);
        BukkitRunnable off = offMessageTasks.remove(player.getUniqueId());
        if (off != null) off.cancel();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(settings.getActionBarOnMessage()));
            }
        };
        task.runTaskTimer(plugin, 0L, 40L);
        actionBarTasks.put(player.getUniqueId(), task);
    }

    public void stopActionBar(Player player) {
        BukkitRunnable task = actionBarTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Ends the line of a running camera mode and shows the one saying it has
     * ended - the latter not while the plugin is being switched off.
     */
    public void showActionBarOffMessage(Player player) {
        stopActionBar(player);
        if (!settings.isActionBarEnabled() || plugin.isShuttingDown()) return;
        BukkitRunnable existing = offMessageTasks.remove(player.getUniqueId());
        if (existing != null) existing.cancel();

        BukkitRunnable task = new BukkitRunnable() {
            private int ticks = 0;


            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                if (ticks >= settings.getActionBarOffDuration()) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(settings.getActionBarOffMessage()));
                ticks++;
            }
        };

        task.runTaskTimer(plugin, 0L, 1L);
        offMessageTasks.put(player.getUniqueId(), task);
    }

    public void onDisable() {
        for (BukkitRunnable task : actionBarTasks.values()) {
            task.cancel();
        }
        actionBarTasks.clear();
        for (BukkitRunnable task : offMessageTasks.values()) {
            task.cancel();
        }
        offMessageTasks.clear();
    }
}
