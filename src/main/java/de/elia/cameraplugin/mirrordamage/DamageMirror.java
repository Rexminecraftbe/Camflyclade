package de.elia.cameraplugin.mirrordamage;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Passes the hit the body takes on to the player, the section
 * {@code mirror-damage}, and keeps every other hit off the camera player while
 * his body stands in for him.
 */
public final class DamageMirror implements Listener {

    /**
     * How long a mirrored hit waits at most for the player to live through
     * those ticks, so that it never hangs should the player stop ticking
     * altogether.
     */
    private static final int MAX_ARMOR_WAIT_TICKS = 10;
    /** How many ticks of their own the player lives through before the hit reaches them. */
    private static final int TICKS_BEFORE_HIT = 2;
    /** Paper's event for every push an entity is given, an explosion's among them. */
    private static final String PAPER_PUSH_EVENT = "io.papermc.paper.event.entity.EntityKnockbackEvent";
    /** The cause Paper's event gives the push of an explosion. */
    private static final String EXPLOSION_PUSH = "EXPLOSION";

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** How strong the swings are that land on a body, see {@link HitPush}. */
    private final SwingStrength swingStrength;
    /** The player who is taking the hit his body took right now. */
    private final Set<UUID> damageImmunityBypass = new HashSet<>();
    /** Players whose body was hit and whose hit has not reached them yet, with the push of that hit. */
    private final Map<UUID, HitPush> pendingMirrorHit = new HashMap<>();
    /** Players whose passed-on hit something else turned away, see onMirroredHitTurnedAway. */
    private final Set<UUID> mirroredHitTurnedAway = new HashSet<>();
    /**
     * How far the explosions announced in this tick reach, by the entity going
     * off. The damage of an explosion does not say, see onExplosionPrime.
     */
    private final Map<UUID, Float> explosionRadius = new HashMap<>();

