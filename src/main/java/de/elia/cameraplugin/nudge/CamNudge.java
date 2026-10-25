package de.elia.cameraplugin.nudge;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets somebody standing at a body tell its camera player that he is waiting
 * there, {@code nudge}: a right click on the body, and the camera player hears
 * a sound and reads who it was in the action bar, wherever he is flying.
 *
 * <p>Only a player out in the world nudges. A camera player has no hands out
 * there, and one the camera player cannot see - hidden by a vanish plugin, say
 * - is not given away by a click of his: the body stays as silent as it always
 * was. A left click is still what it was, a hit on the body.</p>
 *
 * <p>Between two nudges of the same camera player lie at least
 * {@code nudge.cooldown} seconds, from whoever they come. One click sets off
 * several events - the "interact at" variant, the plain one, the one of the
 * armour stand, and each of them once for either hand - and all of them after
 * the first fall into that wait as well.</p>
 */
public final class CamNudge {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    /** Until when each camera player is left alone, as {@link System#currentTimeMillis()} counts. */
    private final Map<UUID, Long> quietUntil = new HashMap<>();

    public CamNudge(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /**
     * A player clicked the body of a camera player.
     *
     * @param nudger who clicked, not in camera mode himself
     * @param owner  the camera player whose body it is
     */
    public void nudge(Player nudger, Player owner) {
        if (!settings.isNudgeEnabled() || !owner.canSee(nudger)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (quietUntil.getOrDefault(owner.getUniqueId(), 0L) > now) {
            return;
        }
        quietUntil.put(owner.getUniqueId(), now + settings.getNudgeCooldown() * 1000L);
        settings.getNudgeSound().play(owner);
        plugin.getEffects().nudged(owner);
        if (messages.isMessageEnabled("nudge-received")) {
            plugin.getActionBar().warn(owner, messages.getMessage("nudge-received")
                    .replace("{player}", nudger.getName()));
        }
        // In his action bar as well and not in the chat: it answers his click,
        // the way the game answers a click on a bed at daytime.
        String sent = messages.isMessageEnabled("nudge-sent") ? messages.getMessage("nudge-sent") : "";
        if (!sent.isEmpty()) {
            nudger.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText(sent.replace("{player}", owner.getName())));
        }
    }

    /** Forgets the wait of a player leaving camera mode: the next one starts without it. */
    public void stopFor(Player player) {
        quietUntil.remove(player.getUniqueId());
    }

    public void onDisable() {
        quietUntil.clear();
    }
}
