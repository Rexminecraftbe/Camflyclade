package de.elia.cameraplugin.mirrordamage;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.AbstractNautilus;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Bee;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Zoglin;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What an attacker pushes by itself when it hits, apart from the push that
 * comes with the damage - see {@link HitPush}.
 *
 * <p>The server hands most of these out only once the damage has landed, and
 * the body's damage is cancelled, so it never hands them out at all. What is
 * left is to do it here, attacker by attacker, by the server's rules:</p>
 * <ul>
 *     <li>the extra push of a swing or a bite, the way the attacker faces -
 *     for the Knockback enchantment, for the attack knockback of a ravager or
 *     a warden, and for a sprint into a swing at full strength,</li>
 *     <li>the push a sweep hands out to everything around what it was aimed
 *     at,</li>
 *     <li>the pushes of a spear's stab, which carries none with its damage,</li>
 *     <li>the ram of a goat and of a nautilus,</li>
 *     <li>the toss of an iron golem,</li>
 *     <li>the throw of a hoglin and a zoglin, different every time,</li>
 *     <li>and the wing of the ender dragon, which pushes before it hits.</li>
 * </ul>
 *
 * <p>A stab and a ram push what they struck even when its damage was
 * cancelled. The ram is read back off the body for that reason: which way
 * the goat ran and how fast, nothing else tells.</p>
 */
final class AttackerPush {

    /** An attacker that pushes nothing by itself. */
    static final AttackerPush NONE = new AttackerPush(null, null, new float[0], null, false, 0.0F, 0.0, null);

    /** The push of a sweep on everything it reaches besides what it was aimed at. */
    private static final float SWEEP_STRENGTH = 0.4F;
    /** The first push of a spear's stab, the one every stab carries. */
    private static final float STAB_STRENGTH = 0.4F;
    /** What a sprint adds to the push of a swing at full strength. */
    private static final float SPRINT_BONUS = 0.5F;
    /** How far an iron golem tosses what it hits upwards, before knockback resistance. */
    private static final float GOLEM_TOSS = 0.4F;
    /** A hoglin turns its throw by a whole number of radians, this many at most either way. */
    private static final int THROW_TURN = 10;
    /** The least share of its strength a hoglin throws sideways with. */
    private static final float THROW_REACH_LEAST = 0.2F;
    /** How much more of it the throw may get, at random. */
    private static final float THROW_REACH_SPREAD = 0.5F;
    /** The most share of its strength a hoglin throws upwards with. */
    private static final double THROW_LIFT_MOST = 0.5;
    /** How hard the wing of the ender dragon pushes, for each block it is away. */
    private static final double WING_STRENGTH = 4.0;
    /** How far the wing lifts what it pushes. */
    private static final float WING_LIFT = 0.2F;
    /** Below this squared distance from its body the wing pushes as hard as it ever does. */
    private static final double WING_NEAREST_SQUARED = 0.1;
    /** How far around the wing it reaches sideways, upwards and downwards before it is moved down. */
    private static final double WING_REACH_SIDEWAYS = 4.0;
    private static final double WING_REACH_UPWARDS = 2.0;
    /** How far the reach of the wing is moved down. */
    private static final double WING_DROP = 2.0;
    /** How wide the parts of the dragon are that count here: its wings and its body. */
    private static final double WING_WIDTH = 4.0;
    private static final double DRAGON_BODY_WIDTH = 5.0;
    /** Where a ravager is in its roar the moment it roars. */
    private static final int ROAR_TICK = 10;
    /** Below this the server sees no direction at all, as for the push of the damage. */
    private static final double NO_DIRECTION = 1.0E-5F;
    /** The steps of the server's sine table in a radian. */
    private static final double SINE_STEPS_PER_RADIAN = 10430.378350470453;
    /** Picks a step of the table out of any number of turns. */
    private static final long SINE_STEP_MASK = 65535L;
    /** A quarter turn in steps of that table, the way from a sine to a cosine. */
    private static final double QUARTER_TURN_STEPS = 16384.0;
    /** Where a saved mob carries its turn - the one the server pushes along. */
    private static final String SAVED_TURN = "Rotation:[";
    /**
     * Paper's question to a ravager how far it is into its roar. Spigot does
     * not ask it; there a roar counts as a bite.
     */
    private static final Method ROAR_TICKS = roarTicks();

