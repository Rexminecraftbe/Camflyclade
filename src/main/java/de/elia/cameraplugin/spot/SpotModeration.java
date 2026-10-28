package de.elia.cameraplugin.spot;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The moderation of the spots, on the admin page of the spots
 * ({@value #PERMISSION}): an admin looks at the spots of any player - online
 * or not -, goes there himself, takes a public or shared one down, so that it
 * is private again, or deletes one, and restricts a player, or everybody at
 * once, for good or for a while and why, see {@link SpotBans}: for each kind
 * of spots - his own, the public ones, those shared by name - no new ones, or
 * none at all, not even to look at. The restriction of everybody leaves out
 * whoever has {@value #BYPASS}.
 *
 * <p>The player hears about all of it while he is online, and every time a
 * restriction keeps him from something: what, for how long still and why -
 * with the texts of a ban of camera mode for the time and the reason; one of
 * everybody tells him that it is switched off. A tab of the spots he may not
 * look at shows him nothing but that.</p>
 */
public final class SpotModeration {

    /** The right of the admin page of the spots. Operators have it by default. */
    public static final String PERMISSION = "camplugin.spotadmin";

    /** Whoever has this right is not kept by the restriction of everybody. Operators have it by default. */
    public static final String BYPASS = "camplugin.spotadmin.bypass";

    /**
     * What keeps a player from something.
     *
     * @param ban       the restriction
     * @param everybody whether it is the one of everybody, rather than his own
     */
    public record Hold(SpotBans.Ban ban, boolean everybody) {
    }

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final SpotBans bans;

    public SpotModeration(CameraPlugin plugin, SpotBans bans) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.bans = bans;
    }

    /** Whether this sender may moderate spots: while spots are on, with the right, or the console. */
    public boolean mayUse(CommandSender sender) {
        return settings.isViewpointsEnabled()
                && (!(sender instanceof Player player) || player.hasPermission(PERMISSION));
    }

    /** Re-reads the restrictions after {@code /cam reload}, so that changes made in the file by hand count. */
    public void reload() {
        bans.load();
    }

    /**
     * What keeps him from this kind of spots at least as far as
     * {@code stage} - his own restriction first, then that of everybody -,
     * or {@code null} while nothing does.
     */
    private Hold hold(Player player, SpotBans.Kind kind, SpotBans.Stage stage) {
        SpotBans.Ban own = bans.get(player.getUniqueId());
        if (own != null && own.stage(kind).compareTo(stage) >= 0) {
            return new Hold(own, false);
        }
        SpotBans.Ban all = bans.everybody();
        if (all != null && all.stage(kind).compareTo(stage) >= 0 && !player.hasPermission(BYPASS)) {
            return new Hold(all, true);
        }
        return null;
    }

    /** What keeps him from looking at this kind of spots, or {@code null} while nothing does. */
    public Hold lookHold(Player player, SpotBans.Kind kind) {
        return hold(player, kind, SpotBans.Stage.NO_LOOK);
    }

    /**
     * Whether a restriction keeps this player from looking at this kind of
     * spots, online or not. Whether one who is offline would get around that
     * of everybody cannot be asked: it keeps him.
     */
    public boolean keepsFromLooking(UUID playerId, SpotBans.Kind kind) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return lookHold(online, kind) != null;
        }
        SpotBans.Ban own = bans.get(playerId);
        SpotBans.Ban all = bans.everybody();
        return own != null && own.stage(kind) == SpotBans.Stage.NO_LOOK
                || all != null && all.stage(kind) == SpotBans.Stage.NO_LOOK;
    }

    /**
     * Whether he may make a new spot of this kind - save one, make one
     * public, share one. If not, he has been told for how long still and why.
     */
    public boolean checkNew(Player player, SpotBans.Kind kind) {
        Hold hold = hold(player, kind, SpotBans.Stage.NO_NEW);
        if (hold == null) {
            return true;
        }
        tell(player, hold, kind, "new");
        return false;
    }

    /**
     * Whether he may look at one of his own spots - jump to it. If not, he
     * has been told for how long still and why.
     */
    public boolean checkLook(Player player) {
        return checkLook(player, List.of(SpotBans.Kind.PRIVATE));
    }

    /**
     * Whether he may look at a spot of another - jump to it, visit it: by
     * any of the ways he has it, as it is shared with him or as it is
     * public. If not, he has been told for how long still and why.
     */
    public boolean checkLook(Player player, Share share) {
        List<SpotBans.Kind> ways = new ArrayList<>();
        if (share.isAcceptedBy(player.getUniqueId())) {
            ways.add(SpotBans.Kind.SHARED);
        }
        if (share.isEveryone()) {
            ways.add(SpotBans.Kind.PUBLIC);
        }
        return ways.isEmpty() || checkLook(player, ways);
    }

    private boolean checkLook(Player player, List<SpotBans.Kind> ways) {
        Hold first = null;
        for (SpotBans.Kind kind : ways) {
            Hold hold = lookHold(player, kind);
            if (hold == null) {
                return true;
            }
            first = first == null ? hold : first;
        }
        tell(player, first, ways.get(0), "look");
        return false;
    }

    /**
     * Tells him what keeps him, with for how long still and why: his own
     * restriction as {@code spot-restricted-...}, that of everybody as
     * {@code spot-disabled-...}.
     */
    private void tell(Player player, Hold hold, SpotBans.Kind kind, String what) {
        messages.sendMessage(player, (hold.everybody() ? "spot-disabled-" : "spot-restricted-") + kind.key + "-"
                + what, "{time}", messageTime(hold.ban(), System.currentTimeMillis()),
                "{reason}", messageReason(hold.ban()));
    }

    /**
     * Fills in {@code {time}} and {@code {reason}} of a text with how long
     * still and why it keeps him, the way its messages put it - for the tab
     * of the spots he may not look at.
     */
    public String fill(String text, Hold hold) {
        return text.replace("{time}", messageTime(hold.ban(), System.currentTimeMillis()))
                .replace("{reason}", messageReason(hold.ban()));
    }

    /** What keeps a player from what, or {@code null} while nothing does. For the menu, which changes it. */
    public SpotBans.Ban banOf(UUID playerId) {
        return bans.get(playerId);
    }

    /** What keeps everybody from what, or {@code null} while nothing does. For the menu, which changes it. */
    public SpotBans.Ban everybodyBan() {
        return bans.everybody();
    }

    /** Every player kept from something right now, by his id. */
    public Map<UUID, SpotBans.Ban> restricted() {
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

    /** How the restriction of everybody stands, as {@link #banText} has it. */
    public String everybodyText() {
        return text(bans.everybody());
    }

    private String text(SpotBans.Ban ban) {
        if (ban == null) {
            return null;
        }
        return whatText(ban.own(), ban.open(), ban.shared()) + " " + time(ban, System.currentTimeMillis())
                + reason(ban);
    }

    /**
     * What a restriction keeps him from, written out one kind after the
     * other: "no new spots, no public spots at all".
     */
    public String whatText(SpotBans.Stage own, SpotBans.Stage open, SpotBans.Stage shared) {
        List<String> parts = new ArrayList<>();
        SpotBans.Stage[] stages = {own, open, shared};
        for (SpotBans.Kind kind : SpotBans.Kind.values()) {
            SpotBans.Stage stage = stages[kind.ordinal()];
            if (stage != SpotBans.Stage.FREE) {
                parts.add(messages.getMessage("spot-restriction-" + kind.key + "-" + stage.key));
            }
        }
        return String.join(messages.getMessage("spot-restriction-separator"), parts);
    }

    /**
     * Restricts a player, or changes his restriction: how far for each kind
     * of spots, for so many minutes, 0 for good, and why. He hears about it
     * while he is online.
     *
     * @param name his name, for the texts and the file
     */
    public void ban(CommandSender admin, UUID playerId, String name, SpotBans.Stage own, SpotBans.Stage open,
                    SpotBans.Stage shared, long minutes, String reason) {
        long now = System.currentTimeMillis();
        SpotBans.Ban ban = new SpotBans.Ban(name, own, open, shared, minutes > 0L ? now + minutes * 60_000L : 0L,
                reason.trim(), name(admin));
        if (!mayUse(admin) || !ban.any()) {
            return;
        }
        boolean change = bans.get(playerId) != null;
        bans.ban(playerId, ban);
        String what = whatText(own, open, shared);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, change ? "spot-restricted-changed" : "spot-restricted-now",
                    "{player}", name(admin), "{what}", what, "{time}", messageTime(ban, now),
                    "{reason}", messageReason(ban));
        }
        messages.sendConfiguredMessage(admin, change ? "spot-restrict-changed" : "spot-restrict-done",
                "{player}", name, "{what}", what, "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
    }

    /**
     * Restricts everybody but those with {@value #BYPASS}, or changes that
     * restriction: as {@link #ban} does a single player. Whoever it keeps
     * hears about it while he is online.
     */
    public void banEverybody(CommandSender admin, SpotBans.Stage own, SpotBans.Stage open, SpotBans.Stage shared,
                             long minutes, String reason) {
        long now = System.currentTimeMillis();
        SpotBans.Ban ban = new SpotBans.Ban(null, own, open, shared, minutes > 0L ? now + minutes * 60_000L : 0L,
                reason.trim(), name(admin));
        if (!mayUse(admin) || !ban.any()) {
            return;
        }
        boolean change = bans.everybody() != null;
        bans.banEverybody(ban);
        String what = whatText(own, open, shared);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(admin) && !online.hasPermission(BYPASS)) {
                messages.sendMessage(online, change ? "spot-disabled-changed" : "spot-disabled-now",
                        "{player}", name(admin), "{what}", what, "{time}", messageTime(ban, now),
                        "{reason}", messageReason(ban));
            }
        }
        messages.sendConfiguredMessage(admin, change ? "spot-restrict-everybody-changed"
                : "spot-restrict-everybody-done", "{what}", what, "{time}", messageTime(ban, now),
                "{reason}", messageReason(ban));
    }

    /** Lets a player do everything with spots again. He hears about it while he is online. */
    public void unban(CommandSender admin, UUID playerId, String name) {
        if (!mayUse(admin) || !bans.unban(playerId)) {
            return;
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, "spot-unrestricted-now", "{player}", name(admin));
        }
        messages.sendConfiguredMessage(admin, "spot-unrestrict-done", "{player}", name);
    }

    /**
     * Lets everybody do everything with spots again; single players stay
     * restricted. Whoever it kept hears about it while he is online.
     */
    public void unbanEverybody(CommandSender admin) {
        if (!mayUse(admin) || !bans.unbanEverybody()) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(admin) && !online.hasPermission(BYPASS)) {
                messages.sendMessage(online, "spot-enabled-now", "{player}", name(admin));
            }
        }
        messages.sendConfiguredMessage(admin, "spot-unrestrict-everybody-done");
    }

    /**
     * Takes one of his spots down: no longer public, shared with nobody and
     * offered to nobody - private again, as he can make it himself. He hears
     * about it while he is online.
     */
    public void takeDown(CommandSender admin, UUID owner, String ownerName, Spot spot) {
        if (!mayUse(admin) || !plugin.getSpots().takeDown(owner, spot.key())) {
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, "spot-taken-down", "{player}", name(admin), "{spot}", spot.name());
        }
        messages.sendConfiguredMessage(admin, "spot-admin-took-down", "{player}", ownerName, "{spot}", spot.name());
    }

    /**
     * Takes the admin himself to one of the spots, looking its way - from
     * wherever he is, with none of the rules of a jump: he goes to look at
     * it. In camera mode he leaves it first, as with {@code /cam tp}: his
     * body would stay behind otherwise. Whoever may fly keeps flying there,
     * as a spot may hang in the air.
     */
    public void teleport(Player admin, String ownerName, Spot spot) {
        if (!mayUse(admin)) {
            return;
        }
        Location to = spot.toLocation();
        if (to == null) {
            messages.sendConfiguredMessage(admin, "spot-admin-tp-no-world", "{spot}", spot.name(),
                    "{world}", spot.world());
            return;
        }
        if (plugin.isInCameraMode(admin)) {
            plugin.exitCameraMode(admin, EndReason.COMMAND);
            messages.sendConfiguredMessage(admin, "camera-off");
        }
        if (!plugin.getTeleportGuard().teleport(admin, to)) {
            return;
        }
        if (admin.getAllowFlight()) {
            admin.setFlying(true);
        }
        messages.sendConfiguredMessage(admin, "spot-admin-teleported", "{player}", ownerName, "{spot}", spot.name());
    }

    /** Deletes one of his spots, whether he is online or not. He hears about it while he is online. */
    public void delete(CommandSender admin, UUID owner, String ownerName, Spot spot) {
        if (!mayUse(admin) || !plugin.getSpots().deleteOf(owner, spot.key())) {
            return;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, "spot-deleted-by-admin", "{player}", name(admin), "{spot}", spot.name());
        }
        messages.sendConfiguredMessage(admin, "spot-admin-deleted", "{player}", ownerName, "{spot}", spot.name());
    }

    /** How long it still holds, the way the texts of a ban put it: "for 2h 0m 0s" or "for good". */
    private String time(SpotBans.Ban ban, long now) {
        return ban.forGood()
                ? messages.getMessage("ban-for-good")
                : messages.getMessage("ban-for").replace("{time}", CamTimeLimit.formatDuration(ban.secondsLeft(now)));
    }

    /** Why, the way the texts of a ban put it behind the time, or nothing when no reason was given. */
    private String reason(SpotBans.Ban ban) {
        return ban.reason().isEmpty() ? "" : messages.getMessage("ban-reason").replace("{reason}", ban.reason());
    }

    /**
     * {@code {time}} in the messages: as {@link #time} has it, but nothing
     * for one for good with a reason - "because: ..." says enough then.
     */
    private String messageTime(SpotBans.Ban ban, long now) {
        return ban.forGood() && !ban.reason().isEmpty() ? "" : time(ban, now);
    }

    /** {@code {reason}} in the messages, without the space in front where the time is left out. */
    private String messageReason(SpotBans.Ban ban) {
        return ban.forGood() ? reason(ban).stripLeading() : reason(ban);
    }

    /** Who acts, for the texts: a player by his name, the console as {@code console-name}. */
    private String name(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : messages.getMessage("console-name");
    }
}
