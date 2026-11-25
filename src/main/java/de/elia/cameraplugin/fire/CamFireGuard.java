package de.elia.cameraplugin.fire;

import de.elia.cameraplugin.camfly2.PluginPart;
import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.session.SessionTask;
import de.elia.cameraplugin.session.SessionTicker;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Simplified fire protection used for the camera mode. It hides nearby fire
 * blocks from the player and prevents burning while camera mode is active.
 */
public class CamFireGuard implements Listener, PluginPart {

    /** How often the player is checked for fire on him and around him, in ticks. */
    private static final long TICK_INTERVAL = 10L;

    /**
     * How far around the player fire is hidden, in blocks: sideways, as a
     * circle, and up and down from his feet. Enough for the fire he could
     * fly into or see flickering in front of his face; every block in reach
     * is looked at on every pass, for every player in camera mode.
     */
    private static final double RADIUS_SIDEWAYS = 1.5;
    private static final int RADIUS_UP = 2;
    private static final int RADIUS_DOWN = 1;
    private static final double RADIUS_SIDEWAYS_SQUARED = RADIUS_SIDEWAYS * RADIUS_SIDEWAYS;
    private static final int RANGE_XZ = (int) Math.ceil(RADIUS_SIDEWAYS);

    private final JavaPlugin plugin;
    /** The tick of each camera mode, which the watching here runs on. */
    private final SessionTicker sessions;

    // configuration values
    private boolean enabled;
    private boolean hideFire;

    private final Map<UUID, SessionTask> tasks = new HashMap<>();
    private final Map<UUID, Set<Block>> hiddenMap = new HashMap<>();

    public CamFireGuard(JavaPlugin plugin, SessionTicker sessions) {
        this.sessions = sessions;
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void loadConfig(ConfigReader config) {
        enabled = config.getBoolean("fireguard.enabled", true);
        hideFire = config.getBoolean("fireguard.hide-fire", true);
    }

    public void startFor(Player player) {
        if (!enabled || tasks.containsKey(player.getUniqueId())) return;

        hiddenMap.put(player.getUniqueId(), new HashSet<>());
        SessionTask task = new SessionTask() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopFor(player);
                    return;
                }
                if (hideFire) {
                    updateHiddenFire(player);
                }
                if (player.getFireTicks() > 0) {
                    player.setFireTicks(0);
                }
            }
        };
        task.runTaskTimer(sessions, player, 0L, TICK_INTERVAL);
        tasks.put(player.getUniqueId(), task);
    }

    /**
     * Stops the protection for the given player. Returns true if the player is
     * currently standing in fire when the guard is removed.
     */
    public boolean stopFor(Player player) {
        UUID id = player.getUniqueId();
        SessionTask task = tasks.remove(id);
        if (task != null) task.cancel();
        restoreAllHidden(player);
        hiddenMap.remove(id);
        return isPlayerInFire(player);
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(tasks.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                stopFor(p);
            }
        }
    }

    private void updateHiddenFire(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

        Set<Block> current = new HashSet<>();
        for (int dx = -RANGE_XZ; dx <= RANGE_XZ; dx++)
            for (int dz = -RANGE_XZ; dz <= RANGE_XZ; dz++) {
                if (dx * dx + dz * dz > RADIUS_SIDEWAYS_SQUARED) continue;
                for (int dy = -RADIUS_DOWN; dy <= RADIUS_UP; dy++) {
                    Block b = w.getBlockAt(
                            loc.getBlockX() + dx,
                            loc.getBlockY() + dy,
                            loc.getBlockZ() + dz);
                    if (isFire(b.getType())) current.add(b);
                }
            }

        Set<Block> previous = hiddenMap.getOrDefault(p.getUniqueId(), Collections.emptySet());

        for (Block b : previous) if (!current.contains(b))
            showAgain(p, b);

        // Always resend the hide packet in case the client was updated by the server
        for (Block b : current)
            p.sendBlockChange(b.getLocation(), Material.AIR.createBlockData());

        hiddenMap.put(p.getUniqueId(), current);
    }

    private void hideFireForBlock(Block block) {
        if (!enabled || !hideFire) return;
        for (UUID id : tasks.keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) continue;
            Location loc = p.getLocation();
            if (!block.getWorld().equals(loc.getWorld())) continue;
            double dx = block.getX() - loc.getBlockX();
            double dz = block.getZ() - loc.getBlockZ();
            if (dx * dx + dz * dz > RADIUS_SIDEWAYS_SQUARED) continue;
            int dy = block.getY() - loc.getBlockY();
            if (dy > RADIUS_UP || dy < -RADIUS_DOWN) continue;
            p.sendBlockChange(block.getLocation(), Material.AIR.createBlockData());
            hiddenMap.computeIfAbsent(id, k -> new HashSet<>()).add(block);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isFire(block.getType())) {
                hideFireForBlock(block);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        if (isFire(event.getNewState().getType())) {
            Bukkit.getScheduler().runTask(plugin, () -> hideFireForBlock(block));
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onEntityCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (tasks.containsKey(player.getUniqueId())) {
                event.setCancelled(true);
                player.setFireTicks(0);
            }
        }
    }

    private void restoreAllHidden(Player p) {
        Set<Block> hidden = hiddenMap.get(p.getUniqueId());
        if (hidden == null) return;
        for (Block b : hidden) {
            showAgain(p, b);
        }
    }

    /**
     * Sends the player a hidden block as it really is - only while he is in
     * its world. The client takes a block change for the world it is in, so
     * after a portal, or back at a body in another world, the fire would turn
     * up at the same spot over there, where there is none. The chunks of the
     * new world come to him as they are anyway.
     */
    private void showAgain(Player p, Block b) {
        if (b.getWorld().equals(p.getWorld())) {
            p.sendBlockChange(b.getLocation(), b.getBlockData());
        }
    }

    private boolean isPlayerInFire(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;
        if (isFire(loc.getBlock().getType())) return true;
        Block head = w.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
        return isFire(head.getType());
    }

    /** Whether the block is fire, the normal kind or the blue one. */
    private static boolean isFire(Material type) {
        return type == Material.FIRE || type == Material.SOUL_FIRE;
    }
}