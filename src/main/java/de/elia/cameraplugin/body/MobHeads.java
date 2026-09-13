package de.elia.cameraplugin.body;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * Tells whether a body wears the face of the mob that is looking at it.
 *
 * <p>In vanilla a worn mob head halves the distance at which that kind of mob
 * notices whoever wears it: a zombie head hides from zombies, a creeper head
 * from creepers. Which head belongs to which mob is not kept as a list here, it
 * is read off the name - {@code ZOMBIE_HEAD} belongs to the {@code ZOMBIE} -
 * so a head a later version adds counts without anybody adding it here.</p>
 */
public final class MobHeads {

    private MobHeads() {
    }

    /**
     * Whether the head worn in {@code equipment} is the face of {@code type}.
     *
     * @return {@code false} when nothing is worn on the head, or when the head
     *         belongs to another kind of mob
     */
    public static boolean matches(EntityEquipment equipment, EntityType type) {
        if (equipment == null) {
            return false;
        }
        ItemStack helmet = equipment.getHelmet();
        if (helmet == null) {
            return false;
        }
        String shownMob = shownMob(helmet.getType());
        if (shownMob == null) {
            return false;
        }
        if (shownMob.equals(type.name())) {
            return true;
        }
        // A piglin brute falls for a piglin head just like a piglin does, the
        // way vanilla has it.
        return shownMob.equals("PIGLIN") && type == EntityType.PIGLIN_BRUTE;
    }

    /**
     * The mob a head shows, taken from its name: {@code ZOMBIE_HEAD} and
     * {@code SKELETON_SKULL} name theirs, {@code PLAYER_HEAD} names no mob at
     * all.
     *
     * @return the name of the mob, or {@code null} for an item that is no head
     */
    private static String shownMob(Material material) {
        String name = material.name();
        if (name.endsWith("_HEAD")) {
            return name.substring(0, name.length() - "_HEAD".length());
        }
        if (name.endsWith("_SKULL")) {
            return name.substring(0, name.length() - "_SKULL".length());
        }
        return null;
    }
}
