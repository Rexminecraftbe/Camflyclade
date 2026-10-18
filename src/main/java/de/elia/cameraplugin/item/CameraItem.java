package de.elia.cameraplugin.item;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The camera item, the section {@code camera-item}: camera mode puts it into
 * the hand of the camera player when it starts, and a right click with it ends
 * camera mode again, the way {@code /cam} does.
 *
 * <p>It goes into the slot of the hotbar he holds. The mouse wheel keeps that
 * slot where it is while he flies, see
 * {@link de.elia.cameraplugin.speed.CamFlightSpeed}, so it stays in his hand.
 * The sweep of {@link de.elia.cameraplugin.inventory.CamInventoryGuard} leaves
 * it alone, and it is gone with everything else of camera mode when his own
 * inventory comes back.</p>
 *
 * <p>It is told apart by a mark of the plugin on it, not by what it looks
 * like: a barrier of his own is no camera item. One that is left outside
 * camera mode - after a crash with {@code crash-protection} switched off - is
 * taken away when he joins or clicks with it, a barrier being something he
 * could build with otherwise.</p>
 */
public final class CameraItem implements Listener {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The mark that makes an item the camera item. */
    private final NamespacedKey key;

    public CameraItem(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.key = new NamespacedKey(plugin, "camera_item");
    }

    /** A new camera item, as {@code camera-item.material} and the language file have it now. */
    private ItemStack create() {
        ItemStack item = new ItemStack(settings.getCameraItemMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = messages.getMessage("camera-item-name");
            if (!name.isEmpty()) {
                meta.setDisplayName(name);
            }
            String lore = messages.getMessage("camera-item-lore");
            List<String> lines = new ArrayList<>();
            if (!lore.isEmpty()) {
                lines.addAll(List.of(lore.split("\n")));
            }
            meta.setLore(lines.isEmpty() ? null : lines);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Whether an item is the camera item. */
    public boolean isCameraItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Puts the camera item into the hand of a player entering camera mode,
     * once his inventory has been put away and emptied.
     */
    public void handTo(Player player) {
        if (!settings.isCameraItemEnabled()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(inventory.getHeldItemSlot(), create());
        player.updateInventory();
    }

    /**
     * A right click with the camera item ends camera mode - into the air, at
     * a block, wherever. The click itself does nothing else: the barrier is
     * not placed. Outside camera mode the item is a leftover and goes.
     *
     * <p>Not left to {@code ignoreCancelled}: a click into the air comes as a
     * cancelled event, there being no block to use, and in camera mode the
     * interaction lock cancels every click before this handler sees it.</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCameraItemUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isCameraItem(event.getItem())) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (plugin.isInCameraMode(player)) {
            plugin.exitCameraMode(player, EndReason.COMMAND);
            messages.sendConfiguredMessage(player, "camera-off");
        } else {
            removeLeftovers(player);
        }
    }

    /** A player who joins with a camera item left over loses it. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isInCameraMode(event.getPlayer())) {
            removeLeftovers(event.getPlayer());
        }
    }

    /** Takes every camera item out of the inventory of a player outside camera mode. */
    private void removeLeftovers(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean found = false;
        for (int slot = 0; slot < contents.length; slot++) {
            if (isCameraItem(contents[slot])) {
                inventory.setItem(slot, null);
                found = true;
            }
        }
        if (found) {
            player.updateInventory();
        }
    }
}
