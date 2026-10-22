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
 * <ul>
 *   <li>{@code /cam stats} - his own</li>
 *   <li>{@code /cam stats reset} - sets his own back to nothing</li>
 *   <li>{@code /cam stats player <name>} - those of somebody else, for whoever
 *       {@code statistics.view-others} lets</li>
 * </ul>
 *
 * <p>A camera mode counts once it has ended. One that a crash cut short
 * never ended and is not counted.</p>
 */
public final class CamStats {

    /** Who may look at the statistics of somebody else on {@code view-others: admin}. Operators have it by default. */
    public static final String PERMISSION_OTHERS = "camplugin.stats.others";

    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final CamBattery battery;
    private final CamTimeLimit timeLimit;

    public CamStats(CamSettings settings, Messages messages, PlayerStore store, CamBattery battery,
                    CamTimeLimit timeLimit) {
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.battery = battery;
        this.timeLimit = timeLimit;
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

    /**
     * Whether a sender may look at the statistics of somebody else, after
     * {@code statistics.view-others}. The console always may, it is the
     * server.
     */
    public boolean mayViewOthers(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        return switch (settings.getStatsViewOthers()) {
            case ADMIN -> player.hasPermission(PERMISSION_OTHERS);
            case ALL -> player.hasPermission("camplugin.use");
            case OFF -> false;
        };
    }

    /**
     * {@code /cam stats} with what follows it.
     *
     * @param args the words after {@code stats}
     */
    public void command(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                show(sender, player.getUniqueId(), player.getName());
            } else {
                messages.sendConfiguredMessage(sender, "no-player");
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            reset(sender);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            showOther(sender, args[1]);
        } else {
            messages.sendConfiguredMessage(sender, "stats-usage");
        }
    }

    /** {@code /cam stats player <name>}: the statistics of a player who has been on the server once. */
    private void showOther(CommandSender sender, String name) {
        // His own name is nobody else.
        if (sender instanceof Player self && self.getName().equalsIgnoreCase(name)) {
            show(sender, self.getUniqueId(), self.getName());
            return;
        }
        if (!mayViewOthers(sender)) {
            messages.sendConfiguredMessage(sender, "no-permission");
            return;
        }
        OfflinePlayer target = find(name);
        if (target == null) {
            messages.sendConfiguredMessage(sender, "stats-unknown-player", "{player}", name);
            return;
        }
        show(sender, target.getUniqueId(), target.getName() != null ? target.getName() : name);
    }

    /** {@code /cam stats reset}: sets the statistics of the sender back to nothing. */
    private void reset(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendConfiguredMessage(sender, "no-player");
            return;
        }
        if (!settings.isStatsEnabled()) {
            messages.sendConfiguredMessage(sender, "stats-off");
            return;
        }
        store.get(player.getUniqueId()).resetStats();
        store.save(player.getUniqueId());
        messages.sendConfiguredMessage(player, "stats-reset");
    }

    /**
     * Sends the statistics of one player, his battery while there is one,
     * and his cooldown while it runs.
     */
    private void show(CommandSender sender, UUID id, String name) {
        // With the statistics switched off the battery is still worth a look.
        if (!settings.isStatsEnabled() && !settings.isBatteryEnabled()) {
            messages.sendConfiguredMessage(sender, "stats-off");
            return;
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
        long cooldown = timeLimit.getCooldownRemaining(id);
        if (cooldown > 0) {
            messages.sendConfiguredMessage(sender, "stats-cooldown",
                    "{time}", CamTimeLimit.formatDuration(cooldown));
        }
    }

    /**
     * A player by his name: online, or once on this server. Only players the
     * server knows are looked through - a name nobody here has had is not
     * looked up anywhere else. For {@code /cam ban} as well.
     */
    public static OfflinePlayer find(String name) {
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