    /** The push before the damage: the wing of the ender dragon. */
    private final Vector before;
    /** Where the knockbacks of a swing, a sweep and a stab drive: the way the attacker faces, flat, of length 1. */
    private final Vector along;
    /** Those knockbacks, one after the other, before knockback resistance. */
    private final float[] knocks;
    /** What a stab or a ram struck, {@code null} for any other hit, see {@link #struckMoved()}. */
    private final LivingEntity struck;
    /** How fast that went the moment it was struck. */
    private final Vector struckSpeed;
    /** Whether the push is read back off what was struck: the ram of a goat or a nautilus. */
    private final boolean ram;
    /** The toss of an iron golem, upwards, before knockback resistance. */
    private final float toss;
    /** The attack knockback a hoglin or a zoglin throws with, 0 for any other attacker. */
    private final double throwStrength;
    /** Where it throws to: from the thrower to the body, flat. */
    private final Vector throwTowards;

    private AttackerPush(Vector before, Vector along, float[] knocks, LivingEntity struck, boolean ram,
                         float toss, double throwStrength, Vector throwTowards) {
        this.before = before;
        this.along = along;
        this.knocks = knocks;
        this.struck = struck;
        this.struckSpeed = struck == null ? null : struck.getVelocity();
        this.ram = ram;
        this.toss = toss;
        this.throwStrength = throwStrength;
        this.throwTowards = throwTowards;
    }

    /**
     * Writes down what the attacker of the hit the body is taking right now
     * pushes by itself: only an attacker that hits with its own hands, a
     * weapon, its teeth or its body.
     */
    static AttackerPush of(EntityDamageEvent event, SwingStrength swings) {
        DamageSource source = event.getDamageSource();
        Entity direct = source.getDirectEntity();
        if (!(direct instanceof LivingEntity attacker) || !direct.equals(source.getCausingEntity())
                || !(event.getEntity() instanceof LivingEntity hit)) {
            return NONE;
        }
        if (DamageType.SPEAR.equals(source.getDamageType())) {
            return knocking(attacker, stabKnocks(attacker), hit);
        }
        DamageCause cause = event.getCause();
        if (cause == DamageCause.ENTITY_SWEEP_ATTACK) {
            return knocking(attacker, new float[]{SWEEP_STRENGTH}, null);
        }
        if (cause != DamageCause.ENTITY_ATTACK) {
            return NONE;
        }
        if (attacker instanceof Goat || attacker instanceof AbstractNautilus) {
            return new AttackerPush(null, null, new float[0], hit, true, 0.0F, 0.0, null);
        }
        if (attacker instanceof IronGolem) {
            return new AttackerPush(null, null, new float[0], null, false, GOLEM_TOSS, 0.0, null);
        }
        if (attacker instanceof Hoglin || attacker instanceof Zoglin) {
            return throwing(attacker, hit);
        }
        if (attacker instanceof EnderDragon dragon) {
            Vector wing = wingPush(dragon, hit);
            return wing == null ? NONE : new AttackerPush(wing, null, new float[0], null, false, 0.0F, 0.0, null);
        }
        if (bitesByItsOwnRule(attacker)) {
            return NONE;
        }
        float[] knocks = swingKnocks(attacker, event, swings);
        return knocks.length == 0 ? NONE : knocking(attacker, knocks, null);
    }

    private static AttackerPush knocking(LivingEntity attacker, float[] knocks, LivingEntity struck) {
        return new AttackerPush(null, facing(attacker), knocks, struck, false, 0.0F, 0.0, null);
    }

    /**
     * The push before the damage, {@code null} when there is none. The wing
     * of the ender dragon pushes first and hits afterwards, so the push of the
     * damage sets out from it.
     */
    Vector before() {
        return before == null ? null : before.clone();
    }

    /**
     * The pushes after the damage, set onto the speed the damage's own push
     * left - each by the server's rule for it.
     *
     * @param resistance the player's knockback resistance
     * @param grounded   whether the body stood on the ground when it was hit
     * @return the speed after them, {@code null} when the attacker pushes
     *         nothing after the damage
     */
    Vector after(Vector velocity, double resistance, boolean grounded) {
        double kept = 1.0 - resistance;
        if (knocks.length > 0) {
            if (struck != null && !struckMoved()) {
                return null;
            }
            Vector pushed = velocity;
            for (float power : knocks) {
                pushed = HitPush.step(pushed, along, power * kept, grounded);
            }
            return pushed;
        }
        if (ram) {
            Vector rammed = rammed();
            if (rammed == null) {
                return null;
            }
            double power = rammed.length();
            return HitPush.step(velocity, rammed.multiply(1.0 / power), power * kept, grounded);
        }
        if (toss > 0.0F) {
            return velocity.clone().add(new Vector(0.0, toss * Math.max(0.0, kept), 0.0));
        }
        if (throwStrength > 0.0) {
            Vector thrown = thrown(resistance);
            return thrown == null ? null : velocity.clone().add(thrown);
        }
        return null;
    }

    /**
     * Whether what was struck has been pushed since. A stab and a ram push
     * right after their damage, cancelled or not - but only when they carry a
     * push at all: a spear charged at too low a speed does not. What was
     * struck has just been removed with the body and moves no more by itself,
     * so any speed it has gained since is that push.
     */
    private boolean struckMoved() {
        return !struck.getVelocity().equals(struckSpeed);
    }

