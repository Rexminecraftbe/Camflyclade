package de.elia.cameraplugin.mirrordamage;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a hit on the body leaves behind with whoever struck it.
 *
 * <p>Outside camera mode the attacker's own side of a hit comes only with a
 * hit that goes through: the weapon wears down, the swing makes him hungry, a
 * mace striking from above catches his fall, and a bee loses its sting. The
 * hit on the body never goes through - {@link DamageMirror} cancels it and
 * passes on the damage alone. So the sword stayed as it was, the bee kept its
 * sting and came back for the player, and whoever struck the body with a mace
 * from above fell on, out of the strike, with the whole fall ahead of him.</p>
 *
 * <p>Handed out right where the hit on the body lands, by the rules of the
 * game: it is the attacker's side, and the mace has to catch the fall before
 * he hits the ground.</p>
 */
final class AttackerAftermath {

    /** How far an attacker has to have fallen for a mace to strike from above, in blocks. */
    private static final float MACE_SMASH_FALL = 1.5f;
    /** The little bounce a mace strike from above leaves the attacker with. */
    private static final double MACE_BOUNCE = 0.01;
    /** The hunger a swing costs, as the game counts it. */
    private static final float SWING_EXHAUSTION = 0.1f;

    private AttackerAftermath() {
    }

    /** Hands the attacker of this hit on the body what the hit would have left him with. */
    static void handOut(EntityDamageEvent hit) {
        if (!(hit instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Bee bee) {
            // It has stung, as it does whenever its sting goes in: it attacks
            // nobody any more and dies soon. Calmed down as well - otherwise
            // camera mode would hand its anger back to the player.
            bee.setHasStung(true);
            bee.setAnger(0);
            bee.setTarget(null);
            return;
        }
        if (damager instanceof Player attacker && hit.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (weapon.getType() == Material.MACE && attacker.getFallDistance() > MACE_SMASH_FALL
                    && !attacker.isGliding()) {
                Vector motion = attacker.getVelocity();
                attacker.setVelocity(new Vector(motion.getX(), MACE_BOUNCE, motion.getZ()));
                attacker.setFallDistance(0.0f);
            }
            if (attacker.getGameMode() == GameMode.SURVIVAL || attacker.getGameMode() == GameMode.ADVENTURE) {
                attacker.setExhaustion(attacker.getExhaustion() + SWING_EXHAUSTION);
                WeaponWear.wear(attacker, weapon);
            }
        }
    }

    /**
     * Wears down the weapon a hit went through with, by as many points as its
     * {@code weapon} component says: one for a sword, a mace or a spear, two
     * for an axe, a pickaxe, a shovel or a hoe, none for anything without
     * one.
     *
     * <p>The component is asked by name: Spigot hands it out on the item
     * meta, Paper as a data component of the item, and neither API has the
     * other's way.</p>
     */
    static final class WeaponWear {

        private static Method spigotHas;
        private static Method spigotGet;
        private static Method spigotPerAttack;
        private static Method paperGet;
        private static Object paperType;
        private static Method paperPerAttack;
        private static boolean looked;

        private WeaponWear() {
        }

        static void wear(Player attacker, ItemStack weapon) {
            if (weapon == null || weapon.getType().isAir()) {
                return;
            }
            int points = pointsPerAttack(weapon);
            ItemMeta meta = weapon.getItemMeta();
            if (points <= 0 || !(meta instanceof Damageable durability) || meta.isUnbreakable()) {
                return;
            }
            int max = durability.hasMaxDamage() ? durability.getMaxDamage() : weapon.getType().getMaxDurability();
            if (max <= 0) {
                return;
            }
            int loss = afterUnbreaking(points, weapon.getEnchantmentLevel(Enchantment.UNBREAKING));
            if (loss <= 0) {
                return;
            }
            PlayerItemDamageEvent event = new PlayerItemDamageEvent(attacker, weapon, loss);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || event.getDamage() <= 0) {
                return;
            }
            int used = durability.getDamage() + event.getDamage();
            if (used >= max) {
                attacker.getInventory().setItemInMainHand(null);
                Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(attacker, weapon));
                attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS,
                        0.8f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f);
                return;
            }
            durability.setDamage(used);
            weapon.setItemMeta(meta);
            attacker.getInventory().setItemInMainHand(weapon);
        }

        /**
         * Rolls the Unbreaking enchantment over the points the way it goes for
         * everything that is no armour: each point is spared with the chance
         * {@code level / (level + 1)}.
         */
        private static int afterUnbreaking(int points, int level) {
            if (level <= 0) {
                return points;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int counted = 0;
            for (int i = 0; i < points; i++) {
                if (random.nextInt(level + 1) == 0) {
                    counted++;
                }
            }
            return counted;
        }

        /** How many durability points a hit costs this item, 0 when it is no weapon. */
        private static int pointsPerAttack(ItemStack weapon) {
            lookUp();
            try {
                if (paperGet != null) {
                    Object component = paperGet.invoke(weapon, paperType);
                    return component == null ? 0 : ((Number) paperPerAttack.invoke(component)).intValue();
                }
                if (spigotHas != null) {
                    ItemMeta meta = weapon.getItemMeta();
                    if (meta == null || !(Boolean) spigotHas.invoke(meta)) {
                        return 0;
                    }
                    return ((Number) spigotPerAttack.invoke(spigotGet.invoke(meta))).intValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return 0;
            }
            return 0;
        }

        private static synchronized void lookUp() {
            if (looked) {
                return;
            }
            looked = true;
            try {
                Class<?> types = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
                Class<?> valued = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
                paperType = types.getField("WEAPON").get(null);
                paperGet = ItemStack.class.getMethod("getData", valued);
                paperPerAttack = Class.forName("io.papermc.paper.datacomponent.item.Weapon")
                        .getMethod("itemDamagePerAttack");
                return;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                paperGet = null;
            }
            try {
                spigotHas = ItemMeta.class.getMethod("hasWeapon");
                spigotGet = ItemMeta.class.getMethod("getWeapon");
                spigotPerAttack = Class.forName("org.bukkit.inventory.meta.components.WeaponComponent")
                        .getMethod("getItemDamagePerAttack");
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                spigotHas = null;
            }
        }
    }
}
