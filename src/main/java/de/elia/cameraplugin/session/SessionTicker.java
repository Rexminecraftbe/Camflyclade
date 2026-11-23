package de.elia.cameraplugin.session;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The tick of each camera mode: one task of the server for each player in
 * camera mode, which runs everything that watches over that camera mode, see
 * {@link SessionTask}.
 *
 * <p>Camera mode sets a good twenty things going when it starts - the
 * particles, the wall, the checks on the body, the signal, the battery... -
 * each repeating, many of them every tick. Each used to be a task of the
 * server of its own. Now they all hang on the one task of their camera mode:
 * the scheduler of the server has one entry per camera player instead of
 * twenty, and what runs when is written down in one place.</p>
 *
 * <p>The order stays the one there was. The task of a camera mode is made
 * when the first of its parts starts - at the moment the first of the old
 * tasks used to be made -, and runs its parts in the order they started, as
 * the server ran the old tasks in the order they were made. A part starts
 * {@code delay} ticks after it is started, but at the earliest in the next
 * tick, and then runs every {@code period} ticks, just like
 * {@code runTaskTimer}. Some parts depend on that: the name over the body,
 * for one, is to find the body back in its spot within the same tick.</p>
 *
 * <p>A part ends itself, as a task of the server did, see
 * {@link SessionTask#cancel()}; the task of the camera mode ends with the
 * last of them. A part that throws is reported and goes on, as the server
 * keeps a task going that threw.</p>
 */
public final class SessionTicker {

    private final JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public SessionTicker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The parts of one camera mode, and the task of the server that runs them. */
    private final class Session implements Runnable {
        private final UUID id;
        private final List<Entry> entries = new ArrayList<>();
        private BukkitTask task;
        /** How often the task has run, the clock of the parts. */
        private long tick;

        Session(UUID id) {
            this.id = id;
        }

        @Override
        public void run() {
            tick++;
            // Counted before: a part started by another part runs in the next
            // tick at the earliest anyway.
            int count = entries.size();
            for (int i = 0; i < count; i++) {
                Entry entry = entries.get(i);
                if (entry.part.isCancelled() || tick < entry.next) {
                    continue;
                }
                entry.next = tick + entry.period;
                try {
                    entry.part.run();
                } catch (Throwable ex) {
                    plugin.getLogger().log(Level.WARNING, "A part of the camera mode of " + id
                            + " (" + entry.part.getClass().getName() + ") generated an exception", ex);
                }
            }
            entries.removeIf(entry -> entry.part.isCancelled());
            if (entries.isEmpty()) {
                task.cancel();
                sessions.remove(id, this);
            }
        }
    }

    /** A part and when it runs next, on the clock of its camera mode. */
    private static final class Entry {
        private final SessionTask part;
        private final long period;
        private long next;

        Entry(SessionTask part, long next, long period) {
            this.part = part;
            this.next = next;
            this.period = period;
        }
    }

    /**
     * Starts a part of the camera mode of a player, see
     * {@link SessionTask#runTaskTimer}.
     */
    void start(SessionTask part, Player player, long delay, long period) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            session = new Session(player.getUniqueId());
            session.task = Bukkit.getScheduler().runTaskTimer(plugin, session, 1L, 1L);
            sessions.put(player.getUniqueId(), session);
        }
        session.entries.add(new Entry(part, session.tick + Math.max(1L, delay), Math.max(1L, period)));
    }

    /** Ends every part of every camera mode, when the plugin is switched off. */
    public void shutdown() {
        for (Session session : new ArrayList<>(sessions.values())) {
            for (Entry entry : session.entries) {
                entry.part.cancel();
            }
            session.task.cancel();
        }
        sessions.clear();
    }
}
