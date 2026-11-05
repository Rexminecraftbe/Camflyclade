package de.elia.cameraplugin.session;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/** What happens to camera mode when a player joins, leaves or dies. */
public final class SessionListener implements Listener {

    /** How close to his body a dead player has to be to count as standing there, in blocks. */
    private static final double AT_BODY = 1.0;

    private final CameraPlugin plugin;
    private final CameraPlayers cameraPlayers;

    public SessionListener(CameraPlugin plugin) {
        this.plugin = plugin;
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (cameraPlayers.contains(event.getPlayer().getUniqueId())) {
            plugin.exitCameraMode(event.getPlayer(), EndReason.QUIT);
        }
        plugin.getMovementGuard().forget(event.getPlayer().getUniqueId());
        plugin.getNoCollisionTeam().removePlayerFromNoCollisionTeam(event.getPlayer());
        plugin.getStartChecks().forget(event.getPlayer().getUniqueId());
        plugin.getPreferences().forget(event.getPlayer().getUniqueId());
        plugin.getStartDelay().callOff(event.getPlayer(), null);
        // After the end above, which counted his last flight.
        plugin.getBattery().onQuit(event.getPlayer());
        plugin.getTimeLimit().onQuit(event.getPlayer());
        plugin.getPlayerStore().unload(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getBattery().onJoin(event.getPlayer());
        plugin.getTimeLimit().onJoin(event.getPlayer());
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
        // The player is meant to die, but camera mode has to end properly
        // first. The XP is handled by the death itself.
        Player player = event.getEntity();
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        // The server has collected the drops from the inventory of the camera
        // player, empty but for the camera head. The player's own things from
        // CameraData are to drop instead - getContents() holds every slot,
        // armour and off hand included, so a separate loop over the armour
        // would drop it twice. Only what the camera carried is taken out of
        // the drops: whatever other plugins put in there - a head of the
        // player, say - stays. With keepInventory nothing drops at all:
        // exitCameraMode gives the inventory back, and the server leaves it
        // with the player.
        if (!event.getKeepInventory()) {
            List<ItemStack> drops = event.getDrops();
            for (ItemStack carried : player.getInventory().getContents()) {
                if (carried != null && carried.getType() != Material.AIR) {
                    drops.remove(carried);
                }
            }
            for (ItemStack item : data.getOriginalInventoryContents()) {
                if (item != null && item.getType() != Material.AIR && !vanishes(item)) {
                    drops.add(item);
                }
            }
        }
        Location bodyAt = data.getBody().getLocation();
        plugin.exitCameraMode(player, EndReason.DEATH);
        // Bukkit teleports nobody without health, so the end of camera mode
        // may have left him where the camera was - and the server would drop
        // everything there, in the air, over the void or in somebody else's
        // base. It goes where he really stood: at his body.
        if (!atBody(player.getLocation(), bodyAt)) {
            dropAtBody(event, bodyAt);
        }
    }

    /**
     * Whether a thing is gone with its owner's death instead of dropping: the
     * curse of vanishing. The server leaves those out of the drops it
     * collects itself, so the things put in their place leave them out too.
     * An enchanted book that only carries the curse is no such thing, it
     * drops as it would.
     */
    private static boolean vanishes(ItemStack item) {
        return item.containsEnchantment(Enchantment.VANISHING_CURSE);
    }

    /** Whether the player stands where his body stood, as the end of camera mode puts him. */
    private static boolean atBody(Location player, Location body) {
        return body.getWorld() == null || body.getWorld().equals(player.getWorld())
                && player.distanceSquared(body) <= AT_BODY * AT_BODY;
    }

    /** Drops what the server would drop for the player at his body instead. */
    private static void dropAtBody(PlayerDeathEvent event, Location body) {
        World world = body.getWorld();
        for (ItemStack item : event.getDrops()) {
            if (item != null && item.getType() != Material.AIR) {
                world.dropItemNaturally(body, item);
            }
        }
        event.getDrops().clear();
        int experience = event.getDroppedExp();
        if (experience > 0) {
            event.setDroppedExp(0);
            world.spawn(body, ExperienceOrb.class, orb -> orb.setExperience(experience));
        }
    }
}
