package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.item.CameraItem;
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

    private final CamSettings settings;
    private final CamList camList;
    private final CamStats stats;

    public CamTabCompleter(CamSettings settings, CamList camList, CamStats stats) {
        this.settings = settings;
        this.camList = camList;
        this.stats = stats;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2) {
            return playerNames(sender, args[0].toLowerCase(Locale.ROOT), args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String partial = args[0].toLowerCase();
        List<String> completions = new ArrayList<>();
        if (settings.isCameraItemEnabled() && "item".startsWith(partial)) {
            completions.add("item");
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
        return completions;
    }

    /**
     * The players online, for {@code /cam item <player>} and
     * {@code /cam stats <player>} - offered only to whoever may name somebody
     * there.
     */
    private List<String> playerNames(CommandSender sender, String subcommand, String partial) {
        boolean mayName = switch (subcommand) {
            case "item" -> !(sender instanceof Player) || sender.hasPermission(CameraItem.PERMISSION_OTHERS);
            case "stats" -> stats.mayViewOthers(sender);
            default -> false;
        };
        if (!mayName) {
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