    /**
     * The ram, read back off what it struck: the server kept half of its
     * speed sideways and added the push, against its knockback resistance.
     * Back come the direction of the ram and its strength, flat.
     *
     * @return {@code null} when it was not rammed, or when its resistance
     *         kept every push off
     */
    private Vector rammed() {
        if (!struckMoved()) {
            return null;
        }
        Vector now = struck.getVelocity();
        double x = now.getX() - struckSpeed.getX() / 2.0;
        double z = now.getZ() - struckSpeed.getZ() / 2.0;
        double kept = 1.0 - attributeValue(struck, Attribute.KNOCKBACK_RESISTANCE, 0.0);
        if (x * x + z * z < NO_DIRECTION || kept <= 0.0) {
            return null;
        }
        return new Vector(x / kept, 0.0, z / kept);
    }

    /**
     * The throw of a hoglin or a zoglin: its attack knockback less the
     * player's knockback resistance - taken off, not as a share -, sideways
     * away from it and up, each by a share drawn at random, and turned by a
     * whole number of radians drawn at random as well. The server draws its
     * own numbers, so the throw here comes out like one of its throws, not
     * like the one it would have thrown.
     */
    private Vector thrown(double resistance) {
        double strength = throwStrength - resistance;
        if (strength <= 0.0) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float turn = random.nextInt(2 * THROW_TURN + 1) - THROW_TURN;
        double reach = strength * (random.nextFloat() * THROW_REACH_SPREAD + THROW_REACH_LEAST);
        double lift = strength * random.nextFloat() * THROW_LIFT_MOST;
        double length = throwTowards.length();
        double x = length < NO_DIRECTION ? 0.0 : throwTowards.getX() / length * reach;
        double z = length < NO_DIRECTION ? 0.0 : throwTowards.getZ() / length * reach;
        float cos = (float) tableSine(turn, QUARTER_TURN_STEPS);
        float sin = (float) tableSine(turn, 0.0);
        return new Vector(x * cos + z * sin, lift, z * cos - x * sin);
    }

    private static AttackerPush throwing(LivingEntity thrower, LivingEntity hit) {
        if (thrower instanceof Ageable ageable && !ageable.isAdult()) {
            return NONE;
        }
        double strength = attributeValue(thrower, Attribute.ATTACK_KNOCKBACK, 0.0);
        Vector towards = hit.getLocation().toVector().subtract(thrower.getLocation().toVector()).setY(0.0);
        return new AttackerPush(null, null, new float[0], null, false, 0.0F, strength, towards);
    }

    /**
     * The push of the ender dragon's wing, as long as it reached what was
     * hit: away from the dragon's body, the harder the closer, and a little
     * up. Head and neck hit without pushing; a hit from them counts only when
     * a wing reached the spot as well, which pushed before them.
     */
    private static Vector wingPush(EnderDragon dragon, LivingEntity hit) {
        BoundingBox body = null;
        boolean underWing = false;
        BoundingBox spot = hit.getBoundingBox();
        for (ComplexEntityPart part : dragon.getParts()) {
            if (part.getWidth() == DRAGON_BODY_WIDTH) {
                body = part.getBoundingBox();
            } else if (part.getWidth() == WING_WIDTH) {
                BoundingBox reach = part.getBoundingBox()
                        .expand(WING_REACH_SIDEWAYS, WING_REACH_UPWARDS, WING_REACH_SIDEWAYS)
                        .shift(0.0, -WING_DROP, 0.0);
                underWing |= reach.overlaps(spot);
            }
        }
        if (body == null || !underWing) {
            return null;
        }
        Vector spotAt = hit.getLocation().toVector();
        double x = spotAt.getX() - body.getCenterX();
        double z = spotAt.getZ() - body.getCenterZ();
        double squared = Math.max(x * x + z * z, WING_NEAREST_SQUARED);
        return new Vector(x / squared * WING_STRENGTH, WING_LIFT, z / squared * WING_STRENGTH);
    }

    /**
     * Whether the attacker bites by a rule of its own, without the extra push
     * a bite usually carries: a bee stings, the cube mobs and the pufferfish
     * hurt by touch, and a ravager's roar hurts without it.
     */
    private static boolean bitesByItsOwnRule(LivingEntity attacker) {
        return attacker instanceof Bee || attacker instanceof AbstractCubeMob || attacker instanceof PufferFish
                || attacker instanceof Ravager ravager && roaring(ravager);
    }

