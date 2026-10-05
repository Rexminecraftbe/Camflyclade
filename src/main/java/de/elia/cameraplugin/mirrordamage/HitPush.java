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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zoglin;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
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
 *     <li>the attacker's own pushes, the way the attacker faces: the extra
 *     push of a swing or a bite - for the Knockback enchantment, for the
 *     attack knockback of a ravager or a warden, and for a sprint into a
 *     swing at full strength -, the push a sweep hands out to everything
 *     around what it was aimed at, and the push of a spear's stab, which
 *     carries none with its damage,</li>
 *     <li>the push of an explosion. The damage of an explosion carries no push
 *     at all - the explosion pushes everything it hurt by itself, apart from
 *     the damage. The body's damage is cancelled, so the explosion left it
 *     alone, and the player got the damage without the push.</li>
 * </ul>
 *
 * <p>The attacker's own pushes are handed out only after the damage has
 * landed, and only where it did - the body's damage is cancelled, so the
 * server never hands them out at all. A stab is the exception: it pushes what
 * it struck either way, and only that push shows whether the stab pushed at
 * all.</p>
 */
final class HitPush {

    /** The push that comes with the damage, in the server's own number. */
    private static final float STRENGTH = 0.4F;
    /** The push of a sweep on everything it reaches besides what it was aimed at. */
    private static final float SWEEP_STRENGTH = 0.4F;
    /** The first push of a spear's stab, the one every stab carries. */
    private static final float STAB_STRENGTH = 0.4F;
    /** What a sprint adds to the push of a swing at full strength. */
    private static final float SPRINT_BONUS = 0.5F;
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
    /** The steps of the server's sine table in a radian. */
    private static final double SINE_STEPS_PER_RADIAN = 10430.378350470453;
    /** Picks a step of the table out of any number of turns. */
    private static final long SINE_STEP_MASK = 65535L;
    /** A quarter turn in steps of that table, the way from a sine to a cosine. */
    private static final double QUARTER_TURN_STEPS = 16384.0;
    /** No push of the attacker's own. */
    private static final float[] NO_PUSHES = new float[0];

    /** Where the push of the damage drives the player: flat, of length 1. */
    private final Vector away;
    /** The push of Punch, flat and before knockback resistance. */
    private final Vector punch;
    /** Where the attacker's own pushes drive the player: the way the attacker faces, flat, of length 1. */
    private final Vector along;
    /** How hard each of the attacker's own pushes drives, one after the other, before knockback resistance. */
    private final float[] extra;
    /** What a stab struck, {@code null} for any other hit, see {@link #stabPushed}. */
    private final Entity stabbed;
    /** How fast that went the moment the stab struck. */
    private final Vector stabbedSpeed;
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

    private HitPush(Vector away, Vector punch, Vector along, float[] extra, Entity stabbed, Vector blast,
                    boolean grounded, double slipperiness, boolean worldTick) {
        this.away = away;
        this.punch = punch;
        this.along = along;
        this.extra = extra;
        this.stabbed = stabbed;
        this.stabbedSpeed = stabbed == null ? null : stabbed.getVelocity();
        this.blast = blast;
        this.grounded = grounded;
        this.slipperiness = slipperiness;
        this.worldTick = worldTick;
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
        // The attacker's own pushes: only from one that hits with its own
        // hands, a weapon or its teeth.
        Vector along = null;
        float[] extra = NO_PUSHES;
        Entity stabbed = null;
        if (direct instanceof LivingEntity attacker && direct.equals(source.getCausingEntity())) {
            if (DamageType.SPEAR.equals(type)) {
                extra = stabPushes(attacker);
                stabbed = event.getEntity();
            } else if (event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK) {
                extra = new float[]{SWEEP_STRENGTH};
            } else if (event.getCause() == DamageCause.ENTITY_ATTACK) {
                extra = swingPushes(attacker, event, swings);
            }
            if (extra.length > 0) {
                along = facing(attacker);
            }
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
        return new HitPush(away, punch, along, extra, stabbed, blast, standsOnGround(standIn), slipperiness,
                !(direct instanceof Player));
    }

    /**
     * The extra push of a swing or a bite: half of the attacker's attack
     * knockback and of the level of Knockback on the weapon in their hand,
     * and for a player half a push more when they sprint into a swing at full
     * strength. Hoglins and zoglins throw what they bite by a rule of their
     * own instead, which comes out differently every time.
     */
    private static float[] swingPushes(LivingEntity attacker, EntityDamageEvent event, SwingStrength swings) {
        if (attacker instanceof Hoglin || attacker instanceof Zoglin) {
            return NO_PUSHES;
        }
        float power = attackKnockback(attacker);
        if (attacker instanceof Player player) {
            Entity body = event.getEntity();
            if (!swings.isSwing(player, body)) {
                return NO_PUSHES;
            }
            if (player.isSprinting() && swings.atFullStrength(player, body, event.getDamage())) {
                power += SPRINT_BONUS;
            }
        }
        return power > 0.0F ? new float[]{power} : NO_PUSHES;
    }

    /**
     * The pushes of a spear's stab: a fixed one, then the extra push a swing
     * of the attacker would carry. Of a player's stab only the first one ever
     * reaches another player - the server sends it right away and then puts
     * the struck player's speed back to what it was, and nothing sends the
     * second one.
     */
    private static float[] stabPushes(LivingEntity attacker) {
        float second = attacker instanceof Player ? 0.0F : attackKnockback(attacker);
        return second > 0.0F ? new float[]{STAB_STRENGTH, second} : new float[]{STAB_STRENGTH};
    }

    /** Half of the attacker's attack knockback, with Knockback on their weapon added in. */
    private static float attackKnockback(LivingEntity attacker) {
        AttributeInstance attribute = attacker.getAttribute(Attribute.ATTACK_KNOCKBACK);
        float knockback = attribute == null ? 0.0F : (float) attribute.getValue();
        EntityEquipment equipment = attacker.getEquipment();
        if (equipment != null) {
            knockback += equipment.getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK);
        }
        return knockback / 2.0F;
    }

