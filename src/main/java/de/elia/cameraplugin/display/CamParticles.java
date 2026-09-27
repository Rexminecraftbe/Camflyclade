package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The particles above a camera player, {@code camera-particles}. */
public final class CamParticles {

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, BukkitRunnable> particleTasks = new HashMap<>();

    public CamParticles(JavaPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    public void startCameraParticles(Player player) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.contains(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                Location particleLoc = player.getLocation().add(0, settings.getParticleHeight(), 0);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!settings.showsOwnParticles() && viewer.equals(player)) continue;
                    if (shouldShowParticlesTo(viewer, particleLoc)) {
                        viewer.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc,
                                settings.getParticlesPerTick(), 0.1, 0.1, 0.1, 0);
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
        particleTasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether this player is shown the particles around a camera player.
     *
     * <p>The world is asked first: a camera player who went through a portal is
     * a world away, and the particles carry only coordinates, so everybody over
     * here would see them floating at the same spot in his own world.</p>
     */
    private boolean shouldShowParticlesTo(Player viewer, Location particleLoc) {
        if (!viewer.getWorld().equals(particleLoc.getWorld())) {
            return false;
        }
        return switch (settings.getPlayerVisibilityMode()) {
            case ALL -> true;
            case CAM -> cameraPlayers.contains(viewer.getUniqueId());
            case NONE -> false;
        };
    }

    public void stopCameraParticles(Player player) {
        BukkitRunnable task = particleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : particleTasks.values()) {
            task.cancel();
        }
        particleTasks.clear();
    }
}
