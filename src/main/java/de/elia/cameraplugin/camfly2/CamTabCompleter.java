package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.back.CamBack;
import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.list.CamList;
import de.elia.cameraplugin.list.CamListTeleport;
import de.elia.cameraplugin.nightvision.CamNightVision;
import de.elia.cameraplugin.photo.CamPhotoMode;
import de.elia.cameraplugin.sky.CamSky;
import de.elia.cameraplugin.spot.CamSpots;
import de.elia.cameraplugin.stats.CamStats;
import de.elia.cameraplugin.stop.CamStop;
import de.elia.cameraplugin.trail.CamTrail;
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
    private final CamBan ban;
    private final CamTrail trail;
    private final CamSpots spots;
    private final CamPhotoMode photoMode;
    private final CamSky sky;

    public CamTabCompleter(CameraPlugin plugin) {
        this.plugin = plugin;
        this.camList = plugin.getCamList();
        this.stats = plugin.getStats();
        this.back = plugin.getBack();
        this.listTeleport = plugin.getListTeleport();
        this.stop = plugin.getStop();
        this.ban = plugin.getBan();
        this.trail = plugin.getTrail();
        this.spots = plugin.getSpots();
        this.photoMode = plugin.getPhotoMode();
        this.sky = plugin.getSky();
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
        boolean banCommand = args.length > 1 && args[0].equalsIgnoreCase("ban") && ban.mayUse(sender);
        if (banCommand && args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>(startingWith(partial, "all"));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        if (banCommand && args.length == 3) {
            return startingWith(args[2].toLowerCase(Locale.ROOT), "30m", "1h", "1d", "7d");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unban") && ban.mayUse(sender)) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            if (ban.everybodyBanned() && "all".startsWith(partial)) {
                names.add("all");
            }
            for (String name : ban.bannedNames()) {
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    names.add(name);
                }
            }
            return names;
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
        boolean trailCommand = args.length > 1 && args[0].equalsIgnoreCase("trail") && trail.mayUse(sender);
        if (trailCommand && args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (String name : trail.recordedNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    names.add(name);
                }
            }
            return names;
        }
        if (trailCommand && args.length == 3) {
            return startingWith(args[2].toLowerCase(Locale.ROOT), trail.choicesFor(args[1]).toArray(new String[0]));
        }
        Player player = sender instanceof Player p && p.hasPermission("camplugin.use") ? p : null;
        // What the menu offers as well is only offered as a command while
        // menu.access lets the commands through.
        Player own = player != null && plugin.getSettings().getMenuAccess().allowsCommands() ? player : null;
        boolean sharing = plugin.getSettings().isViewpointsSharing();
        boolean spotCommand = args.length > 1 && args[0].equalsIgnoreCase("spot") && own != null && spots.isEnabled();
        if (spotCommand && args.length == 2) {
            List<String> words = new ArrayList<>(
                    startingWith(args[1].toLowerCase(Locale.ROOT), "set", "delete", "list"));
            if (sharing) {
                words.addAll(startingWith(args[1].toLowerCase(Locale.ROOT), "share", "unshare"));
            }
            words.addAll(spotNames(own, args[1]));
            words.addAll(matching(spots.sharedLabels(own), args[1]));
            return words;
        }
        if (spotCommand && args.length == 3 && (args[1].equalsIgnoreCase("delete")
                || (sharing && (args[1].equalsIgnoreCase("share") || args[1].equalsIgnoreCase("unshare"))))) {
            return spotNames(own, args[2]);
        }
        if (spotCommand && args.length == 4 && sharing && args[1].equalsIgnoreCase("share")) {
            List<String> names = new ArrayList<>(startingWith(args[3].toLowerCase(Locale.ROOT), "all"));
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(own) && own.canSee(other)) {
                    names.add(other.getName());
                }
            }
            return matching(names, args[3]);
        }
        if (spotCommand && args.length == 4 && sharing && args[1].equalsIgnoreCase("unshare")) {
            return matching(spots.sharedNames(own, args[2]), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("visit") && own != null
                && plugin.getSettings().isVisitorCameraEnabled()) {
            return matching(spots.sharedLabels(own), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("time") && own != null && sky.mayUseTime(own)) {
            return startingWith(args[1].toLowerCase(Locale.ROOT), CamSky.timeWords());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("weather") && own != null && sky.mayUseWeather(own)) {
            return startingWith(args[1].toLowerCase(Locale.ROOT), CamSky.weatherWords());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("nightvision") && own != null
                && plugin.getSettings().isNightVisionPlayerChoice()) {
            return startingWith(args[1].toLowerCase(Locale.ROOT), CamNightVision.words());
        }
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String partial = args[0].toLowerCase();
        List<String> completions = new ArrayList<>();
        // Only offered where they do something: back, photo, time and weather
        // to a player in camera mode, nightvision and spot while they are on.
        if (own != null && back.mayUse(own) && "back".startsWith(partial)) {
            completions.add("back");
        }
        if (own != null && plugin.getSettings().isNightVisionPlayerChoice() && "nightvision".startsWith(partial)) {
            completions.add("nightvision");
        }
        if (own != null && photoMode.mayUse(own) && "photo".startsWith(partial)) {
            completions.add("photo");
        }
        if (own != null && spots.isEnabled() && "spot".startsWith(partial)) {
            completions.add("spot");
        }
        if (own != null && sky.mayUseTime(own) && "time".startsWith(partial)) {
            completions.add("time");
        }
        if (own != null && sky.mayUseWeather(own) && "weather".startsWith(partial)) {
            completions.add("weather");
        }
        if (own != null && plugin.getResume().hasFlight(own) && "resume".startsWith(partial)) {
            completions.add("resume");
        }
        if (own != null && plugin.getSettings().isVisitorCameraEnabled() && !spots.sharedWith(own).isEmpty()
                && "visit".startsWith(partial)) {
            completions.add("visit");
        }
        if (player != null && plugin.getMenu().isOpenable() && "menu".startsWith(partial)) {
            completions.add("menu");
        }
        if (camList.mayUse(sender) && "list".startsWith(partial)) {
            completions.add("list");
        }
        // The console is neither a player nor an operator, but may reload all
        // the same - so it is offered the reload as well.
        if ((!(sender instanceof Player) || sender.isOp()) && "reload".startsWith(partial)) {
            completions.add("reload");
        }
        if (stats.showsAnything() && "stats".startsWith(partial)) {
            completions.add("stats");
        }
        if (stop.mayUse(sender) && "stop".startsWith(partial)) {
            completions.add("stop");
        }
        if (ban.mayUse(sender)) {
            completions.addAll(startingWith(partial, "ban", "banlist", "unban"));
        }
        if (listTeleport.mayUse(sender) && "tp".startsWith(partial)) {
            completions.add("tp");
        }
        if (trail.mayUse(sender) && "trail".startsWith(partial)) {
            completions.add("trail");
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

    /** The words that begin like the partial one, capitals not counting. */
    private static List<String> matching(List<String> words, String partial) {
        String start = partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String word : words) {
            if (word.toLowerCase(Locale.ROOT).startsWith(start)) {
                matches.add(word);
            }
        }
        return matches;
    }

    /** The names of his own spots, for {@code /cam spot <name>} and {@code /cam spot delete <name>}. */
    private List<String> spotNames(Player player, String partial) {
        String start = partial.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (String name : spots.names(player)) {
            if (name.toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(name);
            }
        }
        return names;
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
     * at somebody else, while there is anything to see, {@code reset} for a
     * player, while there are statistics to reset.
     */
    private List<String> statsWords(CommandSender sender, String partial) {
        List<String> words = new ArrayList<>();
        if (stats.showsAnything() && stats.mayViewOthers(sender) && "player".startsWith(partial)) {
            words.add("player");
        }
        if (sender instanceof Player && plugin.getSettings().isStatsEnabled() && "reset".startsWith(partial)) {
            words.add("reset");
        }
        return words;
    }

    /**
     * The players online, for {@code /cam stats player <name>} - offered
     * only to whoever may name somebody there.
     */
    private List<String> playerNames(CommandSender sender, String partial) {
        if (!stats.showsAnything() || !stats.mayViewOthers(sender)) {
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
