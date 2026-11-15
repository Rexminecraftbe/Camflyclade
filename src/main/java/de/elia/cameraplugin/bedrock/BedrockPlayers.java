package de.elia.cameraplugin.bedrock;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who plays on Bedrock, through Geyser, and for whom therefore the
 * adjustments of {@code bedrock} hold: what Bedrock cannot do - the key that
 * swaps the hands, a click in the chat, a right-click or a shift-click in a
 * window, the glowing outline - is offered another way.
 *
 * <p>A Bedrock player is told apart, the surest way first:</p>
 * <ol>
 *   <li>Geyser or Floodgate on this server says so - for a player whose
 *       Bedrock account is linked to one on Java as well, who plays under
 *       that name;</li>
 *   <li>his id is one Floodgate made up, which starts with sixteen zeros -
 *       Java hands out no such id, neither online nor offline; that holds
 *       while Floodgate sits on a proxy in front of this server alone;</li>
 *   <li>his name starts with {@code bedrock.name-prefix}, the way Floodgate
 *       marks them - a dot unless its config says otherwise. No name on Java
 *       has one; only a server in offline mode lets anybody pick it.</li>
 * </ol>
 *
 * <p>Nothing here grants a right or takes one away: it only changes how
 * things are shown and reached. A player taken for the wrong one gets
 * another way to the same things, nothing more.</p>
 */
public final class BedrockPlayers implements Listener {

    private final CamSettings settings;
    private final Messages messages;
    private final BedrockForms forms;
    /** Whether each player online plays on Bedrock, as found the first time it was asked. */
    private final Map<UUID, Boolean> known = new HashMap<>();

    public BedrockPlayers(CameraPlugin plugin) {
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.forms = new BedrockForms(plugin);
    }

    /** Looks Geyser and Floodgate up anew and forgets who was found, at the start and after a reload. */
    public void reload() {
        known.clear();
        forms.load();
    }

    /** The windows of Bedrock, see {@link BedrockForms}. */
    public BedrockForms forms() {
        return forms;
    }

    /** Whether he plays on Bedrock, whatever {@code bedrock.apply-to} says. */
    public boolean isBedrock(Player player) {
        if (!player.isOnline()) {
            // Not kept: whoever has left is forgotten, see onQuit.
            return detect(player);
        }
        return known.computeIfAbsent(player.getUniqueId(), id -> detect(player));
    }

    private boolean detect(Player player) {
        if (forms.knows(player)) {
            return true;
        }
        // The id Floodgate makes up out of the Xbox id: the upper half is nought.
        if (player.getUniqueId().getMostSignificantBits() == 0L) {
            return true;
        }
        String prefix = settings.getBedrockNamePrefix();
        return !prefix.isEmpty() && player.getName().startsWith(prefix);
    }

    /**
     * Whether the adjustments hold for him: a Bedrock player under
     * {@code bedrock.apply-to: bedrock}, every player under {@code all},
     * nobody under {@code false} - and never the console.
     */
    public boolean adapts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return false;
        }
        return switch (settings.getBedrockScope()) {
            case ALL -> true;
            case OFF -> false;
            case BEDROCK -> isBedrock(player);
        };
    }

    /**
     * Whether the windows of Bedrock reach him: the adjustments hold for him,
     * and Geyser or Floodgate on this server knows him. A player on Java has
     * none, also under {@code apply-to: all}.
     */
    public boolean formsReach(Player player) {
        return adapts(player) && forms.knows(player);
    }

    /** Whether {@code /cam menu} comes to him as windows of Bedrock, {@code bedrock.menu: forms}. */
    public boolean menuAsForms(Player player) {
        return settings.isBedrockMenuForms() && formsReach(player);
    }

    /** Whether an invitation comes to him as a window as well, {@code bedrock.invitation-forms}. */
    public boolean invitationForms(Player player) {
        return settings.isBedrockInvitationForms() && formsReach(player);
    }

    /** Whether the buttons in the chat say the command they run, {@code bedrock.chat-commands}. */
    public boolean writesCommands(CommandSender sender) {
        return settings.isBedrockChatCommands() && adapts(sender);
    }

    /**
     * One button behind a line of the chat, with a space in front of it: its
     * text, which shows {@code hover} on hovering and does {@code click} on a
     * click. Whoever cannot click in the chat, see {@link #writesCommands},
     * gets the command written out behind it as well,
     * {@code bedrock-chat-command}, to type it. The click stays on all the
     * same: a player on Java under {@code apply-to: all} can still use it.
     *
     * @param text  the text of the button, with colour codes
     * @param hover what hovering over it shows, with colour codes
     */
    @SuppressWarnings("deprecation")
    public List<BaseComponent> chatButton(CommandSender to, String text, String hover, ClickEvent click) {
        HoverEvent shown = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover)));
        List<BaseComponent> parts = new ArrayList<>();
        parts.add(new TextComponent(" "));
        String written = text;
        if (writesCommands(to) && messages.isMessageEnabled("bedrock-chat-command")) {
            written += messages.getMessage("bedrock-chat-command").replace("{command}", written(click));
        }
        for (BaseComponent part : TextComponent.fromLegacyText(written)) {
            part.setHoverEvent(shown);
            part.setClickEvent(click);
            parts.add(part);
        }
        return parts;
    }

    /**
     * What a click of the chat does, written out: the command it runs, the
     * one it puts into the chat line - one that waits for more, ending in a
     * space, with {@code ...} in its place -, or the address it opens.
     */
    private static String written(ClickEvent click) {
        String value = click.getValue();
        if (click.getAction() == ClickEvent.Action.SUGGEST_COMMAND && value.endsWith(" ")) {
            return value.trim() + " ...";
        }
        return value.trim();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        known.remove(event.getPlayer().getUniqueId());
    }
}
