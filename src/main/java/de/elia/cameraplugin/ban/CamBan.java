package de.elia.cameraplugin.ban;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /cam ban}, {@code /cam unban} and {@code /cam banlist}: keeps single
 * players out of camera mode, or everybody at once - during an event, say.
 * The section {@code cam-ban}; the bans themselves are kept by
 * {@link BanList}.
 *
 * <ul>
 *   <li>{@code /cam ban <player> [time] [reason]} - one player, for good or
 *       for a while</li>
 *   <li>{@code /cam ban all [time] [reason]} - everybody but those with
 *       {@value #BYPASS}</li>
 *   <li>{@code /cam unban <player>} and {@code /cam unban all} - lifts the
 *       one or the other; the ban of everybody leaves the single ones as they
 *       are</li>
 *   <li>{@code /cam banlist} - who is banned, for how long still, by whom and
 *       why</li>
 * </ul>
 *
 * <p>A ban ends a running camera mode at once, the way {@code /cam stop}
 * does, and is checked first of all when somebody tries to start one. A ban
 * of a single player holds for everybody, operators included: it is meant
 * for him by name. Banning again whom a ban holds already changes his ban:
 * the new time and reason take the place of the old ones, and the texts say
 * so - to him, whether he has to wait longer or less, or whether it is for
 * good now or not any more.</p>
 */
public final class CamBan {

    /** The right the commands ask for. Operators have it by default. */
    public static final String PERMISSION = "camplugin.ban";

    /** Whoever has this right is not kept out by the ban of everybody. Operators have it by default. */
    public static final String BYPASS = "camplugin.ban.bypass";

    /** The word that stands for everybody. */
    private static final String ALL = "all";

    /** A time as the commands take it: numbers with s, m, h, d or w after them, such as 2h or 1d12h. */
    private static final Pattern TIME = Pattern.compile("(?i)(\\d{1,9}[smhdw])+");
    private static final Pattern TIME_PART = Pattern.compile("(?i)(\\d{1,9})([smhdw])");

    /**
     * The longest ban for a while, some hundred years: anything longer is as
     * good as for good, and the end of it is to fit into a number of
     * milliseconds.
     */
    private static final long MAX_MILLIS = 100L * 365L * 86_400_000L;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final BanList bans;

    public CamBan(CameraPlugin plugin, BanList bans) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.bans = bans;
    }

    /**
     * Whether this sender may ban and unban: while {@code cam-ban.enabled} is
     * on, a player with the right of the commands, and the console, which is
     * the server.
     */
    public boolean mayUse(CommandSender sender) {
        return settings.isCamBanEnabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION));
    }

    /**
     * Checks whether a ban keeps the player out of camera mode. Asked before
     * anything else at the start: waiting or going somewhere else does not
     * help a banned player.
     *
     * @return whether he may start; if not, he has been told why and for how
     *         long still
     */
    public boolean checkStart(Player player) {
        if (!settings.isCamBanEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis();
        BanList.Ban ban = bans.get(player.getUniqueId());
        if (ban != null) {
            messages.sendMessage(player, "banned", "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
            return false;
        }
        BanList.Ban everybody = bans.everybody();
        if (everybody != null && !player.hasPermission(BYPASS)) {
            messages.sendMessage(player, "banned-everybody", "{time}", messageTime(everybody, now),
                    "{reason}", messageReason(everybody));
            return false;
        }
        return true;
    }

    /** {@code /cam ban}, with what follows it. */
    public void ban(CommandSender sender, String[] args) {
        if (!checkUse(sender)) {
            return;
        }
        if (args.length == 0) {
            messages.sendConfiguredMessage(sender, "ban-usage");
            return;
        }
        long now = System.currentTimeMillis();
        long until = 0L;
        int reasonFrom = 1;
        if (args.length > 1 && TIME.matcher(args[1]).matches()) {
            until = now + millis(args[1]);
            reasonFrom = 2;
        }
        give(sender, args[0], now, until, String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)).trim());
    }

    /**
     * A ban from the menu, the same as {@code /cam ban}: the time in minutes,
     * 0 for good, and the reason as it was written - one that begins with a
     * word such as 2h included, which the command would take for the time.
     *
     * @param who a player by his name, or {@code all}
     */
    public void ban(CommandSender sender, String who, long minutes, String reason) {
        if (!checkUse(sender)) {
            return;
        }
        long now = System.currentTimeMillis();
        give(sender, who, now, minutes > 0L ? now + Math.min(MAX_MILLIS, minutes * 60_000L) : 0L, reason.trim());
    }

    /**
     * Bans a player, or everybody, until then - 0 for good -, or changes the
     * ban that holds already.
     */
    private void give(CommandSender sender, String who, long now, long until, String reason) {
        String by = name(sender);
        if (who.equalsIgnoreCase(ALL)) {
            BanList.Ban old = bans.everybody();
            boolean change = old != null;
            BanList.Ban ban = new BanList.Ban(null, until, reason, by);
            bans.banEverybody(ban);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.equals(sender) || player.hasPermission(BYPASS)) {
                    continue;
                }
                if (plugin.isInCameraMode(player)) {
                    plugin.exitCameraMode(player, EndReason.BAN);
                }
                messages.sendMessage(player, change ? changed("banned-everybody-changed-", old, ban)
                                : "banned-everybody-now", "{player}", by, "{time}", messageTime(ban, now),
                        "{reason}", messageReason(ban), "{date}", date(ban), "{diff}", difference(old, ban));
            }
            messages.sendConfiguredMessage(sender, change ? "ban-everybody-changed" : "ban-everybody-done",
                    "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
            return;
        }
        OfflinePlayer target = CamStats.find(who);
        if (target == null) {
            messages.sendConfiguredMessage(sender, "ban-unknown-player", "{player}", who);
            return;
        }
        String name = target.getName() != null ? target.getName() : who;
        BanList.Ban old = bans.get(target.getUniqueId());
        boolean change = old != null;
        BanList.Ban ban = new BanList.Ban(name, until, reason, by);
        bans.ban(target.getUniqueId(), ban);
        Player online = target.getPlayer();
        if (online != null) {
            if (plugin.isInCameraMode(online)) {
                plugin.exitCameraMode(online, EndReason.BAN);
            }
            messages.sendMessage(online, change ? changed("banned-changed-", old, ban) : "banned-now",
                    "{player}", by, "{time}", messageTime(ban, now), "{reason}", messageReason(ban),
                    "{date}", date(ban), "{diff}", difference(old, ban));
        }
        messages.sendConfiguredMessage(sender, change ? "ban-changed" : "ban-done", "{player}", name,
                "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
    }

    /** {@code /cam unban}, with what follows it. */
    public void unban(CommandSender sender, String[] args) {
        if (!checkUse(sender)) {
            return;
        }
        if (args.length != 1) {
            messages.sendConfiguredMessage(sender, "ban-usage");
            return;
        }
        if (args[0].equalsIgnoreCase(ALL)) {
            if (!bans.unbanEverybody()) {
                messages.sendConfiguredMessage(sender, "unban-everybody-none");
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.equals(sender) && !player.hasPermission(BYPASS)) {
                    messages.sendMessage(player, "unbanned-everybody-now", "{player}", name(sender));
                }
            }
            messages.sendConfiguredMessage(sender, "unban-everybody-done");
            return;
        }
        UUID id = bannedId(args[0]);
        if (id == null || !bans.unban(id)) {
            messages.sendConfiguredMessage(sender, "unban-not-banned", "{player}", args[0]);
            return;
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            messages.sendMessage(online, "unbanned-now", "{player}", name(sender));
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(id);
        messages.sendConfiguredMessage(sender, "unban-done",
                "{player}", target.getName() != null ? target.getName() : args[0]);
    }

    /** {@code /cam banlist}: the ban of everybody first, then the single players in the order they were banned. */
    public void list(CommandSender sender) {
        if (!checkUse(sender)) {
            return;
        }
        long now = System.currentTimeMillis();
        BanList.Ban everybody = bans.everybody();
        Map<UUID, BanList.Ban> players = bans.players();
        if (everybody == null && players.isEmpty()) {
            messages.sendConfiguredMessage(sender, "banlist-empty");
            return;
        }
        messages.sendConfiguredMessage(sender, "banlist-header",
                "{count}", String.valueOf(players.size() + (everybody != null ? 1 : 0)));
        if (everybody != null) {
            messages.sendConfiguredMessage(sender, "banlist-everybody", "{time}", time(everybody, now),
                    "{by}", everybody.by(), "{reason}", listReason(everybody));
        }
        for (BanList.Ban ban : players.values()) {
            messages.sendConfiguredMessage(sender, "banlist-entry", "{player}", ban.name(),
                    "{time}", time(ban, now), "{by}", ban.by(), "{reason}", listReason(ban));
        }
    }

    /** The names of the banned players, for the tab completion of {@code /cam unban}. */
    public Iterable<String> bannedNames() {
        return bans.players().values().stream().map(BanList.Ban::name).toList();
    }

    /** Whether this player is banned himself, for the menu. */
    public boolean isBanned(UUID playerId) {
        return bans.get(playerId) != null;
    }

    /** The ban of this player, or {@code null} while he is not banned. For the menu, which changes it. */
    public BanList.Ban banOf(UUID playerId) {
        return bans.get(playerId);
    }

    /** The ban of everybody, or {@code null} while there is none. For the menu, which changes it. */
    public BanList.Ban everybodyBan() {
        return bans.everybody();
    }

    /**
     * How the ban of this player stands, the way the texts put it - for how
     * long, and why - or {@code null} while he is not banned. For the menu.
     */
    public String banText(UUID playerId) {
        BanList.Ban ban = bans.get(playerId);
        return ban == null ? null : time(ban, System.currentTimeMillis()) + reason(ban);
    }

    /** How the ban of everybody stands, as {@link #banText} has it, or {@code null} while there is none. */
    public String everybodyBanText() {
        BanList.Ban ban = bans.everybody();
        return ban == null ? null : time(ban, System.currentTimeMillis()) + reason(ban);
    }

    /** Whether there is a ban of everybody, for the tab completion of {@code /cam unban}. */
    public boolean everybodyBanned() {
        return bans.everybody() != null;
    }

    /** Re-reads the bans after {@code /cam reload}, so that changes made in the file by hand count. */
    public void reload() {
        bans.load();
    }

    /**
     * Whether the sender may use the commands, and if not, tells him why:
     * switched off, or not his right.
     */
    private boolean checkUse(CommandSender sender) {
        if (!settings.isCamBanEnabled()) {
            messages.sendConfiguredMessage(sender, "ban-off");
            return false;
        }
        if (!mayUse(sender)) {
            messages.sendConfiguredMessage(sender, "no-permission");
            return false;
        }
        return true;
    }

    /** How long a ban still holds, the way the texts put it: "for 2h 0m 0s" or "for good". */
    private String time(BanList.Ban ban, long now) {
        return ban.forGood()
                ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(ban.secondsLeft(now)));
    }

    /** Why, the way the texts put it behind the time, or nothing when no reason was given. */
    private String reason(BanList.Ban ban) {
        return ban.reason().isEmpty() ? "" : messages.getMessage("ban-reason").replace("{reason}", ban.reason());
    }

    /**
     * {@code {time}} in the messages of a ban: as {@link #time} has it, but
     * nothing for a ban for good with a reason - "banned you from cam mode
     * because: ..." says enough then.
     */
    private String messageTime(BanList.Ban ban, long now) {
        return ban.forGood() && !ban.reason().isEmpty() ? "" : time(ban, now);
    }

    /**
     * {@code {reason}} in the messages of a ban: as {@link #reason} has it,
     * without the space in front where {@link #messageTime} leaves the time out.
     */
    private String messageReason(BanList.Ban ban) {
        return ban.forGood() ? reason(ban).stripLeading() : reason(ban);
    }

    /**
     * Which text tells whom a changed ban holds how it changed, after its
     * {@code prefix}: {@code forever} when it is for good now, {@code until}
     * when it was for good and is not any more, {@code longer} or
     * {@code shorter} by a minute or more, else {@code now}.
     */
    private static String changed(String prefix, BanList.Ban old, BanList.Ban ban) {
        if (ban.forGood() || old.forGood()) {
            return prefix + (ban.forGood() == old.forGood() ? "now" : ban.forGood() ? "forever" : "until");
        }
        long diff = ban.until() - old.until();
        return prefix + (Math.abs(diff) < 60_000L ? "now" : diff > 0L ? "longer" : "shorter");
    }

    /**
     * By how much a ban lasts longer or shorter than the one it changed, in
     * whole minutes, the way the texts write a duration; nothing where either
     * is for good.
     */
    private static String difference(BanList.Ban old, BanList.Ban ban) {
        if (old == null || old.forGood() || ban.forGood()) {
            return "";
        }
        long minutes = Math.round(Math.abs(ban.until() - old.until()) / 60_000.0);
        return CamTimeLimit.formatDuration(minutes * 60L);
    }

    /**
     * When a ban ends, as {@code ban-date} writes it, in the time zone of the
     * server; nothing for one for good. A pattern that is none falls back on
     * year, month, day, hour and minute.
     */
    private String date(BanList.Ban ban) {
        if (ban.forGood()) {
            return "";
        }
        ZonedDateTime when = Instant.ofEpochMilli(ban.until()).atZone(ZoneId.systemDefault());
        try {
            return DateTimeFormatter.ofPattern(messages.getMessage("ban-date")).format(when);
        } catch (IllegalArgumentException ex) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(when);
        }
    }

    /** Why, the way {@code /cam banlist} puts it behind who banned, or nothing when no reason was given. */
    private String listReason(BanList.Ban ban) {
        return ban.reason().isEmpty() ? "" : messages.getMessage("banlist-reason").replace("{reason}", ban.reason());
    }

    /** Who bans, for the texts: a player by his name, the console as {@code console-name}. */
    private String name(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : messages.getMessage("console-name");
    }

    /** A time such as 2h or 1d12h in milliseconds. */
    private static long millis(String time) {
        long total = 0L;
        Matcher part = TIME_PART.matcher(time);
        while (part.find()) {
            long amount = Long.parseLong(part.group(1));
            long unit = switch (part.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L;
            };
            total = Math.min(MAX_MILLIS, total + Math.min(MAX_MILLIS, amount * unit));
        }
        return total;
    }

    /**
     * The id of a banned player by his name: the name he was banned under,
     * or the one the server knows him by now. Asked in that order, so that a
     * player who changed his name can be unbanned under the old one.
     */
    private UUID bannedId(String name) {
        for (Map.Entry<UUID, BanList.Ban> entry : bans.players().entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name())) {
                return entry.getKey();
            }
        }
        OfflinePlayer player = CamStats.find(name);
        return player != null ? player.getUniqueId() : null;
    }
}
