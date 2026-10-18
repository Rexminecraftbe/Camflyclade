package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.list.CamList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CamTabCompleter implements TabCompleter {

    private final CamList camList;

    public CamTabCompleter(CamList camList) {
        this.camList = camList;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
        return completions;
    }
}
