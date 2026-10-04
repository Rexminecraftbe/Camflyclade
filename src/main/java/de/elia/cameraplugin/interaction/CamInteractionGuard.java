package de.elia.cameraplugin.interaction;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.UUID;

/**
 * The camera player has no hands out in the world: he clicks, breaks, places,
 * shoots, attacks and rides nothing. His own body is the one thing left to
 * him - clicking it ends camera mode.
 */
public final class CamInteractionGuard implements Listener {

    private final CameraPlugin plugin;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;

    public CamInteractionGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyInteract(PlayerInteractEntityEvent event) {
        handleBodyInteract(event);
    }

    /**
     * Armour stands and mannequins are clicked with the "interact at" variant of
     * the event, which has its own handler list. Without this the body could be
     * equipped by right clicking it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyInteractAt(PlayerInteractAtEntityEvent event) {
        handleBodyInteract(event);
    }

    /**
     * Taking a piece off an armour stand, or hanging one on it, is a third event
     * with a handler list of its own. It is only ever reached through the
     * interact-at above, which turns it away already - but a click that somehow
     * gets past that one must not end up moving armour around either.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        handleBodyInteract(event);
    }

    private void handleBodyInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        UUID ownerUUID = cameraPlayers.getBodyOrHitboxOwner(entity);
        if (ownerUUID == null) {
            // Anything that is not a camera body. The camera player has no hands
            // out here: blocks are shut in onPlayerInteract already, and this is
            // the same rule for the entities standing among them - the item
            // frame he would turn, the armour stand he would undress, the
            // villager he would trade with, the chest minecart, the boat with
            // the chest, the horse. His own body is the one thing left to him,
            // and that is the case below.
            if (cameraPlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!cameraPlayers.contains(ownerUUID)) {
            // Both kinds of event can be fired for the same click.
            return;
        }
        // Only his own body is his to click, and that ends camera mode.
        // Somebody else's is turned away without a word, the way a block is:
        // the click simply does nothing.
        if (player.getUniqueId().equals(ownerUUID)) {
            messages.sendConfiguredMessage(player, "camera-off");
            Player owner = Bukkit.getPlayer(ownerUUID);
            if (owner != null) {
                plugin.exitCameraMode(owner);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockReceiveGame(BlockReceiveGameEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && cameraPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player)) {
            return;
        }

        Player shooter = (Player) event.getEntity().getShooter();

        if (cameraPlayers.contains(shooter.getUniqueId())) {
            event.setCancelled(true); // Generell Projektile verhindern
            messages.sendConfiguredMessage(shooter, "no-projectiles");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!cameraPlayers.contains(player.getUniqueId())) {
            return;
        }

        Action action = event.getAction();

        // Blocks every interaction in camera mode
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK ||
                action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK ||
                action == Action.PHYSICAL) {
            event.setCancelled(true);
        }
    }

    /**
     * The camera player breaks no blocks, whatever mode he flies in.
     *
     * <p>The adventure mode used to answer for this on its own, and in the
     * creative mode the cancelled left click above still does: there the block
     * goes in that very click. In the survival mode it does not - the click
     * only starts the digging, and what finishes it is this event. Since
     * {@code camera-mode.gamemode} can put him into that mode, the ban is
     * written down here instead of being left to the mode he happens to be
     * in.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onCameraBlockBreak(BlockBreakEvent event) {
        if (cameraPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * And he places none either. His inventory is empty while he flies, so
     * there is usually nothing to place; this catches what another plugin
     * hands him anyway, for the same reason as the break above.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCameraBlockPlace(BlockPlaceEvent event) {
        if (cameraPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!cameraPlayers.contains(attacker.getUniqueId())) {
            return;
        }

        Entity target = event.getEntity();
        UUID ownerUUID = cameraPlayers.getBodyOrHitboxOwner(target);

        // Cancel attacks on anything except the player's own body or hitbox
        if (ownerUUID == null || !ownerUUID.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
            if (ownerUUID != null && !ownerUUID.equals(attacker.getUniqueId())) {
                messages.sendConfiguredMessage(attacker, "cant-attack-other-body");
            }
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player &&
                cameraPlayers.contains(event.getEntered().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nothing carries a camera player.
     *
     * <p>The event above already turns away what the API counts as a vehicle -
     * boats, minecarts, horses, and the happy ghast among them. This is the same
     * rule for everything else that can be sat on, and it covers the mount that
     * no right click of his goes through: being put onto something by a command
     * or by another plugin.</p>
     */
    @EventHandler
    public void onCameraMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player
                && cameraPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
