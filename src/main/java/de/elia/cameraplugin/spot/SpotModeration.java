package de.elia.cameraplugin.spot;

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
 * The moderation of the spots, on the admin page of the spots
 * ({@value #PERMISSION}): an admin looks at the spots of any player - online
 * or not -, takes a public or shared one down, so that it is private again,
 * or deletes one, and keeps a player from saving new spots, making them
 * public, sharing them or looking at spots at all, for good or for a while
 * and why, see {@link SpotBans}.
 *
 * <p>The player hears about all of it while he is online, and every time a
 * restriction keeps him from something: what, for how long still and why -
 * with the texts of a ban of camera mode for the time and the reason.</p>
 */
public final class SpotModeration {

    /** The right of the admin page of the spots. Operators have it by default. */
    public static final String PERMISSION = "camplugin.spotadmin";

    /** What a restriction can keep a player from, by the word the texts go by. */
    public enum What {
        /** Saving new spots: moving one he has stays his. */
        CREATE("create"),
        /** Making spots public. */
        PUBLIC("public"),
        /** Sharing spots with single players. */
        SHARE("share"),
        /** Looking at spots: jumping to them, his own as well, and visiting them, also with {@code /cam resume}. */
        VIEW("view");

        final String key;

        What(String key) {
            this.key = key;
        }
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
     * Whether he may do this with spots. If not, he has been told what keeps
     * him, for how long still and why.
     */
    public boolean check(Player player, What what) {
        SpotBans.Ban ban = bans.get(player.getUniqueId());
        if (ban == null || !keeps(ban, what)) {
            return true;
        }
        long now = System.currentTimeMillis();
        messages.sendMessage(player, "spot-restricted-" + what.key, "{time}", messageTime(ban, now),
                "{reason}", messageReason(ban));
        return false;
    }

    private static boolean keeps(SpotBans.Ban ban, What what) {
        return switch (what) {
            case CREATE -> ban.create();
            case PUBLIC -> ban.publish();
            case SHARE -> ban.share();
            case VIEW -> ban.view();
        };
    }

    /** What keeps a player from what, or {@code null} while nothing does. For the menu, which changes it. */
    public SpotBans.Ban banOf(UUID playerId) {
        return bans.get(playerId);
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
        SpotBans.Ban ban = bans.get(playerId);
        if (ban == null) {
            return null;
        }
        return whatText(ban.create(), ban.publish(), ban.share(), ban.view()) + " "
                + time(ban, System.currentTimeMillis()) + reason(ban);
    }

    /** What a restriction keeps him from, written out one after the other: "no new spots, nothing public". */
    public String whatText(boolean create, boolean publish, boolean share, boolean view) {
        List<String> parts = new ArrayList<>();
        if (create) {
            parts.add(messages.getMessage("spot-restriction-create"));
        }
        if (publish) {
            parts.add(messages.getMessage("spot-restriction-public"));
        }
        if (share) {
            parts.add(messages.getMessage("spot-restriction-share"));
        }
        if (view) {
            parts.add(messages.getMessage("spot-restriction-view"));
        }
        return String.join(messages.getMessage("spot-restriction-separator"), parts);
    }

    /**
     * Keeps a player from what is given, or changes what keeps him: for so
     * many minutes, 0 for good, and why. He hears about it while he is
     * online.
     *
     * @param name his name, for the texts and the file
     */
    public void ban(CommandSender admin, UUID playerId, String name, boolean create, boolean publish, boolean share,
                    boolean view, long minutes, String reason) {
        if (!mayUse(admin) || !(create || publish || share || view)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean change = bans.get(playerId) != null;
        SpotBans.Ban ban = new SpotBans.Ban(name, create, publish, share, view,
                minutes > 0L ? now + minutes * 60_000L : 0L, reason.trim(), name(admin));
        bans.ban(playerId, ban);
        String what = whatText(create, publish, share, view);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && !online.equals(admin)) {
            messages.sendMessage(online, change ? "spot-restricted-changed" : "spot-restricted-now",
                    "{player}", name(admin), "{what}", what, "{time}", messageTime(ban, now),
                    "{reason}", messageReason(ban));
        }
        messages.sendConfiguredMessage(admin, change ? "spot-restrict-changed" : "spot-restrict-done",
                "{player}", name, "{what}", what, "{time}", messageTime(ban, now), "{reason}", messageReason(ban));
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
