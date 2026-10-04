package de.elia.cameraplugin.camfly2;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CamTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // The console is neither a player nor an operator, but may reload all
        // the same - so it is offered the reload as well.
        if (args.length == 1 && (!(sender instanceof Player) || sender.isOp())) {
            String partial = args[0].toLowerCase();
            if ("reload".startsWith(partial)) {
                List<String> completions = new ArrayList<>();
                completions.add("reload");
                return completions;
            }
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }
}
