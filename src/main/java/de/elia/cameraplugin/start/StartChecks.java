package de.elia.cameraplugin.start;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.FlightMedium;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a player may start camera mode right now: not straight after a hit -
 * taken, or with {@code cam-safety.after-attack} handed out -, not with another
 * player close by while {@code cam-safety.nearby-players} is on, not as a
 * spectator, not with the wrong effects on him, not in a forbidden area, not
 * in lava, water or powder snow that camera mode is kept out of and not in the
 * middle of a fall.
 */
public final class StartChecks implements Listener {

    /** Straight down, the way a falling player goes. */
    private static final Vector DOWN = new Vector(0, -1, 0);
    /**
     * How far a player falls without harm when his attribute cannot be read:
     * the game's own value.
     */
    private static final double DEFAULT_SAFE_FALL_DISTANCE = 3.0;

    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    /** The last hit each player took, or handed out where {@code after-attack} counts it. */
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();

    public StartChecks(CamSettings settings, Messages messages, CameraPlayers cameraPlayers) {
        this.settings = settings;
        this.messages = messages;
        this.cameraPlayers = cameraPlayers;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLastDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Notes a hit a player hands out, as {@code cam-safety.after-attack} has
     * it, so that nobody slips into camera mode in the middle of a fight: by
     * his own hand or by a projectile of his, on another player, a hostile
     * mob or something friendly - each with a switch of its own, see
     * {@link #counts(Entity, UUID)}.
     *
     * <p>The body of a camera player counts as that player. A hit on it is
     * always cancelled - the plugin hands it on to him, or with
     * {@code mirror-damage.damage-mode: false} not even that - so for a body a
     * cancelled hit counts as well. For anything else it does not: the hit
     * did not happen, something such as a protected area held it back.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void recordLastAttack(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        Entity victim = event.getEntity();
        if (attacker == null || !(victim instanceof LivingEntity)) {
            return;
        }
        UUID owner = cameraPlayers.getBodyOrHitboxOwner(victim);
        if (attacker.equals(victim) || attacker.getUniqueId().equals(owner)
                || (event.isCancelled() && owner == null)) {
            return;
        }
        if (counts(victim, owner)) {
            lastDamageTimes.put(attacker.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Whether a hit on this entity makes the attacker wait, after the three
     * switches of {@code cam-safety.after-attack}:
     *
     * <ul>
     *   <li>{@code players}: another player, or the body of one in camera
     *       mode</li>
     *   <li>{@code mobs}: a hostile mob, a zombie, a creeper, a piglin</li>
     *   <li>{@code friendly}: everything else alive - animals, villagers,
     *       golems -, and the NPCs of other plugins such as Citizens, which
     *       are players to the server and mark themselves as {@code NPC}</li>
     * </ul>
     *
     * <p>Armour stands and mannequins standing around for show are nobody to
     * fight with.</p>
     *
     * @param owner the camera player whose body it is, or {@code null}
     */
    private boolean counts(Entity victim, UUID owner) {
        if (owner != null) {
            return settings.waitsAfterHittingPlayers();
        }
        if (victim instanceof Player) {
            return victim.hasMetadata("NPC") ? settings.waitsAfterHittingFriendly()
                    : settings.waitsAfterHittingPlayers();
        }
        if (victim instanceof Enemy) {
            return settings.waitsAfterHittingMobs();
        }
        return settings.waitsAfterHittingFriendly() && !(victim instanceof ArmorStand)
                && !(victim instanceof Mannequin);
    }

    /** The player behind a hit: the one who struck it, or who shot or threw what struck. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** Forgets the last hit of a player who left the server. */
    public void forget(UUID playerId) {
        lastDamageTimes.remove(playerId);
    }

    public boolean checkCamSafety(Player player) {
        if (!settings.isCamSafetyEnabled()) return true;
        Long last = lastDamageTimes.get(player.getUniqueId());
        if (last == null) return true;
        long elapsed = System.currentTimeMillis() - last;
        long delayMillis = settings.getCamSafetyDelay() * 1000L;
        if (elapsed >= delayMillis) return true;
        long remaining = (delayMillis - elapsed + 999) / 1000;
        String msg = messages.getMessage("cam-safety").replace("%seconds%", String.valueOf(remaining));
        if (messages.isMessageEnabled("cam-safety")) {
            player.sendMessage(ChatColor.RED + msg);
        }
        return false;
    }

    /**
     * Checks whether another player stands close by, as
     * {@code cam-safety.nearby-players} and {@code nearby-radius} have it.
     * With somebody right next to him, camera mode would be the way out of a
     * fight before its first blow - the wait after a hit only comes once a
     * blow has fallen.
     *
     * <p>Counted is whoever he can see and could fight: nobody in camera mode,
     * no spectator, no NPC of another plugin such as Citizens, and nobody a
     * vanish plugin hides from him - he could not tell what turns him
     * away.</p>
     *
     * <p>The message tells him how far he has to get from the nearest of
     * them: {@code nearby-radius} in all, and how many blocks more that is.
     * Exactly that far away he may start.</p>
     *
     * @return whether he may start; if not, he has been told whom to get away
     *         from, and how far
     */
    public boolean checkCamNearby(Player player) {
        if (!settings.locksNearPlayers()) {
            return true;
        }
        double radius = settings.getCamSafetyNearbyRadius();
        Location at = player.getLocation();
        Player nearest = null;
        double nearestSquared = radius * radius;
        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player) || other.getGameMode() == GameMode.SPECTATOR
                    || cameraPlayers.contains(other.getUniqueId()) || other.hasMetadata("NPC")
                    || !player.canSee(other)) {
                continue;
            }
            double squared = other.getLocation().distanceSquared(at);
            if (squared < nearestSquared) {
                nearest = other;
                nearestSquared = squared;
            }
        }
        if (nearest == null) {
            return true;
        }
        double distance = Math.sqrt(nearestSquared);
        // Rounded up: a step short of the radius is still too close.
        long more = Math.max(1L, (long) Math.ceil(radius - distance));
        messages.sendMessage(player, "cam-nearby-player", "{player}", nearest.getName(),
                "{radius}", blocks(radius), "{more}", String.valueOf(more),
                "{distance}", String.valueOf(Math.round(distance)));
        return false;
    }

    /** A number of blocks as the file has it, without ".0" behind a whole one. */
    private static String blocks(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Checks whether the player may start camera mode where he is: out of the
     * spectator mode he may not, whatever {@code camera-mode.gamemode} says.
     *
     * <p>On {@code keep} he would stay a spectator, and that is the mode
     * camera mode has no answer to: he passes through blocks, and that walks
     * straight through {@code max-distance}, through the areas of
     * {@code cam-area} and through the portal rules, which all measure a
     * player who has to fly around a wall. With one click he also puts himself
     * next to any entity on the server. Nobody outside the spectator mode sees
     * him either, so the body left behind, the glowing outline and the
     * particles would say nothing about where he is.</p>
     *
     * <p>The three fixed modes would take him out of the spectator mode, and
     * camera mode used to do exactly that. He is turned away there as well: a
     * spectator is watching already and is not where his body would be put
     * down, so starting camera mode would drop a body at a spot he only flew
     * past and hand him back a mode he did not ask for when he leaves.</p>
     *
     * @return whether he may start; if not, he has been told why
     */
    public boolean checkCamSpectator(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return true;
        }
        messages.sendMessage(player, "cam-spectator-start");
        return false;
    }

    /**
     * Checks whether the player may start camera mode with the effects he is
     * carrying, as {@code camera-mode.start-with-effects} has it.
     *
     * <p>There is something to keep him from here: camera mode takes his
     * effects off him and gives them back when he leaves, so a poisoned player
     * could sit his poison out up there for as long as he likes. On
     * {@code positive} only that kind stands in his way - what does him no
     * harm, a beneficial effect or a neutral one like glowing, lets him
     * through.</p>
     *
     * <p>The message names every effect he is turned away over, not just the
     * first: being sent back three times in a row, once per effect, tells him
     * no more than being told all three at once.</p>
     *
     * @return whether he may start; if not, he has been told why
     */
    public boolean checkCamEffects(Player player) {
        if (settings.getStartWithEffects() == EffectStart.ANY) {
            return true;
        }
        List<String> blocking = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            if (settings.getStartWithEffects() == EffectStart.POSITIVE
                    && type.getCategory() != PotionEffectTypeCategory.HARMFUL) {
                continue;
            }
            blocking.add(messages.getName("effect-names", type.getKey()));
        }
        if (blocking.isEmpty()) {
            return true;
        }
        messages.sendMessage(player, "cam-effect-start", "{effect}", String.join(", ", blocking));
        return false;
    }

    /**
     * Checks whether camera mode may be started where the player is standing,
     * as long as {@code cam-area.level} is not 0.
     *
     * <p>Which areas those are is decided by
     * {@link de.elia.cameraplugin.area.CamAreaRules}: the world, by its name
     * and by the dimension it belongs to, the biome the player stands in and
     * the structure around him.</p>
     *
     * @return {@code true} when he may start, otherwise {@code false} and he
     *         has been told why
     */
    public boolean checkCamArea(Player player) {
        if (!settings.getCamAreaRules().getLevel().blocksStart()) {
            return true;
        }
        String area = settings.getCamAreaRules().forbiddenArea(player.getLocation());
        if (area == null) {
            return true;
        }
        messages.sendMessage(player, "cam-area-start", "{area}", area);
        return false;
    }

    /**
     * Checks whether camera mode may be started where the player is: not in
     * lava, water or powder snow while its switch under {@code camera-mode} -
     * {@code allow_lava_flight}, {@code allow_water_flight},
     * {@code allow_powder_snow_flight} - is off.
     *
     * <p>In flight the camera does not get in there, see
     * {@link de.elia.cameraplugin.movement.CamMovementGuard}. Started inside,
     * it would already be where the switch keeps it out of - and could only
     * get out, as every further block of it is shut.</p>
     *
     * @return whether the player may start; if not, the player has been told
     *         why
     */
    public boolean checkCamMedium(Player player) {
        FlightMedium medium = FlightMedium.reachedInto(player, settings, null, player.getLocation());
        if (medium == null) {
            return true;
        }
        messages.sendMessage(player, medium.getStartMessage());
        return false;
    }

    /**
     * Checks whether the player may start camera mode where he is in the air:
     * not in the middle of a fall that can still hurt him.
     *
     * <p>Camera mode would take that fall off him. He flies from the moment it
     * starts, and his body sets out on a fall of its own from the spot it is
     * put down at, from a standstill and without the way he has fallen so far
     * - on {@code body.movement-sensitivity: 0} it does not fall at all.
     * Started just above the ground after a long fall, camera mode would cost
     * him nothing; started higher up, it ends with him put back where his
     * body is, in the air, with the rest of the fall ahead of him and nothing
     * of what came before it.</p>
     *
     * <p>A jump or a step down does not count: what he has fallen so far and
     * what is left down to the ground stay within the distance the game lets
     * him fall without harm, and his body lands just as unharmed.</p>
     *
     * @return whether he may start; if not, he has been told why
     */
    public boolean checkCamFalling(Player player) {
        if (!isFallingIntoHarm(player)) {
            return true;
        }
        messages.sendMessage(player, "cam-falling-start");
        return false;
    }

    /**
     * Whether the player is in a fall that can still hurt him.
     *
     * <p>Whoever may fly takes no fall damage at all, whoever rides is carried
     * and whoever climbs a ladder or a vine is held by it.</p>
     */
    @SuppressWarnings("deprecation")
    private boolean isFallingIntoHarm(Player player) {
        if (player.getAllowFlight() || player.isInsideVehicle() || player.isClimbing()) {
            return false;
        }
        // His client says whether he stands, and the game takes the fall
        // damage at the landing that same client reports - whoever fakes the
        // one fakes the other, camera mode or not. Asked first because he can
        // stand on the edge of a block with his middle over the air.
        if (player.isOnGround()) {
            return false;
        }
        double harmless = safeFallDistance(player) - player.getFallDistance();
        return harmless <= 0 || !landsWithin(player, harmless);
    }

    /**
     * Whether something below the middle of the player stops his fall within
     * the given distance: the ground, or water or lava, which catch it as
     * well. A player already in water or lava is caught right where he is.
     *
     * <p>Only the middle is looked at. Whatever lies below just one side of
     * him may be an edge he is falling past, and then he would be let through
     * with the whole fall still ahead of him.</p>
     */
    private static boolean landsWithin(Player player, double distance) {
        return player.getWorld().rayTraceBlocks(player.getLocation(), DOWN, distance,
                FluidCollisionMode.ALWAYS, true) != null;
    }

    /**
     * How far the player falls without harm, his attribute
     * {@code safe_fall_distance}.
     */
    private static double safeFallDistance(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        return attribute == null ? DEFAULT_SAFE_FALL_DISTANCE : attribute.getValue();
    }
}
