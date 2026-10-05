package de.elia.cameraplugin.mirrordamage;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.tag.DamageTypeTags;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The push a hit on the body hands out, written down the moment the hit lands
 * and handed to the player once the hit reaches them.
 *
 * <p>Written down right away, because by the time the hit reaches the player
 * the world has moved on. The server takes the direction of the push from the
 * flight of a projectile, and an arrow or a trident that the body turned away
 * has bounced off it and flies back the way it came: a hit passed on a tick
 * later threw the player towards the shooter instead of away. An explosion is
 * over by then altogether, and the player, put back where the body stood a
 * moment ago, is not yet standing on the ground as far as the server knows.</p>
 *
 * <p>These pushes can come out of a hit, each after the rules of the server:</p>
 * <ul>
 *     <li>the push that comes with the damage of almost every hit, away from
 *     the attacker or along the flight of a projectile,</li>
 *     <li>the extra push of an arrow shot from a bow with Punch,</li>
 *     <li>what the attacker pushes by itself - the extra push of a swing, a
 *     sweep, a stab, the ram of a goat, the toss of an iron golem and more,
 *     see {@link AttackerPush},</li>
 *     <li>the push of an explosion. The damage of an explosion carries no push
 *     at all - the explosion pushes everything it hurt by itself, apart from
 *     the damage. The body's damage is cancelled, so the explosion left it
 *     alone, and the player got the damage without the push,</li>
 *     <li>the burst of a wind charge, which goes off right after its hit, see
 *     {@link #awaitsBurst}.</li>
 * </ul>
 */
final class HitPush {

    /** The push that comes with the damage, in the server's own number. */
    private static final float STRENGTH = 0.4F;
    /** How far that push lifts a player off the ground at most. */
    private static final double MAX_LIFT = 0.4;
    /** The extra push of Punch for every level, sideways. */
    private static final double PUNCH_PER_LEVEL = 0.6;
    /** How far the push of Punch lifts, whatever its level. */
    private static final double PUNCH_LIFT = 0.1;
    /**
     * Below this the server sees no direction at all: as the length of a
     * direction, and as the squared length the push of a hit is compared with.
     */
    private static final double NO_DIRECTION = 1.0E-5F;
    /** How far beds and respawn anchors reach when they blow up. */
    private static final float RESPAWN_POINT_RADIUS = 5.0F;
    /** Where primed TNT goes off: this share of its height above its feet. */
    private static final double TNT_CENTER = 0.0625;
    /** How close under its feet the stand-in has to touch a block to stand on it. */
    private static final double GROUND_CONTACT = 1.0E-3;
    /** How deep under the feet the block lies whose slipperiness counts. */
    private static final double GROUND_BLOCK_DEPTH = 0.500001;
    /** What the air leaves of a sideways movement in a tick. */
    private static final double AIR_DRAG = 0.91;
    /** What the air leaves of an upward or downward movement in a tick. */
    private static final double VERTICAL_DRAG = 0.98;
    /** The most gravity pulls on a falling player with Slow Falling. */
    private static final double SLOW_FALLING_GRAVITY = 0.01;
    /** Below this, squared, the server stops a player's sideways movement. */
    private static final double STILL_SIDEWAYS_SQUARED = 9.0E-6;
    /** Below this the server stops a player's upward or downward movement. */
    private static final double STILL_UPWARDS = 0.003;

    /** Where the push of the damage drives the player: flat, of length 1. */
    private final Vector away;
    /** The push of Punch, flat and before knockback resistance. */
    private final Vector punch;
    /** What the attacker pushes by itself. */
    private final AttackerPush attacker;
    /** The push of the explosion, before explosion knockback resistance. */
    private final Vector blast;
    /** Whether the body stood on the ground when it was hit. */
    private final boolean grounded;
    /** How slippery the block under the body is, ice more than stone. */
    private final double slipperiness;
    /**
     * Whether the hit landed while the server was moving the entities of the
     * world - everything but a player's own swing, see {@link #knock}.
     */
    private final boolean worldTick;
    /** Whether a wind charge struck, whose burst follows, see {@link #awaitsBurst}. */
    private final boolean windCharge;
    /** The burst of that wind charge, once it has gone off. */
    private Vector burst;

    private HitPush(Vector away, Vector punch, AttackerPush attacker, Vector blast, boolean grounded,
                    double slipperiness, boolean worldTick, boolean windCharge) {
        this.away = away;
        this.punch = punch;
        this.attacker = attacker;
        this.blast = blast;
        this.grounded = grounded;
        this.slipperiness = slipperiness;
        this.worldTick = worldTick;
        this.windCharge = windCharge;
    }

    /**
     * Writes down the push of the hit the body is taking right now.
     *
     * @param standIn the mannequin taking the hits, where the player will
     *                stand when the hit reaches them
     * @param radius  how far the explosion behind the hit reaches, when it was
     *                announced; {@code null} for any other hit
     * @param swings  how strong the swings of players are that land right now
     */
    static HitPush of(EntityDamageEvent event, LivingEntity standIn, Float radius, SwingStrength swings) {
        DamageSource source = event.getDamageSource();
        DamageType type = source.getDamageType();
        Entity direct = source.getDirectEntity();
        Vector away = null;
        Vector punch = null;
        if (!DamageTypeTags.NO_KNOCKBACK.isTagged(type)) {
            Location spot = standIn.getLocation();
            double fromX = 0.0;
            double fromZ = 0.0;
            if (direct instanceof Projectile projectile) {
                // Along the flight it had when it struck - the one it has a
                // moment later is the bounce off the body.
                Vector flight = projectile.getVelocity();
                fromX = -flight.getX();
                fromZ = -flight.getZ();
                punch = punchOf(projectile, flight);
            } else {
                Location from = source.getSourceLocation();
                if (from != null) {
                    fromX = from.getX() - spot.getX();
                    fromZ = from.getZ() - spot.getZ();
                }
            }
            away = awayFrom(fromX, fromZ);
        }
        Vector blast = null;
        if (DamageTypeTags.IS_EXPLOSION.isTagged(type) && DamageTypeTags.NO_KNOCKBACK.isTagged(type)) {
            if (radius == null && DamageType.BAD_RESPAWN_POINT.equals(type)) {
                radius = RESPAWN_POINT_RADIUS;
            }
            blast = blastOf(source, standIn, event.getDamage(), radius);
        }
        double slipperiness = standIn.getLocation().subtract(0.0, GROUND_BLOCK_DEPTH, 0.0)
                .getBlock().getType().getSlipperiness();
        return new HitPush(away, punch, AttackerPush.of(event, swings), blast, standsOnGround(standIn),
                slipperiness, !(direct instanceof Player), direct instanceof AbstractWindCharge);
    }

    /**
     * Whether the hit gives the player a push along with its damage at all.
     * The server says so itself when the damage is passed on; without damage
     * it is asked here instead, by the same rule: a player whom no hit can
     * hurt is not pushed by one either. The push of an explosion does not ask.
     */
    static boolean takesHits(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR && !player.isInvulnerable();
    }

    /**
     * The speed of a player standing still where the body stood: on the
     * ground, gravity has pulled for one tick and the ground has stopped the
     * fall, so a little of the pull is left over. The push of the damage sets
     * out from there - from a speed of nothing it would lift a little higher
     * than the very same hit does outside camera mode.
     */
    Vector standstill(Player player) {
        if (!grounded) {
            return new Vector();
        }
        return new Vector(0.0, -gravity(player, true) * verticalDrag(player), 0.0);
    }

    /**
     * The push that comes with the damage, set onto the speed the player has:
     * half of that speed is kept, the push is added, and on the ground the
     * player is lifted - all of it against their knockback resistance. Punch
     * comes on top of it, and what the attacker pushes by itself before and
     * after that, see {@link AttackerPush}. The burst of a wind charge joins
     * them before the tick below, the way the server adds it to the same
     * speed.
     *
     * <p>Which of them reach the player the server decides by how the damage
     * landed. The push of the damage it hands out only for damage that
     * landed in full, and nothing else sends the attacker's pushes to a
     * player either. Such damage is told apart in two ways: one that carries a
     * push of its own moved the player, and one that carries none - a stab -
     * started a new span of invulnerability.</p>
     *
     * <p>A hit that lands while the server moves the entities of the world -
     * an arrow, a trident, a mob, everything but the swing of a player, which
     * comes in between two of those rounds - reaches the player one tick late:
     * the server moves them a tick along the push first and only then sends
     * it. Such a push is sent the same way here, a tick along, or it would
     * carry the player well beyond where the same hit does outside camera
     * mode.</p>
     *
     * @param knocked whether the damage passed on to the player pushed them
     * @param landed  whether that damage landed in full
     */
    Vector knock(Player player, Vector velocity, boolean knocked, boolean landed) {
        boolean struck = away != null ? knocked : landed;
        double resistance = attributeValue(player, Attribute.KNOCKBACK_RESISTANCE, 0.0);
        double kept = 1.0 - resistance;
        Vector pushed = velocity.clone();
        boolean moved = false;
        Vector before = attacker.before();
        if (before != null && struck) {
            pushed.add(before);
            moved = true;
        }
        if (away != null && knocked) {
            pushed = step(pushed, away, STRENGTH * kept, grounded);
            double punchKept = Math.max(0.0, kept);
            if (punch != null && punchKept > 0.0) {
                pushed.add(new Vector(punch.getX() * punchKept, PUNCH_LIFT, punch.getZ() * punchKept));
            }
            moved = true;
        }
        Vector after = struck ? attacker.after(pushed, resistance, grounded) : null;
        if (after != null) {
            pushed = after;
            moved = true;
        }
        if (!moved) {
            return pushed;
        }
        if (burst != null) {
            pushed.add(burst);
        }
        return worldTick ? oneTickAlong(player, pushed) : pushed;
    }

    /**
     * One push by the server's rule: half of the speed is kept, the push is
     * added, and on the ground it lifts - never higher than {@link #MAX_LIFT}.
     */
    static Vector step(Vector velocity, Vector direction, double power, boolean grounded) {
        return new Vector(
                velocity.getX() / 2.0 + direction.getX() * power,
                grounded ? Math.min(MAX_LIFT, velocity.getY() / 2.0 + power) : velocity.getY(),
                velocity.getZ() / 2.0 + direction.getZ() * power);
    }

    /**
     * Whether a wind charge struck the body and its burst is still to come.
     * The burst goes off right after the hit - and the player, put back where
     * the body stood the moment the hit ended camera mode, is standing in it
     * by then. It would push them right away, the hit reaching them two ticks
     * later would wipe that push out again, and the push of the hit would come
     * too late to set out from it. So the burst is held back and handed out
     * with the hit, see {@link #catchBurst}.
     */
    boolean awaitsBurst() {
        return windCharge && burst == null;
    }

    /**
     * Takes over the push of the wind charge's burst, as the server worked it
     * out for the player standing where the body stood.
     */
    void catchBurst(Vector push) {
        burst = push.clone();
    }

    /**
     * The burst held back for this hit, {@code null} when none went off. It
     * goes out the way the server sends it: on its own, right away - and
     * with a hit that landed, a tick later together with the hit's push, see
     * {@link #knock}.
     */
    Vector caughtBurst() {
        return burst == null ? null : burst.clone();
    }

    /**
     * The push of the explosion, added onto the speed the player has, against
     * their explosion knockback resistance - the share Blast Protection takes
     * off. The explosion sends it right away, apart from every other push.
     * Nothing for an explosion that does not push players: a spectator, or a
     * player flying in creative mode.
     */
    Vector blast(Player player, Vector velocity) {
        GameMode mode = player.getGameMode();
        if (blast == null || mode == GameMode.SPECTATOR || (mode == GameMode.CREATIVE && player.isFlying())) {
            return velocity.clone();
        }
        double kept = 1.0 - attributeValue(player, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE, 0.0);
        return velocity.clone().add(blast.clone().multiply(kept));
    }

    /**
     * What a tick of moving leaves of a speed, the way the server moves a
     * player who stands where the body stood: what is too slow stops, the
     * ground and the air slow the rest down, and gravity pulls. Blocks in the
     * way are not looked at - the player is pushed away from what hit them,
     * into the open.
     */
    private Vector oneTickAlong(Player player, Vector velocity) {
        double x = velocity.getX();
        double y = velocity.getY();
        double z = velocity.getZ();
        if (x * x + z * z < STILL_SIDEWAYS_SQUARED) {
            x = 0.0;
            z = 0.0;
        }
        if (Math.abs(y) < STILL_UPWARDS) {
            y = 0.0;
        }
        double airDrag = modifiedFriction(AIR_DRAG, attributeValue(player, Attribute.AIR_DRAG_MODIFIER, 1.0));
        double friction = grounded
                ? modifiedFriction(slipperiness, attributeValue(player, Attribute.FRICTION_MODIFIER, 1.0)) * airDrag
                : airDrag;
        return new Vector(x * friction, (y - gravity(player, y <= 0.0)) * verticalDrag(player), z * friction);
    }

    /** How hard gravity pulls on the player in a tick. */
    private static double gravity(Player player, boolean falling) {
        double gravity = attributeValue(player, Attribute.GRAVITY, 0.08);
        if (falling && player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            gravity = Math.min(gravity, SLOW_FALLING_GRAVITY);
        }
        return gravity;
    }

    /** What a tick in the air leaves of an upward or downward movement. */
    private static double verticalDrag(Player player) {
        return modifiedFriction(VERTICAL_DRAG, attributeValue(player, Attribute.AIR_DRAG_MODIFIER, 1.0));
    }

    /** A friction after the modifier the player's attributes put on it. */
    private static double modifiedFriction(double friction, double modifier) {
        return Math.clamp(1.0 - (1.0 - friction) * modifier, 0.0, 1.0);
    }

    /**
     * Whether something solid touches the stand-in from below. Asked of the
     * blocks and not of the entity: a body held in its spot on sensitivity
     * level 0 has no gravity and never finds out it is standing on anything.
     */
    private static boolean standsOnGround(LivingEntity standIn) {
        BoundingBox box = standIn.getBoundingBox();
        BoundingBox sole = new BoundingBox(box.getMinX(), box.getMinY() - GROUND_CONTACT, box.getMinZ(),
                box.getMaxX(), box.getMinY(), box.getMaxZ());
        World world = standIn.getWorld();
        for (int x = floor(sole.getMinX()); x <= floor(sole.getMaxX()); x++) {
            for (int y = floor(sole.getMinY()); y <= floor(sole.getMaxY()); y++) {
                for (int z = floor(sole.getMinZ()); z <= floor(sole.getMaxZ()); z++) {
                    BoundingBox local = sole.clone().shift(-x, -y, -z);
                    if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(local)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Turns the direction a push comes from into the one it drives the player
     * in. Where there is no direction worth the name - a hit from straight
     * above, or from nowhere - the server picks a tiny one at random, and so
     * does this; a side that is out of all proportion is picked at random too.
     */
    static Vector awayFrom(double fromX, double fromZ) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (Math.abs(fromX) > 200.0) {
            fromX = random.nextDouble() - random.nextDouble();
        }
        if (Math.abs(fromZ) > 200.0) {
            fromZ = random.nextDouble() - random.nextDouble();
        }
        while (fromX * fromX + fromZ * fromZ < NO_DIRECTION) {
            fromX = (random.nextDouble() - random.nextDouble()) * 0.01;
            fromZ = (random.nextDouble() - random.nextDouble()) * 0.01;
        }
        return new Vector(-fromX, 0.0, -fromZ).normalize();
    }

    /**
     * The push of Punch: only from an arrow, and only when the bow it was shot
     * from carries the enchantment. It drives along the flight of the arrow,
     * the stronger the higher the level.
     */
    private static Vector punchOf(Projectile projectile, Vector flight) {
        if (!(projectile instanceof Arrow) && !(projectile instanceof SpectralArrow)) {
            return null;
        }
        ItemStack bow = ((AbstractArrow) projectile).getWeapon();
        int level = bow == null ? 0 : bow.getEnchantmentLevel(Enchantment.PUNCH);
        Vector flat = new Vector(flight.getX(), 0.0, flight.getZ());
        double length = flat.length();
        if (level <= 0 || length < NO_DIRECTION) {
            return null;
        }
        return flat.multiply(level * PUNCH_PER_LEVEL / length);
    }

    /**
     * The push of an explosion on a player standing where the stand-in
     * stands: from the middle of the explosion towards their eyes, as strong
     * as the share of the explosion that reached them.
     *
     * @return {@code null} when the explosion has no place to push away from
     */
    private static Vector blastOf(DamageSource source, LivingEntity standIn, double damage, Float radius) {
        Vector center = explosionCenter(source);
        if (center == null) {
            return null;
        }
        Vector direction = standIn.getEyeLocation().toVector().subtract(center);
        double length = direction.length();
        if (length < NO_DIRECTION) {
            return new Vector();
        }
        double distance = standIn.getLocation().toVector().distance(center);
        return direction.multiply(reachedShare(damage, radius, distance) / length);
    }

    /**
     * Where the explosion went off. Beds and respawn anchors carry the spot in
     * their damage; everything else blew up where it stood - primed TNT a
     * little above its feet, and the wither at its eyes when it is born.
     */
    private static Vector explosionCenter(DamageSource source) {
        Location spot = source.getDamageLocation();
        if (spot != null) {
            return spot.toVector();
        }
        Entity direct = source.getDirectEntity();
        if (direct == null) {
            return null;
        }
        Vector center = direct.getLocation().toVector();
        if (direct instanceof TNTPrimed) {
            center.setY(center.getY() + TNT_CENTER * direct.getHeight());
        } else if (direct instanceof Wither wither) {
            center.setY(wither.getEyeLocation().getY());
        }
        return center;
    }

    /**
     * How much of the explosion reached the spot, between 0 and 1: its full
     * strength, less what the distance and what stood in between took off.
     * The push is exactly that share, and so is everything the damage was
     * worked out from - the damage is {@code (s * s + s) / 2 * 7 * 2r + 1} for
     * a share {@code s} and a radius {@code r}. With the radius known, the
     * share is read back out of the damage the body took.
     *
     * <p>A radius nobody announced - an explosion another plugin set off - is
     * worked out from the distance instead, as if nothing stood in between:
     * the share is then {@code 1 - distance / 2r}, and with the damage that is
     * enough to find both.</p>
     */
    private static double reachedShare(double damage, Float radius, double distance) {
        double reach = damage - 1.0;
        if (reach <= 0.0) {
            return 0.0;
        }
        double share;
        if (radius != null && radius > 0.0F) {
            share = (Math.sqrt(1.0 + 4.0 * reach / (7.0 * radius)) - 1.0) / 2.0;
        } else if (distance < NO_DIRECTION) {
            share = 1.0;
        } else {
            double half = distance / 2.0;
            double b = 7.0 * half + reach;
            share = (Math.sqrt(b * b + 28.0 * half * reach) - b) / (14.0 * half);
        }
        return Math.clamp(share, 0.0, 1.0);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** The value of one of the player's attributes, or the game's default without it. */
    private static double attributeValue(Player player, Attribute attribute, double fallback) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }
}
