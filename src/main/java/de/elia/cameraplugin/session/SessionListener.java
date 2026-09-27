package de.elia.cameraplugin.session;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/** What happens to camera mode when a player joins, leaves or dies. */
public final class SessionListener implements Listener {

    private final CameraPlugin plugin;
    private final CameraPlayers cameraPlayers;

    public SessionListener(CameraPlugin plugin) {
        this.plugin = plugin;
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (cameraPlayers.contains(event.getPlayer().getUniqueId())) {
            plugin.exitCameraMode(event.getPlayer());
        }
        plugin.getMovementGuard().forget(event.getPlayer().getUniqueId());
        plugin.getNoCollisionTeam().removePlayerFromNoCollisionTeam(event.getPlayer());
        plugin.getStartChecks().forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getNoCollisionTeam().updateViewerTeam(event.getPlayer());
                plugin.getVisibility().updateVisibilityForAll();
                plugin.getCamModeObjective().setScore(event.getPlayer(), 0);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Der Spieler soll sterben, aber vorher den Kamera-Modus korrekt beenden.
        // Die XP werden vom Tod selbst gehandhabt.
        Player player = event.getEntity();
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        // Der Server hat seine Drops aus dem leeren Inventar des Kamera-Spielers
        // gesammelt. Fallen sollen stattdessen seine eigenen Sachen aus dem
        // CameraData - getContents() hält alle Slots, Rüstung und Zweithand
        // eingeschlossen, eine eigene Schleife für die Rüstung ließe sie
        // doppelt fallen. Mit keepInventory fällt gar nichts: exitCameraMode
        // gibt ihm sein Inventar zurück, und der Server lässt es ihm.
        if (!event.getKeepInventory()) {
            event.getDrops().clear();
            for (ItemStack item : data.getOriginalInventoryContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    event.getDrops().add(item);
                }
            }
        }
        plugin.exitCameraMode(player);
    }
}
