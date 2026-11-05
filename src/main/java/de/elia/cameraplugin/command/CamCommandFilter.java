package de.elia.cameraplugin.command;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps a camera player from the commands {@code cam-commands} names.
 *
 * <p>The camera is not where the player is. A command that remembers a place
 * remembers the one of the camera: {@code /sethome} while the camera hangs in
 * somebody else's base, and {@code /home} would take him there later, where
 * only his camera could go. Which commands do that only the server knows, so
 * the list is the server's: a blacklist out of the box with the usual ones,
 * or a whitelist of what may be used at all.</p>
 *
 * <p>{@code /cam} always goes, it is how camera mode ends.</p>
 */
public final class CamCommandFilter implements Listener {

    private final CameraPlugin plugin;

    public CamCommandFilter(CameraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Before any other plugin gets to see the command, so that none of them acts on it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        CamSettings settings = plugin.getSettings();
        if (settings.getCamCommandMode() == CommandMode.ALL || !plugin.isInCameraMode(player)) {
            return;
        }
        String typed = withoutSlash(event.getMessage());
        List<String> words = words(typed);
        if (words.isEmpty() || isCam(words.get(0))) {
            return;
        }
        boolean listed = listed(words, settings.getCamCommands());
        if (listed == (settings.getCamCommandMode() == CommandMode.BLACKLIST)) {
            event.setCancelled(true);
            plugin.getMessages().sendMessage(player, "cam-command-blocked",
                    "{command}", typed.split("\\s+", 2)[0]);
        }
    }

    /**
     * The words of a command as the list compares them: without the slash in
     * front, in lower case, and the first one without the namespace of its
     * plugin - {@code essentials:sethome} is {@code sethome}.
     */
    public static List<String> words(String command) {
        List<String> words = new ArrayList<>();
        for (String word : withoutSlash(command).toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(words.isEmpty() ? withoutNamespace(word) : word);
            }
        }
        return words;
    }

    /** The command without the one slash it is typed with. */
    private static String withoutSlash(String command) {
        String text = command.strip();
        return text.startsWith("/") ? text.substring(1).strip() : text;
    }

    private static String withoutNamespace(String label) {
        int colon = label.indexOf(':');
        return colon >= 0 && colon < label.length() - 1 ? label.substring(colon + 1) : label;
    }

    /**
     * Whether an entry of the list names this command: its first word is the
     * command or one of its aliases, and the words after it, a subcommand,
     * follow in the same order.
     */
    private static boolean listed(List<String> words, List<List<String>> list) {
        Set<String> names = names(words.get(0));
        for (List<String> entry : list) {
            if (!names.contains(entry.get(0)) || entry.size() > words.size()) {
                continue;
            }
            if (words.subList(1, entry.size()).equals(entry.subList(1, entry.size()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a command is called: as it was typed, and when a plugin brings it,
     * its name and every alias - the entry {@code sethome} holds for
     * {@code /createhome} as well, which is the same command of EssentialsX.
     */
    private static Set<String> names(String label) {
        Set<String> names = new HashSet<>();
        names.add(label);
        PluginCommand command = Bukkit.getPluginCommand(label);
        if (command != null) {
            names.add(command.getName().toLowerCase(Locale.ROOT));
            for (String alias : command.getAliases()) {
                names.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Whether the command is {@code /cam}, by its name or an alias. */
    private boolean isCam(String label) {
        PluginCommand cam = plugin.getCommand("cam");
        if (cam == null) {
            return label.equals("cam");
        }
        if (label.equalsIgnoreCase(cam.getName())) {
            return true;
        }
        for (String alias : cam.getAliases()) {
            if (label.equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }
}
