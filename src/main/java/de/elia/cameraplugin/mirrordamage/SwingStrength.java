package de.elia.cameraplugin.mirrordamage;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How far a player had drawn back the swing that lands on a body.
 *
 * <p>A player who sprints into a swing knocks harder than with the same swing
 * without the sprint - but only at full strength. The server
 * decides that just before the swing lands and starts drawing back the next
 * one right away: by the time the damage reaches the body, the player's
 * charge is back at nothing, and nothing about the hit tells any more how
 * strong the swing was.</p>
 *
 * <p>Paper announces every swing before it lands, with the charge still
 * standing, and that is where it is read off. Its event is looked up at
 * runtime, like the one in {@link de.elia.cameraplugin.interaction.CamKnockbackGuard}.
 * Spigot announces nothing of the kind. There the strength is read back out
 * of the damage instead - a swing short of full strength deals less of it.</p>
 */
public final class SwingStrength {

    /** Paper's event for a swing that is about to land. */
    private static final String PAPER_EVENT = "io.papermc.paper.event.player.PrePlayerAttackEntityEvent";
    /** What the swing lands on, on Paper's event. */
    private static final String PAPER_TARGET = "getAttacked";
    /** The charge a swing has to be above to count as one at full strength. */
    private static final float FULL_CHARGE = 0.9F;
    /** How close a damage may come to the most a weaker swing deals and still count as weaker. */
    private static final double DAMAGE_TOLERANCE = 1.0E-4;

    private final JavaPlugin plugin;
    private final CameraPlayers cameraPlayers;
    /**
     * The swings at a body that are landing in this tick, by the attacker.
     * Each is read within the same moment it is announced in, so what is
     * written down here is cleared again with the next tick.
     */
    private final Map<UUID, Swing> swings = new HashMap<>();
    /** Whether the server announces its swings, see {@link #register()}. */
    private boolean announced;

    /** One swing: the body it lands on and how far it was drawn back, from 0 to 1. */
    private record Swing(UUID body, float charge) {
    }

    public SwingStrength(JavaPlugin plugin, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.cameraPlayers = cameraPlayers;
    }

    /** Listens to Paper's event, where the server has it. */
    public void register() {
        Class<? extends Event> swingEvent;
        Method target;
        try {
            swingEvent = Class.forName(PAPER_EVENT).asSubclass(Event.class);
            target = swingEvent.getMethod(PAPER_TARGET);
        } catch (ClassNotFoundException | NoSuchMethodException | ClassCastException ex) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvent(swingEvent, new Listener() {
        }, EventPriority.MONITOR, (listener, event) -> {
            if (!swingEvent.isInstance(event)) {
                return;
            }
            try {
                note(((PlayerEvent) event).getPlayer(), (Entity) target.invoke(event));
            } catch (ReflectiveOperationException ex) {
                throw new EventException(ex);
            }
        }, plugin, true);
        announced = true;
    }

    /**
     * Writes down a swing at a body, with the charge it has right now - the
     * same one the server goes by for this swing.
     */
    private void note(Player attacker, Entity target) {
        if (target == null || cameraPlayers.getBodyOrHitboxOwner(target) == null) {
            return;
        }
        if (swings.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, swings::clear);
        }
        swings.put(attacker.getUniqueId(), new Swing(target.getUniqueId(), attacker.getAttackCooldown()));
    }

    /**
     * Whether the damage a player deals the body right now comes from a swing
     * at all, and not from another plugin hurting the body in the player's
     * name. Only a swing pushes harder for the enchantment, the attribute or
     * the sprint. Without Paper's event there is no telling the two apart,
     * and every such hit counts as a swing.
     */
    boolean isSwing(Player attacker, Entity body) {
        return !announced || swingAt(attacker, body) != null;
    }

    /**
     * Whether that swing was one at full strength - the only kind a sprint
     * adds to.
     *
     * @param damage what the swing deals the body, for a server without
     *               Paper's event
     */
    boolean atFullStrength(Player attacker, Entity body, double damage) {
        Swing swing = swingAt(attacker, body);
        if (swing != null) {
            return swing.charge() > FULL_CHARGE;
        }
        return !announced && dealsFullDamage(attacker, damage);
    }

    private Swing swingAt(Player attacker, Entity body) {
        Swing swing = swings.get(attacker.getUniqueId());
        return swing != null && swing.body().equals(body.getUniqueId()) ? swing : null;
    }

    /**
     * Whether the damage is more than a swing just short of full strength
     * deals: the server keeps {@code 0.2 + 0.8 * charge * charge} of what the
     * weapon deals, and of what Sharpness adds the charge itself. Of the
     * enchantments that add to the damage only Sharpness counts against a
     * body - the others are for undead, for arthropods and for what lives in
     * the water. Worked out in the server's own precision, so a swing at
     * exactly that charge comes out exactly at that damage.
     */
    private static boolean dealsFullDamage(Player attacker, double damage) {
        AttributeInstance attribute = attacker.getAttribute(Attribute.ATTACK_DAMAGE);
        float weapon = attribute == null ? 1.0F : (float) attribute.getValue();
        int sharpness = attacker.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SHARPNESS);
        float sharpened = sharpness > 0 ? 1.0F + 0.5F * (sharpness - 1) : 0.0F;
        float weaker = weapon * (0.2F + FULL_CHARGE * FULL_CHARGE * 0.8F) + FULL_CHARGE * sharpened;
        return damage > weaker + DAMAGE_TOLERANCE * Math.max(1.0, weaker);
    }
}
