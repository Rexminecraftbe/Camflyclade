package de.elia.cameraplugin.body;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

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
 * ({@code setModel} became {@code setAssetId}), so it is looked up at runtime.
 * {@link #describeAssetSetter()} tells the log which one was used, or which
 * setters this server offers when none of them fitted.</p>
 */
public final class EquipmentVisibility {

    private static volatile String usedSetter;

    private EquipmentVisibility() {
    }

    /**
     * Hides the given armour piece while it is worn in {@code slot}.
     *
     * @param missingAsset key of an equipment asset that does not exist
     * @return {@code true} when the piece should not be rendered any more
     */
    public static boolean hide(ItemStack item, EquipmentSlot slot, NamespacedKey missingAsset) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        EquippableComponent equippable = meta.getEquippable();
        // The component replaces the one of the item type as a whole, so the slot
        // has to be set again - without it the piece could not be worn at all.
        equippable.setSlot(slot);
        // The head slot draws the item model itself when there is no asset, so it
        // always gets the key that cannot be resolved. For the other slots no
        // asset at all is the cleaner way; should the API refuse to take null,
        // the unresolvable key does the same job there.
        boolean hidden = slot == EquipmentSlot.HEAD
                ? setAsset(equippable, missingAsset)
                : setAsset(equippable, null) || setAsset(equippable, missingAsset);
        if (!hidden) {
            return false;
        }
        meta.setEquippable(equippable);
        item.setItemMeta(meta);
        return true;
    }

    /**
     * The setter that was used for the equipment asset, or - when none of them
     * fitted - the single argument setters this server's API offers.
     */
    public static String describeAssetSetter() {
        String used = usedSetter;
        if (used != null) {
            return used;
        }
        StringBuilder available = new StringBuilder();
        for (Method method : EquippableComponent.class.getMethods()) {
            if (method.getParameterCount() != 1 || !method.getName().startsWith("set")) {
                continue;
            }
            if (available.length() > 0) {
                available.append(", ");
            }
            available.append(method.getName()).append('(')
                    .append(method.getParameterTypes()[0].getSimpleName()).append(')');
        }
        return "keiner - vorhanden sind: " + available;
    }

    /**
     * Hands the asset to the setter this server's API declares. Every fitting
     * setter is tried, a deprecated one that refuses the value does not stop the
     * next one from being used.
     */
    private static boolean setAsset(EquippableComponent equippable, NamespacedKey asset) {
        for (Method method : EquippableComponent.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            if (!isAssetSetter(method.getName())) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (asset == null ? parameter.isPrimitive() : !parameter.isInstance(asset)) {
                continue;
            }
            try {
                method.invoke(equippable, asset);
                usedSetter = method.getName() + '(' + parameter.getSimpleName() + ')';
                return true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Try the next setter, this one does not take the value.
            }
        }
        return false;
    }

    /**
     * The asset was called {@code model} up to 1.21.4 and {@code asset_id}
     * afterwards, so setters carrying either word are accepted. The other
     * setters of the component (slot, camera overlay, sounds) carry neither.
     */
    private static boolean isAssetSetter(String methodName) {
        String name = methodName.toLowerCase(Locale.ROOT);
        return name.startsWith("set") && (name.contains("model") || name.contains("asset"));
    }
}
