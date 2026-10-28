package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.config.CamSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a guest of a guide out of camera mode sees of the guide on his own
 * screen, {@code follow.mirror-...}: the guide's hearts as his own, the item in
 * the guide's hand in his, and the one in the guide's off hand in his. His
 * hunger stays his.
 *
 * <p>All of it goes to the client of the guest only, through
 * {@code sendHealthUpdate} and {@code sendEquipmentChange}: on the server his
 * health stays his, and his hands stay as empty as camera mode keeps them.
 * There is nothing he could take, drop or use, the rest of the guide's
 * inventory he never sees, and a click into his own takes the pictures away
 * until they are sent again. They are sent again every
 * {@value #REFRESH} ticks anyway, and whenever the guide's hearts or items
 * change, so whatever the server sends him of his own in between - a hit on
 * his body - does not stay either.</p>
 *
 * <p>Once he is off the tour, or his guide starts camera mode, which empties
 * the guide's hands, the client is sent his own health and his own empty hands
 * again. Should he leave the server instead, there is nothing to put back: the
 * server sends him what is his when he joins.</p>
 *
 * <p>Hearts of 0 are never sent: the client would show the screen of death.
 * A guide who dies ends the tour first, see {@link CamTour}.</p>
 */
final class GuideMirror {

    /** How often everything is sent again, in ticks, changed or not. */
    private static final int REFRESH = 10;

    private final CamSettings settings;
    /** What each guest's client was sent, by the id of the guest. */
    private final Map<UUID, Shown> shown = new HashMap<>();

    /** What one guest's client was sent last. */
    private static final class Shown {
        /** The hearts, -1 while none were sent. */
        double health = -1.0;
        /** The items, {@code null} while none were sent. */
        ItemStack mainHand;
        ItemStack offHand;
        /** The slot of the hotbar his client held the item of the hand in. */
        int slot = -1;
        /** Ticks since everything was last sent. */
        int sinceRefresh;
    }

    GuideMirror(CamSettings settings) {
        this.settings = settings;
    }

    /**
     * Every tick, for each guest of a guide out of camera mode: whatever
     * changed of the guide since, or all of it now and then.
     */
    void update(Player guide, Player guest) {
        Shown state = shown.computeIfAbsent(guest.getUniqueId(), id -> new Shown());
        boolean refresh = ++state.sinceRefresh >= REFRESH;
        if (refresh) {
            state.sinceRefresh = 0;
        }
        if (settings.isTourMirrorHealth()) {
            double health = guide.getHealth();
            if (health > 0.0 && (refresh || health != state.health)) {
                guest.sendHealthUpdate(health, guest.getFoodLevel(), guest.getSaturation());
                state.health = health;
            }
        } else if (state.health >= 0.0) {
            guest.sendHealthUpdate();
            state.health = -1.0;
        }
        int slot = guest.getInventory().getHeldItemSlot();
        if (state.slot >= 0 && slot != state.slot) {
            // The picture stayed in the slot his client held before.
            guest.updateInventory();
            refresh = true;
        }
        state.slot = slot;
        state.mainHand = mirror(guest, EquipmentSlot.HAND, settings.isTourMirrorMainHand(),
                guide.getInventory().getItemInMainHand(), state.mainHand, refresh);
        state.offHand = mirror(guest, EquipmentSlot.OFF_HAND, settings.isTourMirrorOffHand(),
                guide.getInventory().getItemInOffHand(), state.offHand, refresh);
    }

    /**
     * Sends the guest the guide's item for one hand, if it changed or it is
     * time; a hand no longer mirrored is sent empty, as his is.
     *
     * @return what his client holds there now, {@code null} for nothing sent
     */
    private static ItemStack mirror(Player guest, EquipmentSlot hand, boolean on, ItemStack item, ItemStack before,
                                    boolean refresh) {
        if (!on) {
            if (before != null) {
                guest.sendEquipmentChange(guest, hand, new ItemStack(Material.AIR));
            }
            return null;
        }
        ItemStack now = item == null ? new ItemStack(Material.AIR) : item;
        if (refresh || before == null || !now.equals(before)) {
            guest.sendEquipmentChange(guest, hand, now);
        }
        return now.clone();
    }

    /** Puts back on his client what is his: his own health and his own hands. */
    void clear(Player guest) {
        Shown state = shown.remove(guest.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.health >= 0.0) {
            guest.sendHealthUpdate();
        }
        if (state.mainHand != null || state.offHand != null) {
            guest.updateInventory();
        }
    }

    /** A guest who left the server: his client gets what is his when he joins again. */
    void forget(UUID guest) {
        shown.remove(guest);
    }

    /** Whether the guide's hearts or items are shown to anybody at all - anything switched on. */
    boolean isOn() {
        return settings.isTourMirrorHealth() || settings.isTourMirrorMainHand() || settings.isTourMirrorOffHand();
    }
}
