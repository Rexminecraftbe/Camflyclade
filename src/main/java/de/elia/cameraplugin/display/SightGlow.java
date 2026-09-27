package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The glowing outline of {@code camera-mode.glowing-outline: sight}. */
public final class SightGlow {

    /**
     * How often {@code glowing-outline: sight} checks whether somebody has the
     * camera player in sight, in ticks.
     */
    private static final long SIGHT_GLOW_INTERVAL = 5L;

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, BukkitRunnable> sightGlowTasks = new HashMap<>();

    public SightGlow(JavaPlugin plugin, CamSettings settings, CameraPlayers cameraPlayers) {
        this.plugin = plugin;
        this.settings = settings;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Outline for {@code glowing-outline: sight}: the camera player only glows
     * while at least one other player looks at him without a block in between.
     *
     * <p>Glowing is a single flag on the entity and every client draws it
     * through walls, so no viewer can be left out of it. Once one player has
     * him in sight, the ones behind a wall see the outline as well. What this
     * does prevent is the outline giving him away while nobody sees him.</p>
     */
    public void startSightGlow(Player player) {
        if (settings.getGlowMode() != GlowMode.SIGHT || settings.getPlayerVisibilityMode() == VisibilityMode.NONE) {
            return;
        }
        stopSightGlow(player);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                CameraData data = cameraPlayers.get(player.getUniqueId());
                if (data == null || !player.isOnline()) {
                    this.cancel();
                    sightGlowTasks.remove(player.getUniqueId(), this);
                    return;
                }
                // A glow he already had before camera mode is left alone.
                boolean glowing = data.getOriginalGlowing() || isInSightOfAnyone(player);
                if (player.isGlowing() != glowing) {
                    player.setGlowing(glowing);
                }
            }
        };
        task.runTaskTimer(plugin, 0L, SIGHT_GLOW_INTERVAL);
        sightGlowTasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether another player has the camera player in sight right now. Only
     * players he is shown to count, which leaves out everybody outside camera
     * mode in mode CAM. Spectators do not count either: they are not part of
     * the game and would switch the outline on for everybody else.
     */
    private boolean isInSightOfAnyone(Player camPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(camPlayer)
                    || viewer.getGameMode() == GameMode.SPECTATOR
                    || !viewer.getWorld().equals(camPlayer.getWorld())
                    || !viewer.canSee(camPlayer)) {
                continue;
            }
            // Eye to eye, the same check hostile mobs use to spot a player.
            if (viewer.hasLineOfSight(camPlayer)) {
                return true;
            }
        }
        return false;
    }

    public void stopSightGlow(Player player) {
        BukkitRunnable task = sightGlowTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void onDisable() {
        for (BukkitRunnable task : sightGlowTasks.values()) {
            task.cancel();
        }
        sightGlowTasks.clear();
    }
}
