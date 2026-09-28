package de.elia.cameraplugin.interaction;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityKnockbackByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Nothing the camera player hits is knocked away.
 *
 * <p>Nearly every hit ends in {@link CamInteractionGuard#onPlayerAttack} before
 * it can push anything: the damage is turned away there, and the push only
 * ever comes with the damage. A sulfur cube that has swallowed a block is the
 * exception - a fist does it no harm at all, so the game knocks it away without
 * asking about damage first. What is left to turn away is the push itself, and
 * with it the extra push of a sprinting hit.</p>
 *
 * <p>Walking into such a cube pushes it as well, and that push comes without
 * any event; it is held off by
 * {@link de.elia.cameraplugin.sulfurcube.CamSulfurCubeGuard}.</p>
 *
 * <p>The event for the push differs between servers. Paper fires one of its
 * own and keeps Bukkit's {@code EntityKnockbackByEntityEvent} only as a copy
 * that is marked for removal - and warns in the log about every plugin that
 * still listens to it. Spigot has only Bukkit's. So Paper's event is looked up
 * at runtime, like the setters in {@link de.elia.cameraplugin.body.MannequinLabel},
 * and Bukkit's is taken only where there is none.</p>
 */
public final class CamKnockbackGuard {

    /** Paper's event for a push that an attack hands out. */
    private static final String PAPER_EVENT = "io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent";
    /** Who pushed, on Paper's event. */
    private static final String PAPER_PUSHER = "getPushedBy";

    private final JavaPlugin plugin;
    private final CameraPlayers cameraPlayers;

    public CamKnockbackGuard(JavaPlugin plugin, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.cameraPlayers = cameraPlayers;
    }

    /** Hands the check to the server, on the event it offers. */
    public void register() {
        if (!registerPaperEvent()) {
            plugin.getServer().getPluginManager().registerEvents(new BukkitKnockback(), plugin);
        }
    }

    /**
     * Listens to Paper's event, where the server has it.
     *
     * @return whether it is there
     */
    private boolean registerPaperEvent() {
        Class<? extends Event> pushEvent;
        Method pusher;
        try {
            pushEvent = Class.forName(PAPER_EVENT).asSubclass(Event.class);
            pusher = pushEvent.getMethod(PAPER_PUSHER);
        } catch (ClassNotFoundException | NoSuchMethodException | ClassCastException ex) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvent(pushEvent, new Listener() {
        }, EventPriority.NORMAL, (listener, event) -> {
            // The event shares its list of listeners with every other
            // knockback, so the server hands those in here as well.
            if (!pushEvent.isInstance(event)) {
                return;
            }
            try {
                turnAway((Cancellable) event, (Entity) pusher.invoke(event));
            } catch (ReflectiveOperationException ex) {
                throw new EventException(ex);
            }
        }, plugin, true);
        return true;
    }

    private void turnAway(Cancellable push, Entity pusher) {
        if (pusher instanceof Player player && cameraPlayers.contains(player.getUniqueId())) {
            push.setCancelled(true);
        }
    }

    /**
     * Bukkit's event, for a server without Paper's. A class of its own, so a
     * Paper server never loads it.
     */
    private final class BukkitKnockback implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onKnockback(EntityKnockbackByEntityEvent event) {
            turnAway(event, event.getSourceEntity());
        }
    }
}