    /**
     * The extra push of a swing or a bite: half of the attacker's attack
     * knockback and of the level of Knockback on the weapon in their hand,
     * and for a player half a push more when they sprint into a swing at full
     * strength.
     */
    private static float[] swingKnocks(LivingEntity attacker, EntityDamageEvent event, SwingStrength swings) {
        float power = attackKnockback(attacker);
        if (attacker instanceof Player player) {
            Entity body = event.getEntity();
            if (!swings.isSwing(player, body)) {
                return new float[0];
            }
            if (player.isSprinting() && swings.atFullStrength(player, body, event.getDamage())) {
                power += SPRINT_BONUS;
            }
        }
        return power > 0.0F ? new float[]{power} : new float[0];
    }

    /**
     * The pushes of a spear's stab: a fixed one, then the extra push a swing
     * of the attacker would carry. Of a player's stab only the first one ever
     * reaches another player - the server sends it right away and then puts
     * the struck player's speed back to what it was, and nothing sends the
     * second one.
     */
    private static float[] stabKnocks(LivingEntity attacker) {
        float second = attacker instanceof Player ? 0.0F : attackKnockback(attacker);
        return second > 0.0F ? new float[]{STAB_STRENGTH, second} : new float[]{STAB_STRENGTH};
    }

    /** Half of the attacker's attack knockback, with Knockback on their weapon added in. */
    private static float attackKnockback(LivingEntity attacker) {
        float knockback = (float) attributeValue(attacker, Attribute.ATTACK_KNOCKBACK, 0.0);
        EntityEquipment equipment = attacker.getEquipment();
        if (equipment != null) {
            knockback += equipment.getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK);
        }
        return knockback / 2.0F;
    }

    /**
     * The way the attacker faces, flat, read off its turn in the server's own
     * sine table. A player's turn is where they look. A mob turns its body the
     * way it walks, and its head the way it looks - the server goes by the
     * body, and Bukkit shows only the head, see {@link #bodyTurn}.
     */
    private static Vector facing(LivingEntity attacker) {
        float turn = attacker instanceof Player ? attacker.getLocation().getYaw() % 360.0F : bodyTurn(attacker);
        float radians = turn * (float) (Math.PI / 180.0);
        return HitPush.awayFrom(tableSine(radians, 0.0), -tableSine(radians, QUARTER_TURN_STEPS));
    }

    /**
     * The turn of a mob's body. No getter hands it out, but the mob carries
     * it in what it is saved as - {@code Rotation}, at the top, the very turn
     * the server pushes along. Should that not be readable, its head stands
     * in: while a mob attacks, both point at what it attacks.
     */
    private static float bodyTurn(LivingEntity mob) {
        Float saved;
        try {
            String save = mob.getAsString();
            saved = save == null ? null : savedTurn(save);
        } catch (RuntimeException ex) {
            saved = null;
        }
        return saved != null ? saved : mob.getLocation().getYaw();
    }

    /**
     * The turn at the top of a save, {@code Rotation:[turn,pitch]}. A
     * passenger saved inside carries a turn of its own, and quoted text may
     * hold anything - both are stepped over.
     *
     * @return {@code null} when the save carries none
     */
    static Float savedTurn(String save) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < save.length(); i++) {
            char c = save.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                default -> {
                    if (depth == 1 && startsKey(save, i) && save.startsWith(SAVED_TURN, i)) {
                        return number(save, i + SAVED_TURN.length());
                    }
                }
            }
        }
        return null;
    }

    /** Whether a key of the compound starts here: right after its opening brace or a comma. */
    private static boolean startsKey(String save, int at) {
        int before = at - 1;
        while (before >= 0 && Character.isWhitespace(save.charAt(before))) {
            before--;
        }
        return before >= 0 && (save.charAt(before) == '{' || save.charAt(before) == ',');
    }

    /** The number written from here on, {@code null} when there is none. */
    private static Float number(String save, int from) {
        int to = from;
        while (to < save.length() && "+-.0123456789Ee".indexOf(save.charAt(to)) >= 0) {
            to++;
        }
        try {
            return Float.parseFloat(save.substring(from, to));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The sine the server's table holds for an angle, shifted by a number of its steps. */
    private static double tableSine(float radians, double shift) {
        long step = (long) (radians * SINE_STEPS_PER_RADIAN + shift) & SINE_STEP_MASK;
        return (float) Math.sin(step / SINE_STEPS_PER_RADIAN);
    }

    /** Whether a ravager roars right now - Paper says so, Spigot does not. */
    private static boolean roaring(Ravager ravager) {
        if (ROAR_TICKS == null) {
            return false;
        }
        try {
            return ROAR_TICKS.invoke(ravager) instanceof Integer tick && tick == ROAR_TICK;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static Method roarTicks() {
        try {
            return Ravager.class.getMethod("getRoarTicks");
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    /** The value of one of the entity's attributes, or the game's default without it. */
    private static double attributeValue(LivingEntity entity, Attribute attribute, double fallback) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }
}
