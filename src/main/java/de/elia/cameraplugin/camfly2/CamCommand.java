package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.list.CamList;
import de.elia.cameraplugin.menu.MenuAccess;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public class CamCommand implements CommandExecutor {

    /**
     * The words after {@code /cam} for what {@code /cam menu} offers as
     * well, which {@code menu.access} may keep to the menu.
     */
    public static final Set<String> MENU_WORDS = Set.of("back", "resume", "spot", "visit", "tour", "photo",
            "time", "weather", "nightvision", "stats");

    /**
     * The words after {@code /cam} of the commands for admins, which
     * {@code menu.admin-access} may keep to the menu for players - the
     * reload aside, and {@code stats player} as well.
     */
    public static final Set<String> ADMIN_WORDS = Set.of("list", "stop", "ban", "unban", "banlist", "trail", "tp");

    private final CameraPlugin plugin;
    private final Messages messages;
    private final CamList camList;

    public CamCommand(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.camList = plugin.getCamList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Before the player check: the reload also works from the server
        // console. Whoever changes the file on the server does not always have
        // an operator in the game to type the command.
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }
        // A player gets at the rest only the way menu.access and
        // menu.admin-access let him; the console has no menu and gets at
        // everything.
        if (sender instanceof Player player && !commandAllowed(player, args)) {
            return true;
        }
        // From the console as well: whoever runs the server wants to know who
        // is flying around, without being in the game.
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            if (camList.mayUse(sender)) {
                camList.show(sender, plugin.getListTeleport().mayUse(sender), plugin.getStop().showsButton(sender));
            } else {
                messages.sendConfiguredMessage(sender, "no-permission");
            }
            return true;
        }
        // From the console as well, like /cam stop.
        if (args.length > 0 && args[0].equalsIgnoreCase("ban")) {
            plugin.getBan().ban(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("unban")) {
            plugin.getBan().unban(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("banlist")) {
            plugin.getBan().list(sender);
            return true;
        }
        // From the console as well: whoever restarts the server wants
        // everybody back at his body first.
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            plugin.getStop().command(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // From the console as well, which gets where the flight went, if
        // not the line itself.
        if (args.length > 0 && args[0].equalsIgnoreCase("trail")) {
            plugin.getTrail().command(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // The console can look at the statistics of a player, only not at
        // its own.
        if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            plugin.getStats().command(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.sendConfiguredMessage(sender, "no-player");
            return true;
        }

        if (!player.hasPermission("camplugin.use")) {
            messages.sendConfiguredMessage(player, "no-permission");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("menu")) {
            plugin.getMenu().command(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("resume")) {
            plugin.getResume().command(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("visit")) {
            plugin.getVisit().command(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("tour")) {
            plugin.getTour().command(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("back")) {
            plugin.getBack().command(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("spot")) {
            plugin.getSpots().command(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("photo")) {
            plugin.getPhotoMode().command(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("time")) {
            plugin.getSky().timeCommand(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("weather")) {
            plugin.getSky().weatherCommand(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("nightvision")) {
            plugin.getNightVision().command(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        // What the buttons of /cam list run.
        if (args.length > 0 && args[0].equalsIgnoreCase("tp")) {
            plugin.getListTeleport().command(player, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }

        plugin.getStarter().toggle(player);
        return true;
    }

    /**
     * Whether a player may use this command as a command, after
     * {@code menu.access} for what the menu offers him and
     * {@code menu.admin-access} for what it offers admins. If not, he is told
     * that it goes through {@code /cam menu}, or that it is switched off.
     */
    private boolean commandAllowed(Player player, String[] args) {
        if (args.length == 0) {
            return true;
        }
        String word = args[0].toLowerCase(Locale.ROOT);
        MenuAccess access;
        if (isAdminCommand(args)) {
            access = plugin.getSettings().getAdminAccess();
        } else if (MENU_WORDS.contains(word)) {
            access = plugin.getSettings().getMenuAccess();
        } else {
            return true;
        }
        if (access.allowsCommands() || (access.allowsMenu() && answersInvitation(args))) {
            return true;
        }
        messages.sendConfiguredMessage(player, access.allowsMenu() ? "menu-only" : "menu-features-off");
        return false;
    }

    /**
     * Whether the command answers an invitation to a tour, which the buttons
     * of the invitation in the chat run: it goes while only the menu goes as
     * well, so that a click on them starts the tour right away.
     */
    private static boolean answersInvitation(String[] args) {
        return args.length == 3 && args[0].equalsIgnoreCase("tour")
                && (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("decline"));
    }

    /** Whether the command is one for admins, {@code /cam stats player} among them. */
    public static boolean isAdminCommand(String[] args) {
        return args.length > 0 && (ADMIN_WORDS.contains(args[0].toLowerCase(Locale.ROOT))
                || (args.length > 1 && args[0].equalsIgnoreCase("stats") && args[1].equalsIgnoreCase("player")));
    }

    /**
     * Reads the config file again.
     *
     * <p>A player needs the same rights as for the command itself and has to be
     * an operator on top; the console needs nothing, it is the server.</p>
     *
     * @param sender who asked, the console being {@code null} to the plugin -
     *               there is no chat to send the notes about the file to
     */
    private boolean reload(CommandSender sender) {
        Player player = sender instanceof Player p ? p : null;
        if (player != null && (!player.hasPermission("camplugin.use") || !player.isOp())) {
            messages.sendConfiguredMessage(player, "no-permission");
            return true;
        }
        messages.sendConfiguredMessage(sender, "reload-start");
        if (plugin.reloadPlugin(player)) {
            messages.sendConfiguredMessage(sender, "reload-success");
        }
        return true;
    }

}
