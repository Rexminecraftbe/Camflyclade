package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.back.CamBack;
import de.elia.cameraplugin.list.CamList;
import de.elia.cameraplugin.list.CamListTeleport;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.stop.CamStop;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CamTabCompleter implements TabCompleter {

    private final CameraPlugin plugin;
    private final CamList camList;
    private final CamStats stats;
    private final CamBack back;
    private final CamListTeleport listTeleport;
    private final CamStop stop;

    public CamTabCompleter(CameraPlugin plugin) {
        this.plugin = plugin;
        this.camList = plugin.getCamList();
        this.stats = plugin.getStats();
        this.back = plugin.getBack();
        this.listTeleport = plugin.getListTeleport();
        this.stop = plugin.getStop();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean statsCommand = args.length > 1 && args[0].equalsIgnoreCase("stats");
        if (statsCommand && args.length == 2) {
            return statsWords(sender, args[1].toLowerCase(Locale.ROOT));
        }
        if (statsCommand && args.length == 3 && args[1].equalsIgnoreCase("player")) {
            return playerNames(sender, args[2].toLowerCase(Locale.ROOT));
        }
        boolean tpCommand = args.length > 1 && args[0].equalsIgnoreCase("tp") && listTeleport.mayUse(sender);
        if (tpCommand && args.length == 2) {
            return cameraNames(sender, args[1].toLowerCase(Locale.ROOT));
        }
        if (tpCommand && args.length == 3) {
            return startingWith(args[2].toLowerCase(Locale.ROOT), "camera", "body");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop") && stop.mayUse(sender)) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>(startingWith(partial, "all"));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.isInCameraMode(player) && player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String partial = args[0].toLowerCase();
        List<String> completions = new ArrayList<>();
        // Only offered where it does something: to a player in camera mode.
        if (sender instanceof Player player && player.hasPermission("camplugin.use") && back.mayUse(player)
                && "back".startsWith(partial)) {
            completions.add("back");
        }
        if (camList.mayUse(sender) && "list".startsWith(partial)) {
            completions.add("list");
        }
        // The console is neither a player nor an operator, but may reload all
        // the same - so it is offered the reload as well.
        if ((!(sender instanceof Player) || sender.isOp()) && "reload".startsWith(partial)) {
            completions.add("reload");
        }
        if ("stats".startsWith(partial)) {
            completions.add("stats");
        }
        if (stop.mayUse(sender) && "stop".startsWith(partial)) {
            completions.add("stop");
        }
        if (listTeleport.mayUse(sender) && "tp".startsWith(partial)) {
            completions.add("tp");
        }
        return completions;
    }

    private static List<String> startingWith(String partial, String... words) {
        List<String> matches = new ArrayList<>();
        for (String word : words) {
            if (word.startsWith(partial)) {
                matches.add(word);
            }
        }
        return matches;
    }

    /** The players in camera mode, for {@code /cam tp <name>} - nobody's own. */
    private List<String> cameraNames(CommandSender sender, String partial) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isInCameraMode(player) && !player.equals(sender)
                    && player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    /**
     * What may follow {@code /cam stats}: {@code player} for whoever may look
     * at somebody else, {@code reset} for a player.
     */
    private List<String> statsWords(CommandSender sender, String partial) {
        List<String> words = new ArrayList<>();
        if (stats.mayViewOthers(sender) && "player".startsWith(partial)) {
            words.add("player");
        }
        if (sender instanceof Player && "reset".startsWith(partial)) {
            words.add("reset");
        }
        return words;
    }

    /**
     * The players online, for {@code /cam stats player <name>} - offered
     * only to whoever may name somebody there.
     */
    private List<String> playerNames(CommandSender sender, String partial) {
        if (!stats.mayViewOthers(sender)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                names.add(player.getName());
            }
        }
        return names;
    }
}
