package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.session.CameraHead;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * What a button of {@code /cam menu} shows, as {@code menu.buttons.<button>.item}
 * names it: an item of the game by its name, as {@code /give} takes it, or
 * one of two the game has no name of its own for - {@code camera_head}, the
 * head the camera wears, and {@code night_vision_potion}.
 *
 * @param name the name as it was read, lower case and without
 *             {@code minecraft:}
 */
public record MenuIcon(String name) {

    /** The head the camera wears, see {@link CameraHead}. */
    public static final String CAMERA_HEAD = "camera_head";

    /** A potion of night vision. */
    public static final String NIGHT_VISION_POTION = "night_vision_potion";

    /**
     * The icon an entry of the config file names, or {@code null} when it
     * names nothing that can be shown in a window - no item, or air.
     */
    public static MenuIcon parse(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (name.equals(CAMERA_HEAD) || name.equals(NIGHT_VISION_POTION)) {
            return new MenuIcon(name);
        }
        Material material = name.isEmpty() ? null : Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        return new MenuIcon(name);
    }

    /** A new item of this icon, still without the name and lines of the menu. */
    public ItemStack create(Logger logger) {
        if (name.equals(CAMERA_HEAD)) {
            return CameraHead.create(logger);
        }
        if (name.equals(NIGHT_VISION_POTION)) {
            ItemStack potion = new ItemStack(Material.POTION);
            if (potion.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(PotionType.NIGHT_VISION);
                potion.setItemMeta(meta);
            }
            return potion;
        }
        Material material = Material.matchMaterial(name);
        return new ItemStack(material == null ? Material.BARRIER : material);
    }
}
