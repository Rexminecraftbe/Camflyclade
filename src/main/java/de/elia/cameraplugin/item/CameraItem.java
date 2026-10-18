package de.elia.cameraplugin.item;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraHead;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The camera item, the section {@code camera-item}: a right click with it
 * starts camera mode, through the same checks as {@code /cam}.
 *
 * <p>It is told apart by a mark of the plugin on it, not by what it looks
 * like: a renamed clock is no camera, and a camera stays one after
 * {@code material} has been changed. Its name and description come from the
 * language file when it is made.</p>
 *
 * <p>It only starts camera mode. While camera mode runs the hands are empty -
 * the item is put away with everything else and comes back at the end - so
 * camera mode ends the way it always does, with {@code /cam} or a click on
 * the own body.</p>
 */
public final class CameraItem implements Listener {

    /** Who may get a camera with {@code /cam item}. Everybody has it by default. */
    public static final String PERMISSION = "camplugin.item";

    /** Who may hand somebody else a camera with {@code /cam item <player>}. Operators have it by default. */
    public static final String PERMISSION_OTHERS = "camplugin.item.others";

    /** Ticks after a join before the camera is handed over, see {@link #onJoin}. */
    private static final long JOIN_DELAY = 2L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** The mark that makes an item the camera. */
    private final NamespacedKey key;

    public CameraItem(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.key = new NamespacedKey(plugin, "camera_item");
    }

    /** A new camera, as {@code camera-item.material} and the language file have it now. */
    public ItemStack create() {
        Material material = settings.getCameraItemMaterial();
        ItemStack item = material == null ? CameraHead.create(plugin.getLogger()) : new ItemStack(material);
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
            // Also for the camera head, which comes with a line of its own.
            meta.setLore(lines.isEmpty() ? null : lines);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Whether an item is a camera. */
    public boolean isCameraItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Whether a player carries a camera anywhere in his inventory. */
    public boolean carries(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCameraItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code /cam item [player]}: hands a camera to the sender himself, or to
     * the player named.
     *
     * @param targetName the player named, or {@code null} for the sender
     */
    public void give(CommandSender sender, String targetName) {
        if (!settings.isCameraItemEnabled()) {
            messages.sendConfiguredMessage(sender, "camera-item-off");
            return;
        }
        Player target;
        if (targetName == null) {
            if (!(sender instanceof Player player)) {
                messages.sendConfiguredMessage(sender, "no-player");
                return;
            }
            if (!player.hasPermission(PERMISSION) || !player.hasPermission("camplugin.use")) {
                messages.sendConfiguredMessage(player, "no-permission");
                return;
            }
            target = player;
        } else {
            if (sender instanceof Player player && !player.hasPermission(PERMISSION_OTHERS)) {
                messages.sendConfiguredMessage(player, "no-permission");
                return;
            }
            target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                messages.sendConfiguredMessage(sender, "player-not-online", "{player}", targetName);
                return;
            }
        }
        boolean self = target.equals(sender);
        // His pockets are emptied every tick while he flies, the camera would
        // be gone before he saw it.
        if (plugin.isInCameraMode(target)) {
            messages.sendConfiguredMessage(sender, "camera-item-in-cam", "{player}", target.getName());
            return;
        }
        if (carries(target)) {
            messages.sendConfiguredMessage(sender, self ? "camera-item-already" : "camera-item-already-other",
                    "{player}", target.getName());
            return;
        }
        hand(target);
        messages.sendConfiguredMessage(target, "camera-item-received");
        if (!self) {
            messages.sendConfiguredMessage(sender, "camera-item-given", "{player}", target.getName());
        }
    }

    /** Puts a camera into his pockets, or at his feet when they are full. */
    private void hand(Player player) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(create());
        for (ItemStack item : left.values()) {
            player.getWorld().dropItem(player.getLocation(), item);
        }
    }

    /**
     * A right click with the camera starts camera mode. The click itself does
     * nothing else: the head is not placed, the block clicked on is not
     * opened.
     *
     * <p>Not left to {@code ignoreCancelled}: a click into the air comes as a
     * cancelled event, there being no block to use. What counts is whether
     * the item may be used - another plugin that forbids that here, a
     * protected region say, keeps camera mode from starting as well.</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCameraUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isCameraItem(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        // In camera mode the hands are empty, and every click is turned away
        // by the interaction lock anyway.
        if (!settings.isCameraItemEnabled() || plugin.isInCameraMode(player)
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (!player.hasPermission("camplugin.use")) {
            messages.sendConfiguredMessage(player, "no-permission");
            return;
        }
        plugin.getStarter().start(player);
    }

    /**
     * {@code give-on-join}: a player who joins without a camera gets one.
     * Two ticks later, after the crash protection has given a player back the
     * inventory a crash took from him - one tick after the join - so that the
     * camera is not handed over and then replaced.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.isCameraItemEnabled() || !settings.givesCameraItemOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.isInCameraMode(player) && player.hasPermission("camplugin.use")
                    && player.hasPermission(PERMISSION) && !carries(player)
                    && player.getInventory().firstEmpty() >= 0) {
                player.getInventory().addItem(create());
            }
        }, JOIN_DELAY);
    }
}
