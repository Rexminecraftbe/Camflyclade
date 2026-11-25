package de.elia.cameraplugin.interaction;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The click on his own body that ends camera mode reaches nothing else: not
 * the block behind the body, not the one he stands at once he is back.
 *
 * <p>The button is still down for a moment after that click. Camera mode ends
 * in the very tick the click arrives, the body goes and the player is back in
 * the mode he came from - and his client, button still held, goes on with
 * what it holds the button for. In the creative mode a held left click breaks
 * the block under the crosshair as soon as there is one, and with the body
 * gone, that is the block that stood behind it. A held right click uses what
 * is in his hand again, a block he got back a moment ago among it, in every
 * mode. None of that is meant: the click was for the body.</p>
 *
 * <p>So for a short while after such an end, every click of his on a block -
 * to break it, to dig it, to click it or to set something on it - and every
 * use of the item in his hand is turned away, the way it is in camera mode.
 * The block he hit comes back on his screen with that. The while is
 * {@link #GUARD_MILLIS} and his ping on top, the time the client
 * takes to learn that camera mode is over: until then it cannot break
 * anything in the creative mode, and its first try comes in only after. Every
 * try turned away starts the while anew, and for at least
 * {@link #MIN_HOLD_MILLIS}: a button held on breaks a block every few ticks in
 * the creative mode, and it is still the same click until he lets go.</p>
 *
 * <p>Ending camera mode in any other way - {@code /cam}, the exit key -
 * comes with no click on the world, and leaves him his hands at once.</p>
 *
 * <p>The section {@code exit-click-guard} of the config file switched it off
 * and set its time until both were fixed here: nobody could tell what to set
 * it to, and off it only let the click break a block.</p>
 */
public final class ExitClickGuard implements Listener {

    /**
     * How long the clicks are turned away after the end, his ping not yet
     * counted, in milliseconds. The first try of a held button comes in after
     * his ping, which is added on top, and up to two ticks more, his own and
     * the server's: 100 ms. Twice that and a little more leaves room for a
     * try that the server takes up late, when it lags.
     */
    private static final long GUARD_MILLIS = 300L;

    /** The most ping that is added: a ping beyond that is no ping, but a stalled connection. */
    private static final long MAX_PING_MILLIS = 1000L;

    /**
     * How long a try turned away keeps the guard up for, in milliseconds. A
     * held button tries again every five ticks in the creative mode, 250 ms,
     * and every four with a right click; the rest is room for a try that comes
     * a tick or two late, held up on the way or on the server. Shorter, the
     * guard would run out between two tries of a button still down.
     */
    private static final long MIN_HOLD_MILLIS = 400L;

    /** Until when the clicks of each player are turned away, as {@link System#currentTimeMillis()} counts. */
    private final Map<UUID, Long> heldUntil = new HashMap<>();

    /**
     * A player ended camera mode with a click on his own body: what that
     * click does on after it is turned away.
     */
    public void guard(Player player) {
        long ping = Math.min(MAX_PING_MILLIS, Math.max(0, player.getPing()));
        heldUntil.put(player.getUniqueId(), System.currentTimeMillis() + GUARD_MILLIS + ping);
    }

    /**
     * Breaking a block in the creative mode, starting to dig one in any
     * other, clicking one and using the item in his hand. A left click into
     * the air does nothing and is left alone.
     *
     * <p>Not only an event nobody cancelled: a right click into the air comes
     * cancelled already, with the item in his hand still to be used.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK
                || action == Action.RIGHT_CLICK_AIR) {
            turnAway(event.getPlayer(), event);
        }
    }

    /**
     * The break itself. The click above turns it away already; this one
     * holds when another plugin let that click through.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        turnAway(event.getPlayer(), event);
    }

    /** The same for a block set down, see {@link #onBreak}. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        turnAway(event.getPlayer(), event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heldUntil.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Turns the event away if the player's click is still the one that ended
     * camera mode, and keeps it that for another {@link #MIN_HOLD_MILLIS}: he
     * still holds the button.
     */
    private void turnAway(Player player, Cancellable event) {
        UUID id = player.getUniqueId();
        Long until = heldUntil.get(id);
        if (until == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (until <= now) {
            heldUntil.remove(id);
            return;
        }
        event.setCancelled(true);
        heldUntil.put(id, Math.max(until, now + MIN_HOLD_MILLIS));
    }
}
