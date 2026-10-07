package de.elia.cameraplugin.start;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.movement.FlightMedium;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
 * Whether a player may start camera mode right now: not straight after a hit,
 * not as a spectator, not with the wrong effects on him, not in a forbidden
 * area, not in lava, water or powder snow that camera mode is kept out of and
 * not in the middle of a fall.
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
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();

    public StartChecks(CamSettings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLastDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
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
     * {@link de.elia.cameraplugin.area.CamAreaRules}: the dimension the world
     * belongs to and the biome the player stands in.</p>
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
