package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * The guide's items in the hotbar of a guest on Bedrock, see
 * {@link GuideMirror}: in the slot he holds and in his off hand, on his
 * client only.
 *
 * <p>A client on Java puts an item it is sent for its own player's hand -
 * {@code sendEquipmentChange} - into its hotbar as well. Geyser does not: on
 * Bedrock the hotbar shows what the player's inventory holds, and the item
 * only comes as the one in his hand. So a guest on Bedrock is sent the slot
 * of his inventory itself as well, the packet the server sends for any
 * change of a slot, through the internals of the server, see
 * {@link PacketSender}. On the server his inventory stays as empty as camera
 * mode keeps it, and the next time the server sends it, his own is back.</p>
 *
 * <p>Internals are no API: when a name is gone in an update of the game, a
 * guest on Bedrock is shown the guide's items in his hand only - the start
 * says so once in the console - and nothing else changes.</p>
 */
final class GuideSlots {

    /** The packet with one slot of a window: the window, its state, the slot, the item. */
    private static final String SLOT_PACKET = "net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket";
    /** An item of the server. */
    private static final String ITEM = "net.minecraft.world.item.ItemStack";
    /** What turns a Bukkit item into one of the server. */
    private static final String CRAFT_ITEM = "org.bukkit.craftbukkit.inventory.CraftItemStack";

    /** The window of a player's own inventory. */
    private static final int OWN_WINDOW = 0;
    /** Where the hotbar starts in that window, and its off hand. */
    private static final int HOTBAR = 36;
    private static final int OFF_HAND = 45;

    private final PacketSender sender;
    /** Makes the packet. */
    private Constructor<?> slotPacket;
    /** Turns a Bukkit item into one of the server. */
    private Method asServerItem;
    /** The window of the player's own inventory, on the player of the server. */
    private Field ownWindow;
    /** Where that window stands, which the packet carries. */
    private Method stateId;
    /** Whether all of it was found. */
    private boolean available;

    GuideSlots(PacketSender sender) {
        this.sender = sender;
    }

    /**
     * Looks the internals up, at the start and after a reload. Whatever is
     * missing switches it off; on Paper, where it should be there, the
     * console hears about it once.
     *
     * @param log where to say so, or {@code null} while it is not needed anyway
     */
    void load(ConsoleLog log) {
        available = false;
        try {
            sender.load();
            Class<?> item = PacketSender.internal(ITEM);
            slotPacket = PacketSender.internal(SLOT_PACKET).getConstructor(int.class, int.class, int.class, item);
            asServerItem = PacketSender.internal(CRAFT_ITEM).getMethod("asNMSCopy", ItemStack.class);
            ownWindow = PacketSender.internal("net.minecraft.world.entity.player.Player").getField("inventoryMenu");
            stateId = ownWindow.getType().getMethod("getStateId");
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (log != null && PacketSender.isPaper()) {
                log.log(Level.WARNING, "Followers of /cam follow on Bedrock see their leader's items in their hand"
                        + " only, not in their hotbar, on this version of the server: " + ex);
            }
        }
    }

    /**
     * Shows the guest the item in the slot of his hotbar he holds, or in his
     * off hand, on his client only.
     *
     * @return whether the packet went out
     */
    boolean show(Player guest, boolean offHand, ItemStack item) {
        if (!available) {
            return false;
        }
        try {
            int slot = offHand ? OFF_HAND : HOTBAR + guest.getInventory().getHeldItemSlot();
            Object window = ownWindow.get(sender.handleOf(guest));
            int state = (Integer) stateId.invoke(window);
            sender.send(guest, slotPacket.newInstance(OWN_WINDOW, state, slot, asServerItem.invoke(null, item)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }
}
