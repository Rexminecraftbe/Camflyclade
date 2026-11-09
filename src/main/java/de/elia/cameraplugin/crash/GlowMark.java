package de.elia.cameraplugin.crash;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The glowing outline of camera mode, noted down on the player himself.
 *
 * <p>Glowing is a flag the world saves along with the player, and no command
 * of the game takes it off him again: {@code /data} does not change players,
 * and {@code /effect clear} takes the effect, not the flag. An outline camera
 * mode left on him would stay for good - and every camera mode after it would
 * keep it, since its end gives back whatever he had at its start.</p>
 *
 * <p>The file of {@link CamCrashGuard} gives it back after a crash, but only
 * where there is a file it can read. So camera mode also notes in the
 * player's own data whether he glowed before it. The world saves that note
 * in the same file and at the same moment as the flag: whatever state of him
 * it loads, the note is there exactly when the outline of camera mode is. A
 * player joining with it gets his own outline back, file or no file.</p>
 */
public final class GlowMark implements Listener {

    private final CameraPlugin plugin;
    /** The note: whether he glowed before camera mode. */
    private final NamespacedKey key;

    public GlowMark(CameraPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "glowing-before-cam");
    }

    /** Notes, at the start of camera mode and before it lets him glow, whether he glowed already. */
    public void mark(Player player, boolean glowingBefore) {
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, glowingBefore);
    }

    /** Takes the note off again: camera mode has ended and given him his own outline back. */
    public void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    /**
     * A player joining with the note: camera mode never ended for the state
     * of him the world has loaded. Right away - the crash file, where there
     * is one, follows a tick later with the same value.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        giveBack(event.getPlayer());
    }

    /** The same for everybody online when the plugin is started by hand on a running server. */
    public void giveBackOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            giveBack(player);
        }
    }

    private void giveBack(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        Boolean glowingBefore = data.get(key, PersistentDataType.BOOLEAN);
        if (glowingBefore == null || plugin.isInCameraMode(player)) {
            return;
        }
        player.setGlowing(glowingBefore);
        data.remove(key);
    }
}
