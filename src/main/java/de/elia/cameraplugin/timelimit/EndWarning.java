package de.elia.cameraplugin.timelimit;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.entity.Player;

/**
 * Tells a camera player that his time is nearly up, {@code end-warning}: a
 * sound and a line in the action bar once the time left has come down to one
 * of {@code end-warning.seconds}. For the time limit and for the battery
 * alike, so that neither takes him back to his body without a word.
 * {@code afk-end} warns with the same sound and line, but at a point of its
 * own, see {@link #warn}. The time left goes on to
 * {@code shader-effects.end-warning} as well, see
 * {@link de.elia.cameraplugin.shader.CamEffects#timeLeft}.
 *
 * <p>Only a mark the time passes on the way down counts. A battery that holds
 * twenty seconds at the start has nothing to say at thirty: that is in the
 * bossbar from the first second.</p>
 */
public final class EndWarning {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;

    public EndWarning(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
    }

    /**
     * Warns when the time left has just come down to one of the marks. One
     * warning at most, should a single step pass more than one.
     *
     * @param before what was left at the look before, in seconds
     * @param left   what is left now
     * @param key    the text: {@code end-warning-time-limit} or
     *               {@code end-warning-battery}
     */
    public void check(Player player, double before, double left, String key) {
        // Whatever end-warning.enabled says: shader-effects.end-warning has a
        // switch of its own.
        plugin.getEffects().timeLeft(player, left);
        if (!settings.isEndWarningEnabled() || left <= 0.0) {
            return;
        }
        for (int mark : settings.getEndWarningSeconds()) {
            if (before > mark && left <= mark) {
                warn(player, left, key);
                return;
            }
        }
    }

    /**
     * Warns right away: the sound of {@code sounds.end-warning} and the text
     * in the action bar, with {@code {time}} for the time left.
     *
     * @param left what is left, in seconds
     * @param key  the text, e.g. {@code end-warning-afk}
     */
    public void warn(Player player, double left, String key) {
        settings.getEndWarningSound().play(player);
        if (messages.isMessageEnabled(key)) {
            plugin.getActionBar().warn(player, messages.getMessage(key)
                    .replace("{time}", CamTimeLimit.formatDuration((long) Math.ceil(left))));
        }
    }
}