    public DamageMirror(CameraPlugin plugin, SwingStrength swingStrength) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
        this.swingStrength = swingStrength;
    }

    /**
     * Every hit on the body ends camera mode and is passed on to the player.
     * There is no separate check for lava, water or blocks: the body takes that
     * damage itself and the damage event is all that is needed to notice it.
     *
     * <p>Only the raw damage of the hit travels to the player, together with the
     * damage source it came with. The server then reduces it exactly once, on
     * the player: his armour, his enchantments, his resistance and his
     * absorption, in the order and with the rules of the real damage type. What
     * the body wears never counts - it would be a second, wrong reduction, and
     * a damage type that ignores armour (falling, drowning, magic) would lose
     * against armour it never touches in the first place.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBodyDamage(EntityDamageEvent event) {
        Entity damagedEntity = event.getEntity();

        // Checks whether this is one of our bodies or the hitbox belonging to it
        boolean damagedBody = cameraPlayers.isCameraBody(damagedEntity);
        UUID ownerUUID = cameraPlayers.getBodyOrHitboxOwner(damagedEntity);

        if (ownerUUID == null) return; // Not managed by this plugin

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner == null || !owner.isOnline()) {
            // Player offline -> clean up
            if (damagedBody) {
                cameraPlayers.removeBody(damagedEntity.getUniqueId());
            } else {
                cameraPlayers.removeHitbox(damagedEntity.getUniqueId());
            }
            cameraPlayers.remove(ownerUUID);
            plugin.getNoCollisionTeam().deleteNoCollisionTeamIfUnused();
            damagedEntity.remove();
            return;
        }

        // Which of the two entities was hit makes no difference any more: only
        // the raw damage is passed on, and the reduction happens on the player.
        // A hit on the armour stand therefore no longer has to be forwarded to
        // the mannequin standing in it.
        if (owner.isDead()) {
            event.setCancelled(true);
            plugin.exitCameraMode(owner);
            return;
        }

        // The body itself takes no damage, every hit ends camera mode.
        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent selfHit &&
                selfHit.getDamager().getUniqueId().equals(owner.getUniqueId())) {
            messages.sendConfiguredMessage(owner, "camera-off");
            plugin.exitCameraMode(owner);
            return;
        }

        DamageCause cause = event.getCause();

        String damagerName = "environment";
        Entity damagerEntity = null;
        if (event instanceof EntityDamageByEntityEvent entityEvent) {
            damagerEntity = entityEvent.getDamager();
            damagerName = damagerEntity instanceof Player ? damagerEntity.getName() : damagerEntity.getType().toString();
            // The effects of a tipped arrow have reached the player already:
            // CamPotionGuard passes them on when the arrow hits, which comes
            // before this damage.
        }

        double applyDamage;
        switch (settings.getDamageMode()) {
            // The damage the hit started with, before anything reduced it.
            // Armour, armour toughness, protection enchantments, resistance and
            // absorption all belong to the player: the server applies them once,
            // when the hit is passed on to him below. Whatever the body wears
            // does not count, so nothing is subtracted twice and explosions and
            // falling anvils need no special case any more.
            case MIRROR -> applyDamage = event.getDamage();
            case CUSTOM -> {
                applyDamage = settings.getCustomDamageHearts() * 2.0;
                // At zero hearts the mode is meant to cost nothing at all, so
                // no enchantment puts anything on top of it either.
                if (applyDamage > 0 && event instanceof EntityDamageByEntityEvent ede) {
                    Entity dmg = ede.getDamager();
                    if (dmg instanceof LivingEntity attacker) {
                        ItemStack wpn = attacker.getEquipment().getItemInMainHand();
                        int sharp = wpn.getEnchantmentLevel(Enchantment.SHARPNESS);
                        if (sharp > 0) {
                            applyDamage += 1 + sharp; // rough Sharpness formula
                        }
                    } else if (dmg instanceof AbstractArrow arrow) {
                        ProjectileSource src = arrow.getShooter();
                        if (src instanceof LivingEntity attacker) {
                            ItemStack bow = attacker.getEquipment().getItemInMainHand();
                            int power = bow.getEnchantmentLevel(Enchantment.POWER);
                            if (power > 0) {
                                applyDamage += 1 + power; // approximate Power formula
                            }
                        }
                    }
                }
            }
            default -> applyDamage = 0.0;
        }

        // Written down now, while the hit is still exactly what it was when it
        // landed: a moment later the arrow has bounced off the body, the
        // explosion is over and the attacker has turned away.
        HitPush push = HitPush.of(event, standIn(ownerUUID, damagedEntity),
                announcedRadius(event.getDamageSource()), swingStrength);

        plugin.exitCameraMode(owner);

        messages.sendMessage(owner, resolveDamageMessageKey(event, cause),
                "{damager}", damagerName, "{cause}", cause.toString());

        if (settings.isMirrorDebug()) {
            sendMirrorDebug(owner, String.format(Locale.ROOT,
                    "body hit: raw %.3f | after body armour %.3f | %s",
                    event.getDamage(), event.getFinalDamage(), event.getCause()));
        }

        // The hit always reaches the player, whatever the mode passes on of it:
        // the damage, the push, or only the pause it leaves behind. It also
        // keeps the damage source it had - only with it does the server treat
        // it as the fall, the drowning or the arrow it really was, and the
        // source decides whether armour counts at all, which protection
        // enchantment counts, and who gets the kill.
        mirrorHitToPlayer(owner, applyDamage, event.getDamage(), event.getDamageSource(), damagerEntity, push);
    }

    /**
     * The mannequin taking the hits for this player: the one standing in the
     * armour stand of body type 1, otherwise the body itself. It has the
     * player's shape and stands where the player will stand once camera mode
     * is over, so a push worked out on it is the push the player would get.
     */
    private LivingEntity standIn(UUID ownerId, Entity damaged) {
        CameraData data = cameraPlayers.get(ownerId);
        if (data != null) {
            return data.getDamageTarget();
        }
        return (LivingEntity) damaged;
    }

    /** How far the explosion behind this damage reaches, when it was announced. */
    private Float announcedRadius(org.bukkit.damage.DamageSource source) {
        Entity direct = source.getDirectEntity();
        return direct == null ? null : explosionRadius.get(direct.getUniqueId());
    }

    /**
     * Notes down how far an explosion is going to reach. The damage it deals
     * does not say, and the push of an explosion depends on it, see
     * {@link HitPush}.
     *
     * <p>TNT, creepers, fireballs, wither skulls, end crystals, TNT minecarts
     * and the birth of a wither all announce themselves here and go off right
     * after, within the same tick - so what is written down here is cleared
     * again with the next one. Beds and respawn anchors announce nothing, but
     * they always reach just as far.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (explosionRadius.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, explosionRadius::clear);
        }
        explosionRadius.put(event.getEntity().getUniqueId(), event.getRadius());
    }

    /**
     * Hands the hit the body took to the player - as soon as his armour
     * actually protects him again.
     *
     * <p>Camera mode has just put the armour back into his inventory, but that
     * alone does not protect him: the server turns the pieces into attribute
     * modifiers in the player's own tick, and scheduled tasks run before the
     * entities of that tick. A hit passed on one tick later can therefore still
     * find him standing there as if he wore nothing, while waiting longer than
     * needed only gives something else the chance to hit him first.</p>
     *
     * <p>So the hit does not wait for a number of ticks, it waits for the
     * player: as soon as he has lived through one tick since the armour came
     * back, that tick has applied it, and the hit lands with the protection he
     * really has.</p>
     *
     * <p>It waits for one tick more, for the push. The player has just been put
     * back where the body stood, and the client takes them for standing on the
     * ground only once it has moved them there - until then it still has them
     * in the air, where the camera was. A push arriving before that sets out
     * from the air, slides on without the grip of the ground and carries the
     * player further than the same hit does outside camera mode.</p>
     */
    private void mirrorHitToPlayer(Player owner, double amount, double rawDamage,
                                   org.bukkit.damage.DamageSource source, Entity attacker, HitPush push) {
        int restoredAt = owner.getTicksLived();
        // Nothing else may reach him until this hit has landed, see onPlayerDamage.
        pendingMirrorHit.put(owner.getUniqueId(), push);
        new BukkitRunnable() {
            private int waited = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead()) {
                    pendingMirrorHit.remove(owner.getUniqueId());
                    cancel();
                    return;
                }
                if (owner.getTicksLived() - restoredAt < TICKS_BEFORE_HIT && ++waited < MAX_ARMOR_WAIT_TICKS) {
                    return; // his ticks are still to come: the armour, then the ground
                }
                cancel();
                applyMirroredHit(owner, amount, rawDamage, source, attacker, push, waited);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Puts the hit onto the player: once, with his own armour, his own
     * enchantments and his own effects, and with the damage source it came
     * with.
     *
     * <p>His invulnerability is left alone on purpose. Should something else
     * have hit him while this one was on its way, the server counts the two
     * together the way it counts any two hits that land within the same
     * invulnerability - the bigger one wins instead of both being taken. Forcing
     * this hit through would take it on top of the other one, and the player
     * would lose more hearts than the same hit costs outside camera mode.</p>
     *
     * <p>When the mode passes on no damage at all, the hit still arrives: as
     * the push, if the push is switched on, and as the pause it leaves behind
     * either way - and as the wear on the armour, which follows a mode of its
     * own.</p>
     *
     * <p>The push itself is the one written down when the body was hit, see
     * {@link HitPush}: the server would take its direction from what the
     * world looks like now.</p>
     */
    private void applyMirroredHit(Player owner, double amount, double rawDamage,
                                  org.bukkit.damage.DamageSource source, Entity attacker, HitPush push,
                                  int waitedTicks) {
        double speed = owner.getVelocity().length();
        double armor = attributeValue(owner, Attribute.ARMOR);
        double toughness = attributeValue(owner, Attribute.ARMOR_TOUGHNESS);
        double knockbackResistance = attributeValue(owner, Attribute.KNOCKBACK_RESISTANCE);
        double healthBefore = owner.getHealth();
        double absorptionBefore = owner.getAbsorptionAmount();
        int framesBefore = owner.getNoDamageTicks();
        double lastBefore = owner.getLastDamage();
        int fireBefore = owner.getFireTicks();
        EntityDamageEvent causeBefore = owner.getLastDamageCause();
        UUID ownerId = owner.getUniqueId();
        pendingMirrorHit.remove(ownerId);

        if (amount <= 0) {
            // Nothing to pass on - but the hit did happen, it only landed on
            // the body standing in for the player. The pause it leaves behind
            // is his as well: without it the next swing of the attacker, or the
            // fire his body stood in, would reach him in the same moment and
            // take exactly the hearts this mode is meant to save him. The
            // push is his too, as long as it is switched on - the server hands
            // it out only together with damage, so here it has to be handed
            // out by hand, by the same rules.
            Vector pushed = null;
            if (settings.isMirrorKnockback()) {
                pushed = push.standstill(owner);
                if (HitPush.takesHits(owner)) {
                    pushed = push.knock(owner, pushed, true, true);
                }
                pushed = push.blast(owner, pushed);
                handOut(owner, pushed, push.standstill(owner), push);
            }
            owner.setNoDamageTicks(20);
            owner.setLastDamage(rawDamage);
            // The armour follows its own mode: that no hearts are passed on
            // does not mean the hit left the armour alone.
            int wornPoints = wearWornArmor(owner, rawDamage, source);
            reportMirroredHit(owner, amount, source, armor, toughness, knockbackResistance,
                    healthBefore, speed, waitedTicks, framesBefore, lastBefore, fireBefore,
                    pushed, armorNote(false, wornPoints));
            return;
        }

        // The server wears the armour down out of the very damage it deals, and
        // that number is the only one it can use. It is therefore left to do the
        // wear in the one case where its number is the right one anyway: the
        // real hit, mirrored, with the Unbreaking enchantment counting. In every
        // other case the armour is swapped for copies, the server wears those
        // out, and the real pieces get the wear their own mode asks for.
        boolean serverWearsArmor = settings.getArmorDamageMode() == ArmorDamageMode.MIRROR
                && settings.respectsUnbreaking() && amount == rawDamage;
        ItemStack[] saved = null;
        if (!serverWearsArmor) {
            // Copies protect exactly like the originals, so the player takes
            // the same damage - only the copies wear out, and they are thrown
            // away right afterwards. Taking the armour off instead would not
            // work: the protection sits in attribute modifiers the server only
            // refreshes in the entity's own tick, so it would still count here
            // while the durability was already gone.
            saved = owner.getInventory().getArmorContents();
            owner.getInventory().setArmorContents(plugin.getBodySpawner().createMirrorArmor(saved));
            owner.updateInventory();
        }
        // A moment ago the player was flying. That speed must not ride along
        // into the knockback of this hit: outside camera mode he would have
        // been standing where his body stood, and the hit would push him from
        // a standstill - the speed of standing on the ground, which is not
        // quite none.
        Vector standstill = push.standstill(owner);
        owner.setVelocity(standstill);
        damageImmunityBypass.add(ownerId);
        mirroredHitTurnedAway.remove(ownerId);
        try {
            if (source != null) {
                owner.damage(amount, source);
            } else {
                owner.damage(amount, attacker);
            }
        } finally {
            damageImmunityBypass.remove(ownerId);
        }
        // Whether the hit carries a push the server answers by itself: it only
        // moves the player for a hit that really landed, and never for the
        // damage of a fall, of fire or of an explosion.
        boolean knocked = !owner.getVelocity().equals(standstill);
        boolean turnedAway = mirroredHitTurnedAway.remove(ownerId);
        boolean landed = landedInFull(owner, framesBefore, causeBefore);
        if (!settings.damageCountsArmor()) {
            takeWhatTheArmorKeptAway(owner, amount, healthBefore + absorptionBefore, framesBefore);
        }
        Vector pushed = null;
        if (settings.isMirrorKnockback()) {
            // Which way and how hard is taken from the moment the body was hit,
            // not from the server: it would push along the bounce of an arrow.
            // The push of an explosion comes on top - its damage carries none,
            // the explosion hands it out by itself to everything it did not
            // have turned away.
            pushed = push.knock(owner, standstill, knocked, landed);
            if (!turnedAway) {
                pushed = push.blast(owner, pushed);
            }
            handOut(owner, pushed, standstill, push);
        } else {
            // The hit is passed on, the push behind it is not: the server has
            // just turned it into movement, and that movement is taken back
            // before anyone sees it.
            owner.setVelocity(standstill);
        }
        // A dead player keeps nothing of this: what he dropped are the copies,
        // so wearing the originals down would only be heard and seen for a set
        // of pieces that is about to be thrown away.
        int wornPoints = saved == null || owner.isDead() ? 0 : wearArmor(owner, saved, rawDamage, source);
        reportMirroredHit(owner, amount, source, armor, toughness, knockbackResistance,
                healthBefore, speed, waitedTicks, framesBefore, lastBefore, fireBefore,
                pushed, armorNote(serverWearsArmor, wornPoints));
        if (attacker instanceof LivingEntity living) {
            ItemStack weapon = living.getEquipment().getItemInMainHand();
            int fireLevel = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
            if (fireLevel > 0) {
                int ticks = Math.max(owner.getFireTicks(), fireLevel * 80);
                owner.setFireTicks(ticks);
            }
        } else if (attacker instanceof AbstractArrow arr) {
            if (arr.getFireTicks() > 0) {
                int ticks = Math.max(owner.getFireTicks(), 100);
                owner.setFireTicks(ticks);
            }
        }
        if (saved != null && !owner.isDead()) {
            owner.getInventory().setArmorContents(saved);
            owner.updateInventory();
        }
        // Died from the hit: the copies are what he dropped, and they carry the
        // same pieces. Handing the originals back into an inventory the server
        // has just emptied would only put them into the world twice.
    }

    /**
     * Hands the push to the player - in one go, unless the burst of a wind
     * charge comes with the hit. The server sends that burst on its own the
     * moment it goes off, and a tick later, once the hit has pushed as well,
     * the speed of both together; it is sent the same way here. A hit that
     * pushed nothing leaves the burst alone.
     */
    private void handOut(Player owner, Vector pushed, Vector standstill, HitPush push) {
        Vector burst = push.caughtBurst();
        if (burst == null) {
            owner.setVelocity(pushed);
            return;
        }
        owner.setVelocity(standstill.clone().add(burst));
        if (pushed.equals(standstill)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (owner.isOnline() && !owner.isDead()) {
                owner.setVelocity(pushed);
            }
        }, 1L);
    }

    /**
     * Whether the hit passed on landed in full: it got through - nothing
     * turned it away, so it is the player's last damage now -, and not into
     * the invulnerability an earlier hit left behind. Such a later hit only
     * takes what it has beyond the earlier one, starts no new invulnerability
     * of its own, and the server pushes nobody for it. The hits that carry no
     * push of their own, a stab, can only be told apart by that.
     */
    private static boolean landedInFull(Player owner, int framesBefore, EntityDamageEvent causeBefore) {
        int span = owner.getMaximumNoDamageTicks();
        return owner.getLastDamageCause() != causeBefore
                && framesBefore * 2 <= span && owner.getNoDamageTicks() == span;
    }

    /**
     * Wears down the pieces the player has on, for a hit that passes on no
     * damage of its own. Only that one case needs this - everywhere else the
     * armour is off his body already, swapped for the copies the server wears
     * out in its stead.
     */
    private int wearWornArmor(Player owner, double rawDamage, org.bukkit.damage.DamageSource source) {
        if (settings.getArmorDamageMode() == ArmorDamageMode.OFF) {
            return 0;
        }
        ItemStack[] worn = owner.getInventory().getArmorContents();
        int points = wearArmor(owner, worn, rawDamage, source);
        if (points > 0) {
            owner.getInventory().setArmorContents(worn);
            owner.updateInventory();
        }
        return points;
    }

    /**
     * Wears the pieces down after {@code mirror-damage.damage-armor-mode}:
     * after the real hit, after a fixed number of durability points, or not at
     * all.
     *
     * @return the durability points every piece was asked to give up
     */
    private int wearArmor(Player owner, ItemStack[] armor, double rawDamage,
                          org.bukkit.damage.DamageSource source) {
        return switch (settings.getArmorDamageMode()) {
            case MIRROR -> ArmorWear.wearMirrored(owner, armor, rawDamage, source, settings.respectsUnbreaking());
            case CUSTOM -> ArmorWear.wearFixed(owner, armor, settings.getCustomArmorDamage(), source, settings.respectsUnbreaking());
            case OFF -> 0;
        };
    }

    /**
     * Takes off afterwards what the armour kept away, so that the hit costs
     * exactly what it is worth: the hearts "custom" is set to, or the whole of
     * the real hit under "mirror".
     *
     * <p>Only reached with {@code damage-counts-armor} switched off. The hit is
     * still dealt the normal way first, with its damage source, its push and
     * the death message it brings; what the armour, the protection enchantments
     * or an effect took off it is then taken from the player by hand.</p>
     *
     * <p>What this does not touch is the armour itself: how hard the hit wears
     * it down is {@code damage-armor-mode}'s to say alone, so the pieces still
     * take their durability - and on the body they are worn and shown the whole
     * time either way.</p>
     *
     * <p>A hit that does not land at all stays at nothing, though: something
     * cancelled it outright - fire resistance against fire, a protected region -
     * and that is not the armour taking its share. Neither is the
     * invulnerability of a hit the player had just taken, which this one is
     * meant to run into the same way it would outside camera mode.</p>
     */
    private void takeWhatTheArmorKeptAway(Player owner, double target, double poolBefore,
                                          int framesBefore) {
        if (owner.isDead() || framesBefore > 0) {
            return;
        }
        double dealt = poolBefore - (owner.getHealth() + owner.getAbsorptionAmount());
        if (dealt <= 0 || dealt >= target - 1.0E-4) {
            return;
        }
        double missing = target - dealt;
        double absorption = owner.getAbsorptionAmount();
        if (absorption > 0) {
            // Absorption hearts stand in front of the real ones here as well.
            double taken = Math.min(absorption, missing);
            owner.setAbsorptionAmount(absorption - taken);
            missing -= taken;
        }
        if (missing > 0) {
            owner.setHealth(Math.max(0.0, owner.getHealth() - missing));
        }
    }

    /** How the wear on the armour reads in the measuring line. */
    private String armorNote(boolean serverWears, int points) {
        if (serverWears) {
            return "mirror, by the server";
        }
        return switch (settings.getArmorDamageMode()) {
            case MIRROR -> "mirror, " + points + " points";
            case CUSTOM -> "custom, " + points + " points";
            case OFF -> "false";
        };
    }

    /**
     * One line of the measurement, whenever {@code mirror-damage.debug} is on.
     *
     * @param pushed the speed the push left the player with, {@code null}
     *               when the push is switched off
     */
    private void reportMirroredHit(Player owner, double amount, org.bukkit.damage.DamageSource source,
                                   double armor, double toughness, double knockbackResistance,
                                   double healthBefore, double speed, int waitedTicks,
                                   int framesBefore, double lastBefore, int fireBefore,
                                   Vector pushed, String armorNote) {
        if (!settings.isMirrorDebug()) {
            return;
        }
        String knockback = pushed == null ? "off" : String.format(Locale.ROOT, "%.4f %.4f %.4f",
                pushed.getX(), pushed.getY(), pushed.getZ());
        sendMirrorDebug(owner, String.format(Locale.ROOT,
                "mirrored: raw %.3f (%s) | armour %.1f (counts %s), toughness %.1f, KB resistance %.2f"
                        + " | health %.2f -> %.2f (-%.3f) | speed %.3f | waited %d ticks"
                        + " | invulnerable %d, last hit %.2f, fire %d | knockback %s"
                        + " | wear %s",
                amount, damageTypeName(source), armor, settings.damageCountsArmor() ? "on" : "off",
                toughness, knockbackResistance,
                healthBefore, owner.getHealth(), healthBefore - owner.getHealth(), speed, waitedTicks,
                framesBefore, lastBefore, fireBefore, knockback, armorNote));
    }

    /**
     * Picks the message for the hit that ended camera mode. Drowning and
     * suffocation keep their own text, everything else is reported as an attack
     * or as generic environmental damage.
     */
    private String resolveDamageMessageKey(EntityDamageEvent event, DamageCause cause) {
        if (event instanceof EntityDamageByEntityEvent) {
            return "body-attacked";
        }
        return switch (cause) {
            case DROWNING -> "body-drowning";
            case SUFFOCATION -> "body-suffocating";
            default -> "body-env-damage";
        };
    }

    /**
     * Watches for the burst of a wind charge that struck a body, on the event
     * the server offers - Paper's own where there is one, the way
     * {@link de.elia.cameraplugin.interaction.CamKnockbackGuard} picks it,
     * and Bukkit's otherwise. See {@link HitPush#awaitsBurst}.
     */
    public void watchBursts() {
        if (!watchPaperBursts()) {
            plugin.getServer().getPluginManager().registerEvents(new BukkitBursts(), plugin);
        }
    }

    /**
     * Listens to Paper's event for pushes, where the server has it.
     *
     * @return whether it is there
     */
    private boolean watchPaperBursts() {
        Class<? extends Event> pushEvent;
        Method cause;
        Method knockback;
        try {
            pushEvent = Class.forName(PAPER_PUSH_EVENT).asSubclass(Event.class);
            cause = pushEvent.getMethod("getCause");
            knockback = pushEvent.getMethod("getKnockback");
        } catch (ClassNotFoundException | NoSuchMethodException | ClassCastException ex) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvent(pushEvent, new Listener() {
        }, EventPriority.HIGHEST, (listener, event) -> {
            if (!pushEvent.isInstance(event)) {
                return;
            }
            try {
                if (EXPLOSION_PUSH.equals(String.valueOf(cause.invoke(event)))
                        && holdBackBurst(((EntityEvent) event).getEntity(), (Vector) knockback.invoke(event))) {
                    ((Cancellable) event).setCancelled(true);
                }
            } catch (ReflectiveOperationException ex) {
                throw new EventException(ex);
            }
        }, plugin, true);
        return true;
    }

    /**
     * Holds the burst of a wind charge back from the player whose body it
     * struck: it reaches them with the hit, see {@link #handOut}.
     *
     * @return whether it was held back
     */
    private boolean holdBackBurst(Entity entity, Vector burst) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        HitPush pending = pendingMirrorHit.get(player.getUniqueId());
        if (pending == null || !pending.awaitsBurst()) {
            return false;
        }
        pending.catchBurst(burst);
        return true;
    }

    /**
     * Bukkit's event for pushes, for a server without Paper's. A class of its
     * own, so a Paper server never loads it.
     */
    private final class BukkitBursts implements Listener {

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPush(EntityKnockbackEvent event) {
            if (event.getCause() == EntityKnockbackEvent.KnockbackCause.EXPLOSION
                    && holdBackBurst(event.getEntity(), event.getKnockback())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * The camera player takes no damage himself: while camera mode runs his
     * body stands in for him, and in the tick or two between the hit on the
     * body and that hit reaching him nothing else may hit him either.
     *
     * <p>That second part matters more than it looks. The hit on the body came
     * first - without camera mode the player would have taken it right there,
     * and everything reaching him in the next few ticks would have run into the
     * invulnerability of that hit. Letting something hit him while his own hit
     * is still on its way would take it on top instead: the fire his body stood
     * in, or the next swing of the attacker, would cost him hearts that the
     * same situation never costs outside camera mode.</p>
     */
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (damageImmunityBypass.contains(playerId)) {
            return; // his own hit, on its way through
        }
        if (cameraPlayers.contains(playerId) || pendingMirrorHit.containsKey(playerId)) {
            event.setCancelled(true);
        }
    }

    /**
     * Notes down when something else - a protected region, a god mode - turned
     * away the hit passed on to the player. An explosion pushes nobody whose
     * damage was turned away, and the push of an explosion is handed out by
     * hand here, so it has to ask.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMirroredHitTurnedAway(EntityDamageEvent event) {
        if (event.isCancelled() && event.getEntity() instanceof Player player
                && damageImmunityBypass.contains(player.getUniqueId())) {
            mirroredHitTurnedAway.add(player.getUniqueId());
        }
    }

    /** The value of one of the player's attributes, or zero when he has none. */
    private double attributeValue(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    /** The name of the damage type a hit carries, for the measuring output. */
    private String damageTypeName(org.bukkit.damage.DamageSource source) {
        return source == null ? "no source" : source.getDamageType().getKey().toString();
    }

    /**
     * Puts one line of the measurement in front of the player and into the log.
     * Only ever reached while {@code mirror-damage.debug} is switched on.
     */
    private void sendMirrorDebug(Player owner, String line) {
        plugin.getLogger().info("[Damage mirror] " + owner.getName() + ": " + line);
        owner.sendMessage("§e[CamFly] §7" + line);
    }
}
