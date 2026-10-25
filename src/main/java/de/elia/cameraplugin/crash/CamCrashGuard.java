package de.elia.cameraplugin.crash;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.session.CameraData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Keeps what camera mode takes off a player safe from a crash,
 * {@code crash-protection}.
 *
 * <p>While camera mode runs, his things are only in memory, in his
 * {@link CameraData}. A server that goes down without shutting down - killed,
 * out of memory, the power gone - never gives them back, and it may well have
 * saved him in between: with empty pockets, in the air, in the game mode of
 * the camera.</p>
 *
 * <p>So when camera mode starts, the same things go into a file of their own,
 * {@code playerdata/crash/<uuid>.yml} in the folder of the plugin, and the file is
 * deleted when it ends. A file that is still there when he joins means camera
 * mode never ended: he gets everything back from it and stands at his body
 * again. What his body left behind is cleared away by
 * {@link de.elia.cameraplugin.body.BodySpawner#removeLeftoverEntities()}.</p>
 *
 * <p>That costs one small file at the start and one deletion at the end, and
 * nothing while camera mode runs. The file is written under another name first
 * and then moved into place, so a crash in the middle of writing it leaves no
 * half a file behind.</p>
 */
public final class CamCrashGuard implements Listener {

    /**
     * The folder of the files, inside the folder of the plugin: under
     * {@code playerdata}, next to the statistics of
     * {@link de.elia.cameraplugin.storage.PlayerStore}.
     */
    private static final String FOLDER = "crash";

    /**
     * The folder of everything the plugin keeps per player. The files lay
     * right in it before the statistics got a folder next to them, and are
     * still looked for there: a crash from before the update must not be
     * lost.
     */
    private static final String PLAYER_FOLDER = "playerdata";

    /** Which layout a file has, for a later version that changes it. */
    private static final int FORMAT = 1;

    /** What stands at the top of every file, for whoever finds one and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly crash protection: backup of what cam mode took from this player.",
            "Only used after a server crash, on the next join. Deleted when cam mode ends.",
            "⚠ Do not edit or delete it while the server is running! ⚠");

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final ConsoleLog log;

    public CamCrashGuard(CameraPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.log = log;
    }

    private Path file(UUID playerId) {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FOLDER).resolve(playerId + ".yml");
    }

    private Path oldFile(UUID playerId) {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(playerId + ".yml");
    }

    /** The file a player left behind, in the folder of now or of before, or {@code null} for none. */
    private Path leftBehind(UUID playerId) {
        Path file = file(playerId);
        if (Files.exists(file)) {
            return file;
        }
        Path old = oldFile(playerId);
        return Files.exists(old) ? old : null;
    }

    /**
     * Writes down what a player entering camera mode is to get back. Called
     * before the flight speed of camera mode is set, so his own is read off
     * him.
     */
    public void save(Player player, CameraData data) {
        if (!settings.isCrashProtectionEnabled()) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        file.set("format", FORMAT);
        file.set("name", player.getName());
        file.set("inventory", Arrays.asList(data.getOriginalInventoryContents()));
        file.set("effects", new ArrayList<>(data.getPausedEffects()));
        file.set("game-mode", data.getOriginalGameMode().name());
        file.set("allow-flight", data.getOriginalAllowFlight());
        file.set("flying", data.getOriginalFlying());
        file.set("glowing", data.getOriginalGlowing());
        file.set("fly-speed", (double) player.getFlySpeed());
        // Written out instead of as a Location: one of those cannot even be
        // read back while its world is not loaded, and the rest of the file
        // would be lost with it.
        Location body = data.getBody().getLocation();
        file.set("body.world", body.getWorld().getName());
        file.set("body.x", body.getX());
        file.set("body.y", body.getY());
        file.set("body.z", body.getZ());
        file.set("body.yaw", (double) body.getYaw());
        file.set("body.pitch", (double) body.getPitch());
        Path target = file(player.getUniqueId());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, file.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "Crash protection: the file of " + player.getName()
                    + " could not be written, a crash would leave him without his things: " + ex);
        }
    }

    /**
     * Deletes the file of a player whose camera mode has ended and who has
     * his things back. Done whatever {@code crash-protection.enabled} says: a
     * file from before it was switched off would otherwise turn him back to
     * that old state on his next join.
     */
    public void delete(Player player) {
        try {
            Files.deleteIfExists(file(player.getUniqueId()));
        } catch (IOException ex) {
            log.log(Level.WARNING, "Crash protection: the file of " + player.getName()
                    + " could not be deleted. On his next join he will be set back to the state of his"
                    + " last start of cam mode unless it is removed: " + file(player.getUniqueId())
                    + " (" + ex + ")");
        }
    }

    /**
     * A player joining with a file left behind: camera mode never ended for
     * him. A tick later, so that he is fully in the world and nothing that
     * comes with the join undoes it again.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (leftBehind(player.getUniqueId()) == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !plugin.isInCameraMode(player)) {
                restore(player);
            }
        });
    }

    /**
     * The same for everybody already online when the plugin starts, which
     * only happens when it is started by hand on a running server.
     */
    public void restoreOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (leftBehind(player.getUniqueId()) != null) {
                restore(player);
            }
        }
    }

    /**
     * Gives a player back what his file holds and puts him at his body.
     *
     * <p>His inventory is replaced and not added to, so nothing turns up
     * twice: if the server never saved him in camera mode, he simply gets the
     * same things again. The effects go the same way - the ones of camera
     * mode, invisibility and night vision, are taken off with whatever else
     * he wears.</p>
     *
     * <p>A file that cannot be read is moved aside instead of deleted, so that
     * nothing in it is lost and somebody can still look into it.</p>
     */
    private void restore(Player player) {
        Path path = leftBehind(player.getUniqueId());
        if (path == null) {
            return;
        }
        YamlConfiguration file = new YamlConfiguration();
        try {
            file.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
            apply(player, file);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            keepBroken(player, path, ex);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.log(Level.WARNING, "Crash protection: the file of " + player.getName()
                    + " was given back but could not be deleted: " + path + " (" + ex + ")");
        }
        log.log(Level.INFO, "Crash protection: " + player.getName()
                + " was in cam mode when the server went down and got his things back.");
        messages.sendMessage(player, "crash-restored");
    }

    private void apply(Player player, YamlConfiguration file) {
        List<?> inventory = file.getList("inventory", List.of());
        int size = Math.min(inventory.size(), player.getInventory().getSize());
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            contents[i] = inventory.get(i) instanceof ItemStack item ? item : null;
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        for (Object entry : file.getList("effects", List.of())) {
            if (entry instanceof PotionEffect effect) {
                player.addPotionEffect(effect);
            }
        }
        player.getInventory().setContents(contents);
        player.updateInventory();
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(file.getString("game-mode", "SURVIVAL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            gameMode = GameMode.SURVIVAL;
        }
        player.setGameMode(gameMode);
        player.setAllowFlight(file.getBoolean("allow-flight"));
        player.setFlying(file.getBoolean("flying"));
        player.setGlowing(file.getBoolean("glowing"));
        float flySpeed = (float) file.getDouble("fly-speed", 0.1);
        player.setFlySpeed(Math.max(-1.0f, Math.min(1.0f, flySpeed)));
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        World world = Bukkit.getWorld(file.getString("body.world", ""));
        if (world != null) {
            player.teleport(new Location(world, file.getDouble("body.x"), file.getDouble("body.y"),
                    file.getDouble("body.z"), (float) file.getDouble("body.yaw"),
                    (float) file.getDouble("body.pitch")));
        }
    }

    /** Moves a file that could not be given back out of the way, under a name that says so. */
    private void keepBroken(Player player, Path path, Exception reason) {
        Path aside = path.resolveSibling(path.getFileName() + ".broken");
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            aside = path;
        }
        log.log(Level.SEVERE, "Crash protection: the file of " + player.getName()
                + " could not be given back, his things from cam mode are still in " + aside
                + " (" + reason + ")");
    }
}
