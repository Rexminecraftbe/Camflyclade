package de.elia.cameraplugin.body;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Takes the rendering away from a piece of armour without taking away its
 * protection.
 *
 * <p>Since Minecraft 1.21.2 the {@code minecraft:equippable} component decides
 * how a worn item is drawn. Without an equipment asset nothing is drawn at all -
 * except in the head slot, which falls back to the item model itself, so the
 * helmet gets an asset the client cannot resolve instead. How much the armour
 * protects comes from the item's attributes and stays untouched.</p>
 *
 * <p>The name of the setter for that asset changed between versions
 * ({@code setModel} became {@code setAssetId}), so it is looked up at runtime.</p>
 */
public final class EquipmentVisibility {

    private EquipmentVisibility() {
    }

    /**
     * Hides the given armour piece while it is worn in {@code slot}.
     *
     * @param missingAsset key of an equipment asset that does not exist, used
     *                     for the head slot
     * @return {@code true} when the piece will not be rendered any more
     */
    public static boolean hide(ItemStack item, EquipmentSlot slot, NamespacedKey missingAsset) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        EquippableComponent equippable = meta.getEquippable();
        // The component is replaced as a whole, so the slot has to be set again.
        equippable.setSlot(slot);
        if (!setAsset(equippable, slot == EquipmentSlot.HEAD ? missingAsset : null)) {
            return false;
        }
        meta.setEquippable(equippable);
        item.setItemMeta(meta);
        return true;
    }

    /** Hands the asset to the setter that this server's API declares. */
    private static boolean setAsset(EquippableComponent equippable, NamespacedKey asset) {
        for (Method method : EquippableComponent.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            String name = method.getName();
            if (!name.equals("setModel") && !name.equals("setAssetId")) {
                continue;
            }
            if (asset != null && !method.getParameterTypes()[0].isInstance(asset)) {
                continue;
            }
            try {
                method.invoke(equippable, asset);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }
        return false;
    }
}
