package de.elia.cameraplugin.stats;

import de.elia.cameraplugin.battery.CamBattery;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The statistics of camera mode, {@code statistics} and {@code /cam stats}:
 * how often a player flew, how long altogether and at most, how far his
 * camera flew, and how far it got from his body.
 *
 * <p>A camera mode counts once it has ended. One that a crash cut short
 * never ended and is not counted.</p>
 */
public final class CamStats {

    /** Who may look at the statistics of somebody else. Operators have it by default. */
    public static final String PERMISSION_OTHERS = "camplugin.stats.others";

    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final CamBattery battery;

    public CamStats(CamSettings settings, Messages messages, PlayerStore store, CamBattery battery) {
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.battery = battery;
    }

    /**
     * Adds a camera mode that is ending to the statistics of its player. The
     * record is written to its file by whoever ends camera mode, together with
     * the battery.
     */
    public void record(Player player, CameraData data) {
        if (!settings.isStatsEnabled()) {
            return;
        }
        long seconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - data.getStartedAt());
        store.get(player.getUniqueId()).addSession(player.getName(), seconds,
                data.getDistanceFlown(), data.getFarthestFromBody());
    }

    /** Whether a sender may look at the statistics of somebody else. The console always may. */
    public boolean mayViewOthers(CommandSender sender) {
        return !(sender instanceof Player player) || player.hasPermission(PERMISSION_OTHERS);
    }

    /**
     * {@code /cam stats [player]}: sends the statistics of the sender himself,
     * or of the player named. That player need not be online, only have been
     * on the server once.
     *
     * @param targetName the player named, or {@code null} for the sender
     */
    public void show(CommandSender sender, String targetName) {
        // With the statistics switched off the battery is still worth a look.
        if (!settings.isStatsEnabled() && !settings.isBatteryEnabled()) {
            messages.sendConfiguredMessage(sender, "stats-off");
            return;
        }
        UUID id;
        String name;
        // His own name is no other player.
        if (targetName != null && sender instanceof Player self && self.getName().equalsIgnoreCase(targetName)) {
            targetName = null;
        }
        if (targetName == null) {
            if (!(sender instanceof Player player)) {
                messages.sendConfiguredMessage(sender, "no-player");
                return;
            }
            id = player.getUniqueId();
            name = player.getName();
        } else {
            if (!mayViewOthers(sender)) {
                messages.sendConfiguredMessage(sender, "no-permission");
                return;
            }
            OfflinePlayer target = find(targetName);
            if (target == null) {
                messages.sendConfiguredMessage(sender, "stats-unknown-player", "{player}", targetName);
                return;
            }
            id = target.getUniqueId();
            name = target.getName() != null ? target.getName() : targetName;
        }
        PlayerRecord record = store.peek(id);
        if (!settings.isStatsEnabled()) {
            // Only the battery is left to show.
            messages.sendConfiguredMessage(sender, "stats-header", "{player}", name);
        } else if (record.getSessions() == 0) {
            messages.sendConfiguredMessage(sender, "stats-none", "{player}", name);
        } else {
            messages.sendConfiguredMessage(sender, "stats-header", "{player}", name);
            messages.sendConfiguredMessage(sender, "stats-sessions",
                    "{count}", String.valueOf(record.getSessions()));
            messages.sendConfiguredMessage(sender, "stats-total-time",
                    "{time}", CamTimeLimit.formatDuration(record.getTotalSeconds()));
            messages.sendConfiguredMessage(sender, "stats-longest",
                    "{time}", CamTimeLimit.formatDuration(record.getLongestSeconds()));
            messages.sendConfiguredMessage(sender, "stats-distance",
                    "{distance}", String.valueOf(Math.round(record.getDistanceFlown())));
            messages.sendConfiguredMessage(sender, "stats-farthest",
                    "{distance}", String.valueOf(Math.round(record.getFarthestFromBody())));
        }
        if (settings.isBatteryEnabled()) {
            messages.sendConfiguredMessage(sender, "stats-battery",
                    "{charge}", CamTimeLimit.formatDuration((long) Math.floor(battery.charge(id))),
                    "{capacity}", CamTimeLimit.formatDuration(settings.getBatteryCapacity()));
        }
    }

    /**
     * A player by his name: online, or once on this server. Only players the
     * server knows are looked through - a name nobody here has had is not
     * looked up anywhere else.
     */
    private static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(player.getName())) {
                return player;
            }
        }
        return null;
    }
}
