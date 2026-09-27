package de.elia.cameraplugin.mob;

import de.elia.cameraplugin.body.MobHeads;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which mobs go for a camera player, after {@code body.mob-target}: none of
 * them go for him, and his body takes his place as far as a mob would have
 * noticed him.
 */
public final class MobTargeting implements Listener {

    /**
     * How often {@code body.mob-target} looks around the body for hostile mobs
     * to send after it, in ticks.
     */
    private static final long MOB_TARGET_INTERVAL = 20L;

    /**
     * The range of a mob that carries no follow range attribute, in blocks.
     * That is what most mobs have in vanilla.
     */
    private static final double DEFAULT_FOLLOW_RANGE = 16.0;

    /**
     * How far the search around the body reaches at most, in blocks. No mob in
     * vanilla notices anything from further away, so looking further would only
     * cost time. The same reach decides which mobs are handed over when camera
     * mode starts and ends.
     */
    private static final double MAX_MOB_TARGET_RANGE = 64.0;

    /** What a mob head on the body leaves of the range of that kind of mob. */
    private static final double MOB_HEAD_SIGHT_FACTOR = 0.5;

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, BukkitRunnable> mobTargetTasks = new HashMap<>();

    public MobTargeting(JavaPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Takes the aggro away from a player who has just started camera mode:
     * onto his body, or nowhere at all.
     *
     * @param damageTarget the mannequin that takes the hits, see
     *                     {@link CameraData#getDamageTarget()}
     */
    public void turnMobsFromPlayer(Player player, LivingEntity damageTarget) {
        for (Entity entity : player.getNearbyEntities(MAX_MOB_TARGET_RANGE, MAX_MOB_TARGET_RANGE, MAX_MOB_TARGET_RANGE)) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                // Aggro away from the player: onto his body, or nowhere at all -
                // when the body is out of the reach of that mob, and in the mode
                // off, where nobody is handed the body at all.
                boolean toBody = settings.getMobTargetMode().attractsMobs() && noticesBody(mob, damageTarget);
                mob.setTarget(toBody ? damageTarget : null);
            }
        }
    }

    /**
     * Hands the aggro back to the player when camera mode has ended: every mob
     * that was after his body or his hitbox goes for him again.
     */
    public void turnMobsBackToPlayer(Player player, LivingEntity body, Mannequin hitbox) {
        for (Entity entity : body.getNearbyEntities(MAX_MOB_TARGET_RANGE, MAX_MOB_TARGET_RANGE, MAX_MOB_TARGET_RANGE)) {
            if (entity instanceof Mob mob
                    && (body.equals(mob.getTarget()) || (hitbox != null && hitbox.equals(mob.getTarget())))) {
                mob.setTarget(player);
            }
        }
    }

