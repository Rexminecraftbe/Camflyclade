package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The moderation of the tours, on the admin page of the tours
 * ({@value #PERMISSION}): an admin keeps a player from leading tours, from
 * taking part in them, or both, or switches tours off for everybody at once
 * - for good or for a while and why, see {@link TourBans}. Switched off,
 * nobody leads tours or takes part in them but whoever has {@value #BYPASS}.
 *
 * <p>It holds at once: a guide it keeps from leading loses his tour and the
 * invitations he gave, a guest it keeps from taking part leaves the tour he
 * follows and loses his invitations, see {@link CamTour#keepOut}. He hears
 * about it while he is online, and every time it keeps him from something:
 * what, for how long still and why - with the texts of a ban of camera mode
 * for the time and the reason; switched off, that tours are switched off. A
 * guide who would invite a player it keeps is told so, and the menu shows it
 * under him.</p>
 */
public final class TourModeration {

    /** The right of the admin page of {@code /cam follow}. Operators have it by default. */
    public static final String PERMISSION = "camplugin.followadmin";

    /** Whoever has this right may lead and follow while it is switched off. Operators have it by default. */
    public static final String BYPASS = "camplugin.followadmin.bypass";

    /** What {@link #PERMISSION} was called while {@code /cam follow} was {@code /cam follow}; it still counts. */
    private static final String OLD_PERMISSION = "camplugin.touradmin";

    /** What {@link #BYPASS} was called then; it still counts as well. */
    private static final String OLD_BYPASS = "camplugin.touradmin.bypass";

    /**
     * What keeps a player from something.
     *
     * @param ban       the restriction
     * @param everybody whether tours are switched off for everybody, rather than his own restriction
     */
    public record Hold(TourBans.Ban ban, boolean everybody) {
    }

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final TourBans bans;

    public TourModeration(CameraPlugin plugin, TourBans bans) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.bans = bans;
    }

    /** Whether this sender may moderate tours: while tours are on, with the right, or the console. */
    public boolean mayUse(CommandSender sender) {
        return settings.isTourEnabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION)
                || player.hasPermission(OLD_PERMISSION));
    }

    /** Whether he may lead and follow while it is switched off for everybody, under the right's new name or old one. */
    private static boolean bypasses(Player player) {
        return player.hasPermission(BYPASS) || player.hasPermission(OLD_BYPASS);
    }

    /** Re-reads the restrictions after {@code /cam reload}, so that changes made in the file by hand count. */
    public void reload() {
        bans.load();
    }

    /**
     * What keeps him from this - his own restriction first, then that of
     * everybody -, or {@code null} while nothing does.
     */
    public Hold hold(Player player, TourBans.What what) {
        TourBans.Ban own = bans.get(player.getUniqueId());
        if (own != null && own.keeps(what)) {
            return new Hold(own, false);
        }
        TourBans.Ban all = bans.everybody();
        if (all != null && all.keeps(what) && !bypasses(player)) {
            return new Hold(all, true);
        }
        return null;
    }

    /** Whether nothing keeps him from this. */
    public boolean may(Player player, TourBans.What what) {
        return hold(player, what) == null;
    }

    /**
     * Whether he may do this - invite players to his tour, accept an
     * invitation. If not, he has been told for how long still and why: his
     * own restriction as {@code tour-restricted-...}, tours switched off as
     * {@code tour-disabled}.
     */
    public boolean check(Player player, TourBans.What what) {
        Hold hold = hold(player, what);
        if (hold == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        messages.sendMessage(player, hold.everybody() ? "tour-disabled" : "tour-restricted-" + what.key,
                "{time}", messageTime(hold.ban(), now), "{reason}", messageReason(hold.ban()));
        return false;
    }

    /**
     * Whether the guide may invite this player: nothing keeps him from taking
     * part. If not, the guide has been told.
     */
    public boolean checkGuest(Player guide, Player other) {
        Hold hold = hold(other, TourBans.What.JOIN);
        if (hold == null) {
            return true;
        }
        messages.sendConfiguredMessage(guide, hold.everybody() ? "tour-disabled-guest" : "tour-restricted-guest",
                "{player}", other.getName());
        return false;
    }

    /**
     * Fills in {@code {time}} and {@code {reason}} of a text with how long
     * still and why it keeps him, the way its messages put it - for the menu.
     */
    public String fill(String text, Hold hold) {
        return text.replace("{time}", messageTime(hold.ban(), System.currentTimeMillis()))
                .replace("{reason}", messageReason(hold.ban()));
    }

    /** What keeps a player from what, or {@code null} while nothing does. For the menu, which changes it. */
    public TourBans.Ban banOf(UUID playerId) {
        return bans.get(playerId);
    }

    /** What keeps everybody from what, or {@code null} while nothing does. For the menu, which changes it. */
    public TourBans.Ban everybodyBan() {
        return bans.everybody();
    }

    /** Every player kept from something right now, by his id. */
    public Map<UUID, TourBans.Ban> restricted() {
        return bans.players();
    }

    /**
     * How the restriction of a player stands, for the menu: what it keeps
     * him from, for how long still and why - or {@code null} while there is
     * none.
     */
    public String banText(UUID playerId) {
        return text(bans.get(playerId));
    }

    /**
     * For how long still and why tours are switched off for everybody, for
     * the menu - or {@code null} while they are on.
     */
    public String offText() {
        TourBans.Ban ban = bans.everybody();
        return ban == null ? null : time(ban, System.currentTimeMillis()) + reason(ban);
    }

    private String text(TourBans.Ban ban) {
        if (ban == null) {
            return null;
        }
        return whatText(ban.lead(), ban.join()) + " " + time(ban, System.currentTimeMillis()) + reason(ban);
    }

    /** What a restriction keeps him from, written out: "no leading, no taking part". */
    public String whatText(boolean lead, boolean join) {
        List<String> parts = new ArrayList<>();
        if (lead) {
            parts.add(messages.getMessage("tour-restriction-lead"));
        }
        if (join) {
            parts.add(messages.getMessage("tour-restriction-join"));
        }
        return String.join(messages.getMessage("tour-restriction-separator"), parts);
    }

    /**
     * Restricts a player, or changes his restriction: from leading tours,
     * from taking part, for so many minutes, 0 for good, and why. It holds at
     * once, and he hears about it while he is online.
     *
     * @param name his name, for the texts and the file
     */
    public void ban(CommandSender admin, UUID playerId, String name, boolean lead, boolean join, long minutes,
                    String reason) {
        long now = System.currentTimeMillis();
        TourBans.Ban ban = new TourBans.Ban(name, lead, join, minutes > 0L ? now + minutes * 60_000L : 0L,
                reason.trim(), name(admin));
        if (!mayUse(admin) || !ban.any()) {
            return;
        }
        boolean change = bans.get(playerId) != null;
        bans.ban(playerId, ban);
        String what = whatText(lead, join);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            if (!online.equals(admin)) {
                messages.sendMessage(online, change ? "tour-restricted-changed" : "tour-restricted-now",
                        "{player}", name(admin), "{what}", what, "{time}", messageTime(ban, now),
                        "{reason}", messageReason(ban));
            }
            plugin.getTour().keepOut(online);
        }
        messages.sendConfiguredMessage(admin, change ? "tour-restrict-changed" : "tour-restrict-done",
                "{player}", name, "{what}", what, "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
    }

    /**
     * Switches tours off for everybody but those with {@value #BYPASS} - no
     * leading, no taking part -, for so many minutes, 0 for good, and why;
     * or changes for how long and why while they are off. It holds at once,
     * and whoever it keeps hears about it while he is online.
     */
    public void switchOff(CommandSender admin, long minutes, String reason) {
        if (!mayUse(admin)) {
            return;
        }
        long now = System.currentTimeMillis();
        TourBans.Ban ban = new TourBans.Ban(null, true, true, minutes > 0L ? now + minutes * 60_000L : 0L,
                reason.trim(), name(admin));
        boolean change = bans.everybody() != null;
        bans.banEverybody(ban);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (bypasses(online)) {
                continue;
            }
            if (!online.equals(admin)) {
                messages.sendMessage(online, change ? "tour-disabled-changed" : "tour-disabled-now",
                        "{player}", name(admin), "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
            }
            plugin.getTour().keepOut(online);
        }
        messages.sendConfiguredMessage(admin, change ? "tour-off-changed" : "tour-off-done",
                "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
    }

    /** Lets a player lead tours and take part in them again. He hears about it while he is online. */
    public void unban(CommandSender admin, UUID playerId, String name) {
        if (!mayUse(admin) || !bans.unban(playerId)) {
            return;
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, "tour-unrestricted-now", "{player}", name(admin));
        }
        messages.sendConfiguredMessage(admin, "tour-unrestrict-done", "{player}", name);
    }

    /**
     * Switches tours on again for everybody; single players stay restricted.
     * Whoever it kept hears about it while he is online.
     */
    public void switchOn(CommandSender admin) {
        if (!mayUse(admin) || !bans.unbanEverybody()) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(admin) && !bypasses(online)) {
                messages.sendMessage(online, "tour-enabled-now", "{player}", name(admin));
            }
        }
        messages.sendConfiguredMessage(admin, "tour-on-done");
    }

    /** How long it still holds, the way the texts of a ban put it: "for 2h 0m 0s" or "for good". */
    private String time(TourBans.Ban ban, long now) {
        return ban.forGood()
                ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(ban.secondsLeft(now)));
    }

    /** Why, the way the texts of a ban put it behind the time, or nothing when no reason was given. */
    private String reason(TourBans.Ban ban) {
        return ban.reason().isEmpty() ? "" : messages.getMessage("ban-reason").replace("{reason}", ban.reason());
    }

    /**
     * {@code {time}} in the messages: as {@link #time} has it, but nothing
     * for one for good with a reason - "because: ..." says enough then.
     */
    private String messageTime(TourBans.Ban ban, long now) {
        return ban.forGood() && !ban.reason().isEmpty() ? "" : time(ban, now);
    }

    /** {@code {reason}} in the messages, without the space in front where the time is left out. */
    private String messageReason(TourBans.Ban ban) {
        return ban.forGood() ? reason(ban).stripLeading() : reason(ban);
    }

    /** Who acts, for the texts: a player by his name, the console as {@code console-name}. */
    private String name(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : messages.getMessage("console-name");
    }
}
