package de.elia.cameraplugin.mirrordamage;

import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Bee;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a mob hands out with its hit on top of the damage: the poison of a cave
 * spider and of a bee, the wither of a wither skeleton and of a wither skull,
 * the hunger of a husk, the levitation of a shulker bullet and the fire of a
 * burning zombie.
 *
 * <p>The game hands all of that out only when the hit goes through, right
 * after the damage, in the attack of the mob or the hit of its projectile.
 * The hit on the body never goes through - {@link DamageMirror} cancels it and
 * passes on the damage alone -, so camera mode used to keep the player safe
 * from poison and wither: the cave spider bit his body, and he took the bite
 * without the poison. It is handed out here instead, by the same rules, once
 * the hit passed on to him has got through, the way the fire of Fire Aspect
 * is.</p>
 *
 * <p>The tipped arrow, with the effects it carries, has its own way, see
 * {@link de.elia.cameraplugin.potion.CamPotionGuard#passOnArrowEffects}.</p>
 */
final class MobHitEffects {

    /** How long the wither of a wither skeleton lasts, in ticks, on every difficulty. */
    private static final int WITHER_SKELETON_TICKS = 200;
    /** How long the levitation of a shulker bullet lasts, in ticks, on every difficulty. */
    private static final int SHULKER_BULLET_TICKS = 200;
    /** The hunger of a husk, in ticks for each whole step of the local difficulty. */
    private static final int HUSK_HUNGER_TICKS = 140;
    /** The chance of a burning zombie to set its target on fire, for each step of the local difficulty. */
    private static final float ZOMBIE_FIRE_CHANCE = 0.3f;

    private MobHitEffects() {
    }

    /**
     * Hands the player what the hit of this mob - or of its projectile -
     * would have handed him outside camera mode.
     *
     * @param damager what hit the body: the mob itself, or the wither skull
     *                and the shulker bullet that flew into it
     * @param source  the damage of the hit - an explosion hands out nothing,
     *                not even that of a wither skull: its wither comes with
     *                the direct hit alone
     */
    static void handOut(Player victim, Entity damager, DamageSource source) {
        if (damager == null || source != null && DamageTypeTags.IS_EXPLOSION.isTagged(source.getDamageType())) {
            return;
        }
        Difficulty difficulty = victim.getWorld().getDifficulty();
        if (damager instanceof CaveSpider) {
            give(victim, PotionEffectType.POISON, seconds(difficulty, 7, 15), 0);
        } else if (damager instanceof Bee) {
            give(victim, PotionEffectType.POISON, seconds(difficulty, 10, 18), 0);
        } else if (damager instanceof WitherSkeleton) {
            give(victim, PotionEffectType.WITHER, WITHER_SKELETON_TICKS, 0);
        } else if (damager instanceof WitherSkull) {
            give(victim, PotionEffectType.WITHER, seconds(difficulty, 10, 40), 1);
        } else if (damager instanceof ShulkerBullet) {
            give(victim, PotionEffectType.LEVITATION, SHULKER_BULLET_TICKS, 0);
        } else if (damager instanceof Zombie zombie && emptyHanded(zombie)) {
            // Husks, drowned, zombie villagers and zombified piglins alike: all
            // of them hit as zombies do, a husk with its hunger on top.
            float local = LocalDifficulty.effective(zombie.getLocation());
            if (zombie.getFireTicks() > 0 && ThreadLocalRandom.current().nextFloat() < local * ZOMBIE_FIRE_CHANCE) {
                int ticks = 2 * (int) local * 20;
                if (ticks > 0) {
                    victim.setFireTicks(Math.max(victim.getFireTicks(), ticks));
                }
            }
            if (zombie instanceof Husk) {
                give(victim, PotionEffectType.HUNGER, HUSK_HUNGER_TICKS * (int) local, 0);
            }
        }
    }

    /**
     * Ticks of an effect that only comes on normal and hard, and lasts
     * longer on hard; none on easy and peaceful.
     */
    private static int seconds(Difficulty difficulty, int normal, int hard) {
        return switch (difficulty) {
            case NORMAL -> normal * 20;
            case HARD -> hard * 20;
            default -> 0;
        };
    }

    private static void give(Player victim, PotionEffectType type, int ticks, int amplifier) {
        if (ticks > 0) {
            victim.addPotionEffect(new PotionEffect(type, ticks, amplifier));
        }
    }

    /** Whether the zombie has nothing in its hand: only then does it set fire and bring hunger. */
    private static boolean emptyHanded(Zombie zombie) {
        EntityEquipment equipment = zombie.getEquipment();
        return equipment == null || equipment.getItemInMainHand().getType().isAir();
    }

    /**
     * The local difficulty at a spot, which the hunger of a husk and the fire
     * of a zombie go by. It grows with how long players have spent in the
     * chunk, with the age of the world and with the moon.
     *
     * <p>Neither the Bukkit nor the Paper API hands it out, so it is asked of
     * the server by name, as the game calls it on Paper. Where that fails -
     * on Spigot, whose server goes by other names - it is the lowest the
     * difficulty of the world gives: what a fresh chunk has.</p>
     */
    static final class LocalDifficulty {

        /** {@code CraftWorld#getHandle()}, then the methods of the game, or {@code null} once they failed. */
        private static Method handle;
        private static Method difficultyAt;
        private static Method effective;
        private static java.lang.reflect.Constructor<?> blockPos;
        private static boolean failed;

        private LocalDifficulty() {
        }

        static float effective(Location at) {
            World world = at.getWorld();
            if (world == null) {
                return 0.0f;
            }
            if (!failed) {
                try {
                    if (handle == null) {
                        handle = world.getClass().getMethod("getHandle");
                    }
                    Object level = handle.invoke(world);
                    if (difficultyAt == null) {
                        Class<?> pos = Class.forName("net.minecraft.core.BlockPos", false,
                                level.getClass().getClassLoader());
                        blockPos = pos.getConstructor(int.class, int.class, int.class);
                        difficultyAt = level.getClass().getMethod("getCurrentDifficultyAt", pos);
                    }
                    Object instance = difficultyAt.invoke(level,
                            blockPos.newInstance(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
                    if (effective == null) {
                        effective = instance.getClass().getMethod("getEffectiveDifficulty");
                    }
                    return ((Number) effective.invoke(instance)).floatValue();
                } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
                    failed = true;
                }
            }
            return lowest(world.getDifficulty());
        }

        /** The local difficulty of a chunk nobody has spent any time in, in a new world. */
        private static float lowest(Difficulty difficulty) {
            return switch (difficulty) {
                case EASY -> 0.75f;
                case NORMAL -> 1.5f;
                case HARD -> 2.25f;
                default -> 0.0f;
            };
        }
    }
}
