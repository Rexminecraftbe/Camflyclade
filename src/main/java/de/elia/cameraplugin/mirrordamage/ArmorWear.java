package de.elia.cameraplugin.mirrordamage;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.tag.DamageTypeTags;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wears down the armour of a player by hand.
 *
 * <p>The server only ever wears armour down together with the damage it deals,
 * both out of the same number. Camera mode keeps the two apart: how many hearts
 * the hit costs the player and how hard it wears his armour are set separately.
 * Wherever those two numbers differ, the wear is done here instead - by the
 * same rules the server goes by, only with the number the config asks for.</p>
 *
 * <p>Those rules are: a hit is worth {@code max(1, damage / 4)} durability
 * points, every worn piece loses that many, the Unbreaking enchantment may take
 * some of them back, and a piece that runs out breaks. Only real armour is worn
 * down - an elytra, a pumpkin or a head in the same slot is not - and neither
 * is an unbreakable piece or one that shrugs off this kind of damage, like
 * netherite in fire. In creative mode nothing wears out at all.</p>
 */
public final class ArmorWear {

    /** The helmet in {@link org.bukkit.inventory.PlayerInventory#getArmorContents()}. */
    private static final int HELMET = 3;
    /** Boots, leggings, chestplate and helmet, in the order of that same array. */
    private static final int[] EVERY_PIECE = {0, 1, 2, 3};
    private static final int[] HELMET_ONLY = {HELMET};

    private ArmorWear() {
    }

    /** The durability points a hit of this size is worth, as the server counts them. */
    public static int pointsFor(double damage) {
        return (int) Math.max(1.0, damage / 4.0);
    }

    /**
     * Wears the pieces down the way this very hit would have worn them.
     *
     * <p>Damage that goes straight through armour - falling, drowning, magic -
     * leaves it alone, exactly as it does outside camera mode: what never meets
     * the armour cannot wear it out.</p>
     *
     * @param armor the pieces to wear down, changed in place; a piece that
     *              breaks is taken out of the array
     * @return the durability points every piece was asked to give up, 0 when
     *         this hit does not wear armour at all
     */
    public static int wearMirrored(Player owner, ItemStack[] armor, double damage,
                                   DamageSource source, boolean respectUnbreaking) {
        if (armor == null || damage <= 0 || owner.getGameMode() == GameMode.CREATIVE) {
            return 0;
        }
        DamageType type = source == null ? null : source.getDamageType();
        double forArmor = damage;
        if (type != null && DamageTypeTags.DAMAGES_HELMET.isTagged(type)) {
            // A falling anvil first lands on the helmet alone, and only what is
            // left of it, a quarter less, reaches the rest of the armour.
            hurtPieces(owner, armor, HELMET_ONLY, pointsFor(damage), type, respectUnbreaking);
            forArmor = damage * 0.75;
        }
        if (type != null && DamageTypeTags.BYPASSES_ARMOR.isTagged(type)) {
            return 0;
        }
        int points = pointsFor(forArmor);
        hurtPieces(owner, armor, EVERY_PIECE, points, type, respectUnbreaking);
        return points;
    }

    /**
     * Takes the same number of durability points off every worn piece, whatever
     * the hit was worth and wherever it came from.
     *
     * @param armor the pieces to wear down, changed in place; a piece that
     *              breaks is taken out of the array
     * @return the durability points every piece was asked to give up
     */
    public static int wearFixed(Player owner, ItemStack[] armor, int points,
                                DamageSource source, boolean respectUnbreaking) {
        if (armor == null || points <= 0 || owner.getGameMode() == GameMode.CREATIVE) {
            return 0;
        }
        DamageType type = source == null ? null : source.getDamageType();
        hurtPieces(owner, armor, EVERY_PIECE, points, type, respectUnbreaking);
        return points;
    }

    private static void hurtPieces(Player owner, ItemStack[] armor, int[] slots, int points,
                                   DamageType type, boolean respectUnbreaking) {
        for (int slot : slots) {
            if (slot < armor.length) {
                hurtPiece(owner, armor, slot, points, type, respectUnbreaking);
            }
        }
    }

    private static void hurtPiece(Player owner, ItemStack[] armor, int slot, int points,
                                  DamageType type, boolean respectUnbreaking) {
        ItemStack piece = armor[slot];
        if (piece == null || piece.getType().isAir()) {
            return;
        }
        if (!isArmor(slot, piece.getType())) {
            return; // an elytra, a pumpkin, a head: worn, but not worn down by hits
        }
        ItemMeta meta = piece.getItemMeta();
        if (!(meta instanceof Damageable durability) || meta.isUnbreakable()) {
            return;
        }
        EquippableComponent equippable = meta.hasEquippable() ? meta.getEquippable() : null;
        if (equippable != null && !equippable.isDamageOnHurt()) {
            return; // a piece told outright that hits leave it alone
        }
        if (type != null && meta.hasDamageResistant()) {
            Collection<DamageType> resistances = meta.getDamageResistances();
            if (resistances != null && resistances.contains(type)) {
                return; // netherite in fire and the like
            }
        }
        int maxDamage = durability.hasMaxDamage() ? durability.getMaxDamage() : piece.getType().getMaxDurability();
        if (maxDamage <= 0) {
            return;
        }

        int loss = respectUnbreaking
                ? afterUnbreaking(points, piece.getEnchantmentLevel(Enchantment.UNBREAKING))
                : points;
        if (loss <= 0) {
            return;
        }
        PlayerItemDamageEvent event = new PlayerItemDamageEvent(owner, piece, loss);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || event.getDamage() <= 0) {
            return;
        }

        int used = durability.getDamage() + event.getDamage();
        if (used >= maxDamage) {
            armor[slot] = null;
            Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(owner, piece));
            owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS,
                    0.8f, 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f);
            return;
        }
        durability.setDamage(used);
        piece.setItemMeta(meta);
    }

    /** Whether the piece is the armour that belongs in this slot of the array. */
    private static boolean isArmor(int slot, Material type) {
        return switch (slot) {
            case 0 -> Tag.ITEMS_FOOT_ARMOR.isTagged(type);
            case 1 -> Tag.ITEMS_LEG_ARMOR.isTagged(type);
            case 2 -> Tag.ITEMS_CHEST_ARMOR.isTagged(type);
            case 3 -> Tag.ITEMS_HEAD_ARMOR.isTagged(type);
            default -> false;
        };
    }

    /**
     * Rolls the Unbreaking enchantment over the points.
     *
     * <p>Armour keeps far less of them than a tool does: six out of ten points
     * always count, and only the remaining four are the enchantment's to take,
     * with the chance {@code level / (level + 1)} each. That is where the
     * familiar number comes from - even Unbreaking III leaves armour losing
     * seven of every ten points.</p>
     */
    private static int afterUnbreaking(int points, int level) {
        if (level <= 0) {
            return points;
        }
        double chanceToCount = 0.6 + 0.4 / (level + 1.0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int counted = 0;
        for (int i = 0; i < points; i++) {
            if (random.nextDouble() < chanceToCount) {
                counted++;
            }
        }
        return counted;
    }
}
