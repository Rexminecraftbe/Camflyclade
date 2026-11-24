package de.elia.cameraplugin.display;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.camfly2.PluginPart;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.SessionTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The particles above a camera player, {@code camera-particles}. They are
 * for the others, the camera player himself never sees his own.
 */
public final class CamParticles implements PluginPart {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, SessionTask> particleTasks = new HashMap<>();

    public CamParticles(CameraPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Starts the particles for a camera player, unless they are switched off.
     *
     * <p>Switched off means no task at all. Handing the game a count of 0
     * would not do: it reads that as one single particle that flies in the
     * direction of the offsets.</p>
     *
     * <p>A count below one per tick is collected from tick to tick, so that
     * 0.25 sends one particle every fourth tick.</p>
     */
    public void startCameraParticles(Player player) {
        if (!settings.particles().enabled()) {
            return;
        }
        SessionTask task = new SessionTask() {
            /** Particles owed from the ticks before, not yet a whole one. */
            private double owed;

            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                owed += settings.particles().perTick();
                int count = (int) owed;
                if (count == 0) {
                    return;
                }
                owed -= count;
                Location particleLoc = player.getLocation().add(0, settings.particles().height(), 0);
                double spread = settings.particles().spread();
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.equals(player)) continue;
                    if (shouldShowParticlesTo(viewer, player, particleLoc)) {
                        viewer.spawnParticle(settings.particles().type(), particleLoc,
                                count, spread, spread, spread, 0);
                    }
                }
            }
        };
        task.runTaskTimer(plugin.getSessionTicker(), player, 0L, 1L);
        particleTasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether this player is shown the particles around a camera player.
     *
     * <p>The world is asked first: a camera player who went through a portal is
     * a world away, and the particles carry only coordinates, so everybody over
     * here would see them floating at the same spot in his own world.</p>
     *
     * <p>Nobody gets them who does not see the camera player himself: they
     * would show where he is to a viewer he is hidden from - in photo mode,
     * say, or by a vanish plugin. Nor does whoever is on the same tour, at
     * the same spot mostly: they would fly right before his eyes.</p>
     */
    private boolean shouldShowParticlesTo(Player viewer, Player camPlayer, Location particleLoc) {
        if (!viewer.getWorld().equals(particleLoc.getWorld()) || !viewer.canSee(camPlayer)
                || plugin.getTour().hidesCamera(viewer, camPlayer)) {
            return false;
        }
        return switch (settings.cameraMode().visibilityMode()) {
            case ALL -> true;
            case CAM -> cameraPlayers.contains(viewer.getUniqueId());
            case NONE -> false;
        };
    }

    public void stopCameraParticles(Player player) {
        SessionTask task = particleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public void onDisable() {
        for (SessionTask task : particleTasks.values()) {
            task.cancel();
        }
        particleTasks.clear();
    }
}