    /**
     * Sends the hostile mobs around the body after it, as long as
     * {@code body.mob-target} names a mode that does so.
     *
     * <p>Without it the body stands there untouched: no mob picks a mannequin
     * as its target by itself. Only the ones that were already after the player
     * follow his body, and they leave it again as soon as something else
     * catches their eye. With a mode set the body stands in for the player here
     * as well - what would have come for him comes for it, as far as it would
     * have noticed him, see {@link #sightRangeFor(Mob, LivingEntity)}.</p>
     *
     * <p>The target is set again and again, not once: a mob works out its
     * target anew every so often and would drop a target it did not pick
     * itself.</p>
     *
     * @param damageTarget the mannequin that takes the hits, see
     *                     {@link CameraData#getDamageTarget()}
     */
    public void startMobTargeting(Player player, LivingEntity damageTarget) {
        if (!settings.getMobTargetMode().attractsMobs() || searchRadius() <= 0.0) {
            return;
        }
        stopMobTargeting(player);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()
                        || damageTarget.isDead()) {
                    this.cancel();
                    mobTargetTasks.remove(player.getUniqueId(), this);
                    return;
                }
                sendMobsAfterBody(player, damageTarget);
            }
        };
        task.runTaskTimer(plugin, 0L, MOB_TARGET_INTERVAL);
        mobTargetTasks.put(player.getUniqueId(), task);
    }

    public void stopMobTargeting(Player player) {
        BukkitRunnable task = mobTargetTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * One pass of {@link #startMobTargeting(Player, LivingEntity)}: the hostile
     * mobs that notice the body and can see it get it as their target.
     *
     * <p>Noticing it is the point of the range: without it every mob around
     * would set off, including the ones standing behind a wall, in a cave below
     * or on the other side of a hill. On top of the range comes the same look a
     * mob takes at a player - eye to eye, without a block in between.</p>
     *
     * <p>A mob that is busy with somebody else keeps the target it has - that
     * fight is not ours to take away. One that is already after the body is
     * left alone too: it is on its way, and losing sight of it on that way is
     * its own business, exactly as it would be while chasing a player. Left out
     * on purpose: the warden, which
     * {@link #onWardenTarget(EntityTargetLivingEntityEvent)} keeps off the body
     * and off the camera player alike, and a mob whose AI is switched off.</p>
     */
    private void sendMobsAfterBody(Player player, LivingEntity damageTarget) {
        double radius = searchRadius();
        for (Entity entity : damageTarget.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Mob mob) || !(entity instanceof Enemy) || mob instanceof Warden) {
                continue;
            }
            if (!mob.isAware()) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (damageTarget.equals(current)) {
                continue;
            }
            if (player.equals(current)) {
                // Beating on the camera player gains nothing, he takes no
                // damage. Either the body takes his place right below, or the
                // mob is left without a target.
                mob.setTarget(null);
                current = null;
            }
            if (current != null && !current.isDead()) {
                continue;
            }
            if (!noticesBody(mob, damageTarget) || !mob.hasLineOfSight(damageTarget)) {
                continue;
            }
            mob.setTarget(damageTarget);
        }
    }

    /**
     * How far the search around the body looks, in blocks. The mode
     * {@code custom} has one range for everybody, {@code vanilla} has to look as
     * far as the mob with the longest reach and sorts the rest out mob by mob.
     */
    private double searchRadius() {
        return settings.getMobTargetMode() == MobTargetMode.CUSTOM ? settings.getMobTargetRadius() : MAX_MOB_TARGET_RANGE;
    }

    /**
     * How far this mob may notice the body, in blocks.
     *
     * <p>In the mode {@code custom} that is the configured radius, the same one
     * for every mob. In {@code vanilla} it is the range the mob brings with it:
     * its follow range, the 16 blocks of a zombie, the 48 of a blaze. Read off
     * the mob itself and not from a list here, so it fits every mob - the ones
     * a later version adds and the ones another plugin hands a range of its own
     * included.</p>
     *
     * <p>A mob head on the body halves it, the way it halves the distance at
     * which that kind of mob notices a player wearing it. That part is switched
     * by {@code body.mob-target-heads}.</p>
     *
     * <p>The mode {@code false} never asks: nothing is sent to the body there
     * and nothing is handed over to it either.</p>
     */
    private double sightRangeFor(Mob mob, LivingEntity body) {
        double range = settings.getMobTargetMode() == MobTargetMode.CUSTOM ? settings.getMobTargetRadius() : vanillaFollowRange(mob);
        if (settings.isMobTargetHeads() && MobHeads.matches(body.getEquipment(), mob.getType())) {
            range *= MOB_HEAD_SIGHT_FACTOR;
        }
        return range;
    }

    /** The distance at which this mob notices a player, out of its own attributes. */
    private double vanillaFollowRange(Mob mob) {
        AttributeInstance followRange = mob.getAttribute(Attribute.FOLLOW_RANGE);
        return followRange != null ? followRange.getValue() : DEFAULT_FOLLOW_RANGE;
    }

    /** Whether the body stands close enough for this mob to go for it. */
    private boolean noticesBody(Mob mob, LivingEntity body) {
        if (body.isDead() || !mob.getWorld().equals(body.getWorld())) {
            return false;
        }
        double range = sightRangeFor(mob, body);
        return range > 0.0 && mob.getLocation().distanceSquared(body.getLocation()) <= range * range;
    }

    /**
     * Keeps mobs off the camera player. He takes no damage while his body
     * stands in for him, so a mob after him beats on nothing at all - and one
     * that shoots, a blaze or a ghast, keeps firing at him from far away
     * without ever being able to hit him.
     *
     * <p>In the modes {@code vanilla} and {@code custom} his body takes his
     * place, as long as it stands within the range this mob has, see
     * {@link #sightRangeFor(Mob, LivingEntity)}. Is it further away - the
     * player flies on, his body stays behind - then the mob gets no target
     * rather than one it can never reach.</p>
     *
     * <p>In the mode {@code false} nothing is handed over: whoever takes aim at
     * the camera player loses his target and stays where he is. Otherwise
     * flying past a zombie would be enough to send it off to the body, which is
     * exactly what that mode is meant to prevent. The body itself is refused
     * there as well, so that mode holds even where a mob would pick a mannequin
     * on its own.</p>
     */
    @EventHandler
    public void onMobTarget(EntityTargetEvent event) {
        if (!(event.getTarget() instanceof Player player)) {
            // In the mode false the body is nobody's target either, not even of
            // a mob that would go for a mannequin by itself.
            if (!settings.getMobTargetMode().attractsMobs() && isActiveBody(event.getTarget())) {
                refuse(event);
            }
            return;
        }
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (settings.getMobTargetMode().attractsMobs() && event.getEntity() instanceof Mob mob
                && noticesBody(mob, data.getDamageTarget())) {
            event.setTarget(data.getDamageTarget());
            return;
        }
        // Cancelled rather than handed an empty target: a mob that is already
        // after the body keeps it that way, only the camera player is refused.
        refuse(event);
    }

    @EventHandler
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden)) return;
        LivingEntity target = event.getTarget();
        boolean camera = target instanceof Player player
                ? cameraPlayers.contains(player.getUniqueId())
                : isActiveBody(target);
        if (camera) {
            refuse(event);
        }
    }

    /** Whether the entity is the body or the hitbox of a player in camera mode right now. */
    private boolean isActiveBody(Entity entity) {
        UUID owner = cameraPlayers.getBodyOrHitboxOwner(entity);
        return owner != null && cameraPlayers.contains(owner);
    }

    /** Leaves the mob without the target it was about to take. */
    private static void refuse(EntityTargetEvent event) {
        event.setCancelled(true);
        event.setTarget(null);
    }
}
