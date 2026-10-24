package de.elia.cameraplugin.start;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Starts and ends camera mode the way a player asks for it with
 * {@code /cam}: every check that can turn him away, in the order that tells
 * him the reason that really stands in the way, and the cost last of all.
 * With {@code start-delay} he waits a few seconds in between, see
 * {@link StartDelay}.
 */
public final class CamStarter {

    private final CameraPlugin plugin;
    private final Messages messages;

    public CamStarter(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
    }

    /** Ends a running camera mode, calls off a start he is waiting for, or starts one. */
    public void toggle(Player player) {
        if (plugin.isInCameraMode(player)) {
            plugin.exitCameraMode(player, EndReason.COMMAND);
            messages.sendConfiguredMessage(player, "camera-off");
        } else if (!plugin.getStartDelay().callOff(player, "start-delay-cancelled")) {
            request(player);
        }
    }

    /**
     * Starts camera mode right away, or with {@code start-delay} after the
     * wait. The checks come before the wait as well: whoever may not start
     * hears it at once, instead of standing still for nothing.
     */
    private void request(Player player) {
        if (!plugin.getSettings().isStartDelayEnabled()) {
            start(player);
        } else if (mayStart(player) && plugin.getStartCost().canPay(player)) {
            plugin.getStartDelay().begin(player, () -> start(player));
        }
    }

    /**
     * Starts camera mode, unless one of the checks stands in the way. The
     * cost comes last of all: it is only taken once nothing else can turn him
     * away, so that nobody pays for a start that does not happen.
     *
     * @return whether camera mode started; if not, he has been told why
     */
    public boolean start(Player player) {
        if (!mayStart(player) || !plugin.getStartCost().pay(player)) {
            return false;
        }
        plugin.enterCameraMode(player);
        messages.sendConfiguredMessage(player, "camera-on");
        return true;
    }

    /**
     * Whether the player may start camera mode right now, the cost left
     * aside. The checks that only ask him to wait come first, the ones about
     * where he is and what he carries after them.
     *
     * @return whether he may; if not, he has been told why
     */
    private boolean mayStart(Player player) {
        StartChecks checks = plugin.getStartChecks();
        // First of all: neither waiting nor going somewhere else helps a
        // banned player.
        if (!plugin.getBan().checkStart(player)) {
            return false;
        }
        // Before the cooldown: "wait ten more seconds" tells a spectator
        // the wrong thing - waiting does not let a spectator start either.
        if (!checks.checkCamSpectator(player)) {
            return false;
        }
        CamTimeLimit timeLimit = plugin.getTimeLimit();
        if (timeLimit.isCooldownActive(player)) {
            long remaining = timeLimit.getCooldownRemaining(player);
            if (messages.isMessageEnabled("cooldown-text")) {
                String msg = messages.getMessage("cooldown-text").replace("%time%", CamTimeLimit.formatDuration(remaining));
                player.sendMessage(ChatColor.RED + msg);
            }
            return false;
        }
        if (!plugin.getBattery().checkStart(player)) {
            return false;
        }
        if (!checks.checkCamArea(player)) {
            return false;
        }
        if (!checks.checkCamMedium(player)) {
            return false;
        }
        if (!checks.checkCamFalling(player)) {
            return false;
        }
        // Before the safety check: a harmful effect usually hurts as
        // well, and then "wait five more seconds" would come first and
        // the reason that really stands in the way only after it.
        if (!checks.checkCamEffects(player)) {
            return false;
        }
        return checks.checkCamSafety(player);
    }
}
