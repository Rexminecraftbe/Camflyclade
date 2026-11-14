package de.elia.cameraplugin.stats;

import de.elia.cameraplugin.battery.CamBattery;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.storage.PlayerRecord;
import de.elia.cameraplugin.storage.PlayerStore;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The statistics of camera mode, {@code statistics} and {@code /cam stats}:
 * how often a player flew, how long and how far his camera flew altogether,
 * both for one flight on average, the longest flight and the longest way, how
 * far it flew away from his body - flown, not jumped to a spot far off; and
 * only without a fixed border: at one, nearly everybody gets there once, and
 * all would have the same number - and since when all this is counted.
 * On a tour of {@code /cam follow}, the way the guide takes a guest along
 * counts as flown, while {@code statistics.count-follow-distance} says so,
 * see {@link de.elia.cameraplugin.tour.CamTour}.
 *
 * <ul>
 *   <li>{@code /cam stats} - his own</li>
 *   <li>{@code /cam stats reset} - sets his own back to nothing</li>
 *   <li>{@code /cam stats player <name>} - those of somebody else, for whoever
 *       {@code statistics.view-others} lets</li>
 * </ul>
 *
 * <p>A camera mode counts once it has ended, and only if it ran for
 * {@code statistics.min-seconds} at least: one switched on by mistake, or
 * ended by a hit on the body right away, would only add a flight and pull the
 * average down. One that a crash cut short never ended and is not counted.
 * While one is going on, it is shown along with them as it would count if it
 * ended now - one flight more, its time, its way and how far it got, once it
 * is long enough -, so that the numbers grow as it goes and do not jump when
 * it ends; see {@link #lines}.</p>
 */
public final class CamStats {

    /** Who may look at the statistics of somebody else on {@code view-others: admin}. Operators have it by default. */
    public static final String PERMISSION_OTHERS = "camplugin.stats.others";

    private final CamSettings settings;
    private final Messages messages;
    private final PlayerStore store;
    private final CamBattery battery;
    private final CamTimeLimit timeLimit;
    private final CameraPlayers cameraPlayers;

    public CamStats(CamSettings settings, Messages messages, PlayerStore store, CamBattery battery,
                    CamTimeLimit timeLimit, CameraPlayers cameraPlayers) {
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.battery = battery;
        this.timeLimit = timeLimit;
        this.cameraPlayers = cameraPlayers;
    }

    /**
     * Adds a camera mode that is ending to the statistics of its player - or,
     * shorter than {@code statistics.min-seconds}, only when it ended. The
     * record is written to its file by whoever ends camera mode, together with
     * the battery.
     */
    public void record(Player player, CameraData data) {
        if (!settings.isStatsEnabled()) {
            return;
        }
        long seconds = seconds(data);
        PlayerRecord record = store.get(player.getUniqueId());
        if (counts(seconds)) {
            record.addSession(player.getName(), data.getStartedAt(), seconds,
                    data.getDistanceCounted(), data.getFarthestFlownFromBody());
        } else {
            record.addUncountedSession(player.getName());
        }
    }

    /** How long a camera mode has been going on, in whole seconds, the way it counts once it ends. */
    private static long seconds(CameraData data) {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - data.getStartedAt());
    }

    /** Whether a camera mode of so many seconds is long enough to be counted, {@code statistics.min-seconds}. */
    private boolean counts(long seconds) {
        return seconds >= settings.getStatsMinSeconds();
    }

    /**
     * Whether {@code /cam stats} has anything to show: the statistics, or with
     * them switched off the battery, while there is one. Only then is it
     * offered in the tab completion, like every other command that is
     * switched off.
     */
    public boolean showsAnything() {
        return settings.isStatsEnabled() || settings.isBatteryEnabled();
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
     * and his cooldown while it runs. A line whose text is empty is left
     * out, as it is under the button of the menu.
     */
    private void show(CommandSender sender, UUID id, String name) {
        for (Line line : lines(id, name)) {
            if (!messages.getMessage(line.key()).isEmpty()) {
                messages.sendConfiguredMessage(sender, line.key(), line.fills());
            }
        }
    }

    /** One line of the statistics: the key of its text in the language file, and what fills it in. */
    public record Line(String key, String... fills) {
    }

    /**
     * The lines of the statistics of one player, as {@code /cam stats} sends
     * them and the menu shows them under its button: the header, the
     * numbers, his battery while there is one and his cooldown while it
     * runs - or {@code stats-off} alone, with nothing to show.
     *
     * <p>While he is in camera mode, the flight going on is in the numbers
     * already, as {@link #record} would add it if it ended now, and
     * {@code stats-flying} says how long it has been going: the menu, which
     * draws them anew every second, shows them growing. Until it has run for
     * {@code statistics.min-seconds} it would not count, and
     * {@code stats-flying-uncounted} says so instead - it is not in the
     * numbers then, and for somebody who has no counted flight yet there are
     * none.</p>
     *
     * <p>The battery and the cooldown say how things stand now, not what has
     * added up over all flights: {@code stats-gap} sets them apart from the
     * numbers above them.</p>
     */
    public List<Line> lines(UUID id, String name) {
        List<Line> lines = new ArrayList<>();
        // With the statistics switched off the battery is still worth a look.
        if (!settings.isStatsEnabled() && !settings.isBatteryEnabled()) {
            lines.add(new Line("stats-off"));
            return lines;
        }
        PlayerRecord record = store.peek(id);
        CameraData flight = cameraPlayers.get(id);
        long now = flight == null ? 0L : seconds(flight);
        // The flight going on as far as it would count if it ended now: not
        // at all while it is shorter than min-seconds.
        CameraData counted = flight != null && counts(now) ? flight : null;
        long sessions = record.getSessions() + (counted == null ? 0L : 1L);
        if (!settings.isStatsEnabled()) {
            // Only the battery is left to show.
            lines.add(new Line("stats-header", "{player}", name));
        } else if (sessions == 0 && flight == null) {
            lines.add(new Line("stats-none", "{player}", name));
        } else {
            lines.add(new Line("stats-header", "{player}", name));
            if (counted != null) {
                lines.add(new Line("stats-flying", "{time}", CamTimeLimit.formatDuration(now)));
            } else if (flight != null) {
                lines.add(new Line("stats-flying-uncounted", "{time}", CamTimeLimit.formatDuration(now),
                        "{min}", CamTimeLimit.formatDuration(settings.getStatsMinSeconds())));
            }
            if (sessions > 0) {
                addNumbers(lines, record, counted, counted == null ? 0L : now, sessions);
            }
        }
        long cooldown = timeLimit.getCooldownRemaining(id);
        if (settings.isStatsEnabled() && (settings.isBatteryEnabled() || cooldown > 0)) {
            // Only under the numbers: with the statistics switched off, the
            // battery stands under the header alone.
            lines.add(new Line("stats-gap"));
        }
        if (settings.isBatteryEnabled()) {
            lines.add(new Line("stats-battery",
                    "{charge}", CamTimeLimit.formatDuration((long) Math.floor(battery.charge(id))),
                    "{capacity}", CamTimeLimit.formatDuration(settings.getBatteryCapacity())));
        }
        if (cooldown > 0) {
            lines.add(new Line("stats-cooldown", "{time}", CamTimeLimit.formatDuration(cooldown)));
        }
        return lines;
    }

    /**
     * The numbers over all flights: altogether, for one flight on average,
     * the longest flight and the longest way, how far from the body and since
     * when.
     *
     * @param counted the flight going on, if it would count now, or {@code null}
     * @param now     how long that one has been going, in seconds, or 0
     */
    private void addNumbers(List<Line> lines, PlayerRecord record, CameraData counted, long now, long sessions) {
        long totalSeconds = record.getTotalSeconds() + now;
        double flownNow = counted == null ? 0.0 : counted.getDistanceCounted();
        double flown = record.getDistanceFlown() + flownNow;
        lines.add(new Line("stats-sessions", "{count}", String.valueOf(sessions)));
        lines.add(new Line("stats-total-time", "{time}", CamTimeLimit.formatDuration(totalSeconds)));
        lines.add(new Line("stats-distance", "{distance}", blocks(flown)));
        lines.add(new Line("stats-average",
                "{time}", CamTimeLimit.formatDuration(Math.round((double) totalSeconds / sessions)),
                "{distance}", blocks(flown / sessions)));
        lines.add(new Line("stats-longest", "{time}",
                CamTimeLimit.formatDuration(Math.max(record.getLongestSeconds(), now))));
        lines.add(new Line("stats-longest-distance", "{distance}",
                blocks(Math.max(record.getLongestDistance(), flownNow))));
        // At a fixed border nearly everybody gets there once, and from then
        // on the number is the same for all: max-distance. It is still
        // counted, for when the border goes.
        if (!settings.limitsDistance()) {
            double farthest = Math.max(record.getFarthestFromBody(),
                    counted == null ? 0.0 : counted.getFarthestFlownFromBody());
            lines.add(new Line("stats-farthest", "{distance}", blocks(farthest)));
        }
        // The first counted flight is the one going on, while the record has
        // none yet; a record from before this was noted does not know.
        long since = record.getSessions() == 0 && counted != null
                ? counted.getStartedAt() : record.getCountedSince();
        if (since > 0L) {
            lines.add(new Line("stats-since", "{date}", date(since)));
        }
    }

    /**
     * A day as {@code stats-since-date} writes it, in the time zone of the
     * server. A pattern that is none falls back on year, month and day.
     */
    private String date(long millis) {
        ZonedDateTime when = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        try {
            return DateTimeFormatter.ofPattern(messages.getMessage("stats-since-date")).format(when);
        } catch (IllegalArgumentException ex) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd").format(when);
        }
    }

    /** A distance for {@code {distance}}, in whole blocks. */
    private static String blocks(double distance) {
        return String.valueOf(Math.round(distance));
    }

    /** The text of a line, filled in, or {@code null} while it is switched off. */
    public String text(Line line) {
        if (!messages.isMessageEnabled(line.key())) {
            return null;
        }
        String text = messages.getMessage(line.key());
        for (int i = 0; i + 1 < line.fills().length; i += 2) {
            text = text.replace(line.fills()[i], line.fills()[i + 1]);
        }
        return text;
    }

    /** {@code /cam stats reset} for a player, from the menu. */
    public void reset(Player player) {
        reset((CommandSender) player);
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
