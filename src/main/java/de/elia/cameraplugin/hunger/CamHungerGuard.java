package de.elia.cameraplugin.hunger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the hunger of a camera player where it was when he started.
 *
 * <p>The camera player stays in adventure mode, and there the hunger of the
 * server keeps running: an effect, a heal, a flight through water - all of it
 * fills his exhaustion, and every four points of that cost a point of
 * saturation and, once that is used up, a haunch off the bar. He is only
 * watching, though, and pays for nothing else either: his body stands in for
 * him and the damage never reaches him. The hunger is treated the same way.</p>
 *
 * <p>Creative mode gets out of it by being invulnerable, which is not open
 * here - the player is meant to stay in adventure mode. So the counter the
 * whole hunger system runs on is stopped instead: a camera player gains no
 * exhaustion, therefore nothing is ever taken from his saturation or his bar.
 * Below that sits {@link FoodLevelChangeEvent} as the backstop for anything
 * that would reach the bar past the exhaustion. What he brought with him is
 * written down at the start and put back at the end, so camera mode hands him
 * exactly the hunger he came in with.</p>
 *
 * <p>Eating is no concern of this class: every interaction of a camera player
 * is already cancelled and his inventory is empty while he flies.</p>
 */
public class CamHungerGuard implements Listener {

    /** Hunger of the players currently in camera mode, by player id. */
    private final Map<UUID, Hunger> frozen = new HashMap<>();

    public CamHungerGuard(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Freezes the hunger of the player until {@link #stopFor(Player)}. */
    public void startFor(Player player) {
        frozen.putIfAbsent(player.getUniqueId(), new Hunger(
                player.getFoodLevel(), player.getSaturation(), player.getExhaustion()));
    }

    /** Ends the freeze and gives the player the hunger back he started with. */
    public void stopFor(Player player) {
        Hunger hunger = frozen.remove(player.getUniqueId());
        if (hunger == null) {
            return;
        }
        player.setFoodLevel(hunger.foodLevel());
        player.setSaturation(hunger.saturation());
        player.setExhaustion(hunger.exhaustion());
    }

    /** Whether the hunger of that player is currently held in place. */
    private boolean isFrozen(Player player) {
        return frozen.containsKey(player.getUniqueId());
    }

    /**
     * Nothing fills the exhaustion of a camera player. This is where the drain
     * really starts: without exhaustion the food tick of the server finds
     * nothing to take away, from neither the saturation nor the bar.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * The backstop under the exhaustion: whatever else would take a haunch off
     * the bar of a camera player is turned away here. A change upwards is let
     * through - it gives him something, and nothing he is protected from.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || !isFrozen(player)) {
            return;
        }
        if (event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    /** The three values the hunger of a player is made of. */
    private record Hunger(int foodLevel, float saturation, float exhaustion) {
    }
}
