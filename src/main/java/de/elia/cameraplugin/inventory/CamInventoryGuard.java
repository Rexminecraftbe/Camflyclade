package de.elia.cameraplugin.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Keeps the hands of the camera player empty.
 *
 * <p>Camera mode takes his inventory off him at the start and gives it back at
 * the end, and in between everything that could put something into it is
 * turned away: the click in a window, the window itself, picking an item up
 * off the ground, dropping one. The click covers the creative menu as well -
 * {@code InventoryCreativeEvent} carries no handler list of its own and is
 * therefore handed to the listener on {@code InventoryClickEvent}.</p>
 *
 * <p>One way past all of them is left: the middle click in the creative mode.
 * The pick does not go through any window - the server puts the block into the
 * hotbar slot itself - and the Spigot API this plugin builds against has no
 * event for it at all, so there is nothing to cancel. Since
 * {@code camera-mode.gamemode} can fly the camera in the creative mode, that
 * gap is open, and a camera player could hold a block he picked out of the
 * world.</p>
 *
 * <p>So this is held by looking instead of by cancelling, which also makes it
 * independent of the way something got in: once a tick the storage slots and
 * the off hand are emptied again. The armour is deliberately left alone, since
 * the camera head of {@code camera-head.enabled} is worn in the helmet slot
 * and a sweep over the armour would take it off him every tick.</p>
 *
 * <p>The off hand is swept with the rest because swapping hands would
 * otherwise carry a picked block out of the slots that are looked at.</p>
 *
 * <p>Nothing is lost by this: what the player brought with him is written down
 * in his {@code CameraData} at the start and put back when he leaves, and a
 * block picked in the creative mode was made out of nothing anyway.</p>
 */
public class CamInventoryGuard {

    /** The slots a player carries his things in: the hotbar and the three rows. */
    private static final int STORAGE_SLOTS = 36;

    /**
     * Ticks between two sweeps. The lowest there is: the block is in his hand
     * until the next one, and nobody is meant to see it there.
     */
    private static final long INTERVAL = 1L;

    private final JavaPlugin plugin;
    /**
     * Whether that player is still in camera mode.
     *
     * <p>Asked before every sweep, because this one is the odd guard out: what
     * it does is take things away. A sweep left running after camera mode has
     * ended would empty the player's own pockets, tick after tick, so it is
     * not enough for it to be stopped from the outside - it has to be able to
     * see for itself that its player is gone.</p>
     */
    private final Predicate<Player> inCameraMode;
    /** The sweep of every player currently in camera mode, by player id. */
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public CamInventoryGuard(JavaPlugin plugin, Predicate<Player> inCameraMode) {
        this.plugin = plugin;
        this.inCameraMode = inCameraMode;
    }

    /** Starts sweeping for this camera player. */
    public void startFor(Player player) {
        if (tasks.containsKey(player.getUniqueId())) {
            return;
        }
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !inCameraMode.test(player)) {
                    stopFor(player);
                    return;
                }
                sweep(player);
            }
        };
        task.runTaskTimer(plugin, INTERVAL, INTERVAL);
        tasks.put(player.getUniqueId(), task);
    }

    /**
     * Stops sweeping for him, when camera mode ends. Called before his own
     * inventory is handed back, so that nothing sweeps that away.
     */
    public void stopFor(Player player) {
        BukkitRunnable task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    /**
     * Empties what has turned up in his hands since the last look. The
     * inventory is only sent to him again when something really was taken -
     * in the ordinary case there is nothing to take and nothing to send.
     */
    private void sweep(Player player) {
        PlayerInventory inventory = player.getInventory();
        boolean taken = false;
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            if (holdsSomething(inventory.getItem(slot))) {
                inventory.clear(slot);
                taken = true;
            }
        }
        if (holdsSomething(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
            taken = true;
        }
        if (taken) {
            player.updateInventory();
        }
    }

    /**
     * Whether that slot has anything in it. An empty slot reads as
     * {@code null} or as air, depending on the server, so both are asked.
     */
    private boolean holdsSomething(ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }
}
