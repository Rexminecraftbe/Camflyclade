package de.elia.cameraplugin.hunger;

import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;

/**
 * Keeps a camera player from healing by himself, which the frozen hunger of
 * {@link CamHungerGuard} would otherwise make free.
 */
public final class CamRegenGuard implements Listener {

    private final CameraPlayers cameraPlayers;

    public CamRegenGuard(CameraPlayers cameraPlayers) {
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * The camera player does not heal by himself either.
     *
     * <p>Natural regeneration is paid for out of the hunger: outside camera
     * mode every half heart of it costs saturation first and then a haunch off
     * the bar. That bar stands still while he watches - see
     * {@link CamHungerGuard} - so left running it
     * would be free here, and a player could sit his wounds out in the air
     * instead of eating them off on the ground. Both of its reasons are
     * therefore turned away while he is in camera mode: the fast one out of
     * the saturation, and the slow one off the full bar, which is also the one
     * a peaceful world heals with.</p>
     *
     * <p>Only those two. Healing that somebody hands him on purpose - an
     * effect, another plugin - is none of this plugin's business. The one
     * thing still moving his health is the hit his body takes, and that ends
     * camera mode in the same breath.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCameraRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RegainReason reason = event.getRegainReason();
        if (reason != RegainReason.REGEN && reason != RegainReason.SATIATED) {
            return;
        }
        if (cameraPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
