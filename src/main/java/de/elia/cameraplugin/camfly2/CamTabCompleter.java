package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.list.CamList;
import de.elia.cameraplugin.stats.CamStats;
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

    private final CamList camList;
    private final CamStats stats;

    public CamTabCompleter(CamList camList, CamStats stats) {
        this.camList = camList;
        this.stats = stats;
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
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String partial = args[0].toLowerCase();
        List<String> completions = new ArrayList<>();
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
        return completions;
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
