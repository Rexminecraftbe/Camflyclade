package de.elia.cameraplugin.bedrock;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The outline of a camera player for players on Bedrock, which draws no
 * glowing outline: in its place, {@code bedrock.glow-particle} along the
 * edges of his body, for as long as he glows - whenever
 * {@code camera-mode.glowing-outline} shows him to the others, under
 * {@code sight} only while somebody has him in sight.
 *
 * <p>Only for those the adjustments hold for, see
 * {@link BedrockPlayers#adapts}: on Java the outline is there already. And
 * only for whoever sees the camera player at all - in the same world, not
 * hidden from him, not on the same tour -, the same as the particles of
 * {@code camera-particles}, and no further than the particles carry.</p>
 */
public final class BedrockGlow {

    /** How often the outline is drawn, in ticks: twice a second. */
    private static final long INTERVAL = 10L;
    /** How far it is seen, in blocks: as far as the game sends a particle. */
    private static final double RANGE = 32.0;
    /** At which heights of the body the corners are drawn: the feet, the middle, the head. */
    private static final double[] HEIGHTS = {0.0, 0.5, 1.0};

    private final CameraPlugin plugin;
    private BukkitTask task;

    public BedrockGlow(CameraPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::draw, INTERVAL, INTERVAL);
        }
    }

    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Draws the outline of every camera player who glows, for every player on Bedrock who sees him. */
    private void draw() {
        Particle particle = plugin.getSettings().getBedrockGlowParticle();
        if (particle == null) {
            return;
        }
        List<Player> viewers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getBedrock().adapts(player)) {
                viewers.add(player);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        for (UUID id : plugin.getCameraPlayers().ids()) {
            Player camera = Bukkit.getPlayer(id);
            if (camera == null || !camera.isGlowing()) {
                continue;
            }
            BoundingBox box = camera.getBoundingBox();
            for (Player viewer : viewers) {
                if (sees(viewer, camera)) {
                    outline(viewer, particle, box);
                }
            }
        }
    }

    private boolean sees(Player viewer, Player camera) {
        return !viewer.equals(camera) && viewer.getWorld().equals(camera.getWorld()) && viewer.canSee(camera)
                && !plugin.getTour().hidesCamera(viewer, camera)
                && viewer.getLocation().distanceSquared(camera.getLocation()) <= RANGE * RANGE;
    }

    /** One particle at each of the four upright edges of the body, at the feet, in the middle and at the head. */
    private static void outline(Player viewer, Particle particle, BoundingBox box) {
        for (double share : HEIGHTS) {
            double y = box.getMinY() + box.getHeight() * share;
            viewer.spawnParticle(particle, box.getMinX(), y, box.getMinZ(), 1, 0.0, 0.0, 0.0, 0.0);
            viewer.spawnParticle(particle, box.getMaxX(), y, box.getMinZ(), 1, 0.0, 0.0, 0.0, 0.0);
            viewer.spawnParticle(particle, box.getMinX(), y, box.getMaxZ(), 1, 0.0, 0.0, 0.0, 0.0);
            viewer.spawnParticle(particle, box.getMaxX(), y, box.getMaxZ(), 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
