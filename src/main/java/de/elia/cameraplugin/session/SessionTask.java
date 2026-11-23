package de.elia.cameraplugin.session;

import org.bukkit.entity.Player;

/**
 * Something that runs again and again while a player is in camera mode, on
 * the tick of that camera mode, see {@link SessionTicker}.
 *
 * <p>Used the way a {@link org.bukkit.scheduler.BukkitRunnable} is: written
 * as {@link #run()}, started with {@link #runTaskTimer}, ended with
 * {@link #cancel()} - from inside, or from outside by whoever keeps it.</p>
 */
public abstract class SessionTask implements Runnable {

    private volatile boolean cancelled;

    /**
     * Starts it on the tick of the camera mode of a player: first after
     * {@code delay} ticks, at the earliest in the next one, then every
     * {@code period} ticks, until it is cancelled.
     *
     * @return this, as {@code runTaskTimer} hands back the task
     */
    public final SessionTask runTaskTimer(SessionTicker ticker, Player player, long delay, long period) {
        ticker.start(this, player, delay, period);
        return this;
    }

    /** Ends it: it does not run again, and the camera mode lets go of it. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
