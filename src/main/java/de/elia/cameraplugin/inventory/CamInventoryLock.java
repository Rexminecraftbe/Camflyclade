package de.elia.cameraplugin.inventory;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Turns away everything that would put something into the pockets of a camera
 * player or take something out of them: picking up, dropping, clicking in a
 * window and the window itself. What gets past these is swept away by
 * {@link CamInventoryGuard}.
 */
public final class CamInventoryLock implements Listener {

    private final CameraPlayers cameraPlayers;

    public CamInventoryLock(CameraPlayers cameraPlayers) {
        this.cameraPlayers = cameraPlayers;
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player &&
                cameraPlayers.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (cameraPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player &&
                cameraPlayers.contains(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * No window opens in front of a camera player.
     *
     * <p>The click into one is turned away by the handler above, and the way
     * into a chest or a barrel by the interaction lock. This shuts the windows
     * that hang on an entity instead of on a block - the trade of a villager,
     * the inventory of a chest minecart or of a horse - and it shuts them before
     * they are seen, rather than only keeping his hands out of them.</p>
     */
    @EventHandler
    public void onCameraInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && cameraPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