    /**
     * The way the attacker faces, flat. The server reads it off the turn of
     * the attacker in its own sine table, and so does this. For a player the
     * turn is where they look. A mob turns its body the way it walks, which
     * Bukkit does not show, and its head the way it looks - while a mob
     * attacks, both point at what it attacks, so its head stands in.
     */
    private static Vector facing(LivingEntity attacker) {
        float turn = attacker.getLocation().getYaw() % 360.0F * (float) (Math.PI / 180.0);
        return awayFrom(tableSine(turn, 0.0), -tableSine(turn, QUARTER_TURN_STEPS));
    }

    /** The sine the server's table holds for an angle, shifted by a number of its steps. */
    private static double tableSine(float radians, double shift) {
        long step = (long) (radians * SINE_STEPS_PER_RADIAN + shift) & SINE_STEP_MASK;
        return (float) Math.sin(step / SINE_STEPS_PER_RADIAN);
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
     * comes on top of it, and the attacker's own pushes after that, each by
     * the same rule.
     *
     * <p>Which of them reach the player the server decides by how the damage
     * landed. The push of the damage it hands out only for damage that
     * landed in full, and nothing else sends the attacker's own pushes to a
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
        double kept = 1.0 - attributeValue(player, Attribute.KNOCKBACK_RESISTANCE, 0.0);
        Vector pushed = velocity.clone();
        boolean moved = false;
        if (away != null && knocked) {
            pushed = step(pushed, away, STRENGTH * kept);
            double punchKept = Math.max(0.0, kept);
            if (punch != null && punchKept > 0.0) {
                pushed.add(new Vector(punch.getX() * punchKept, PUNCH_LIFT, punch.getZ() * punchKept));
            }
            moved = true;
        }
        boolean struck = away != null ? knocked : landed;
        if (extra.length > 0 && struck && (stabbed == null || stabPushed())) {
            for (float power : extra) {
                pushed = step(pushed, along, power * kept);
            }
            moved = true;
        }
        if (!moved) {
            return pushed;
        }
        return worldTick ? oneTickAlong(player, pushed) : pushed;
    }

    /**
     * One push by the server's rule: half of the speed is kept, the push is
     * added, and on the ground it lifts - never higher than {@link #MAX_LIFT}.
     */
    private Vector step(Vector velocity, Vector direction, double power) {
        return new Vector(
                velocity.getX() / 2.0 + direction.getX() * power,
                grounded ? Math.min(MAX_LIFT, velocity.getY() / 2.0 + power) : velocity.getY(),
                velocity.getZ() / 2.0 + direction.getZ() * power);
    }

    /**
     * Whether the stab pushed what it struck. The stab pushes right after its
     * damage, cancelled or not, and only when it carries a push at all - a
     * spear charged at too low a speed does not. What it struck has just been
     * removed with the body and moves no more by itself, so any speed it has
     * gained since is that push.
     */
    private boolean stabPushed() {
        return !stabbed.getVelocity().equals(stabbedSpeed);
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
    private static Vector awayFrom(double fromX, double fromZ) {
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
