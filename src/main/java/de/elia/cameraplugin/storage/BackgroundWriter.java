package de.elia.cameraplugin.storage;

import de.elia.cameraplugin.log.ConsoleLog;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Writes files of the plugin to the disk away from the main thread, so that a
 * slow disk - shared or on the network, as with many hosts - holds up only
 * this one thread and not the whole server.
 *
 * <p>There is one thread for it, and it writes everything in the order it was
 * handed over: two writes of the same file never overtake each other, the
 * newer one is always the one that stays. What is about to be written is
 * worked out on the main thread beforehand - only a finished text comes here,
 * nothing of the game is touched from this thread.</p>
 *
 * <p>Until a text is on the disk, {@link #pendingText(Path)} gives it to
 * whoever reads the file in the meantime: a player who leaves and comes
 * straight back, or a look at the statistics of somebody who just left, gets
 * what was handed over and not what the disk still says.</p>
 *
 * <p>Only for files no crash depends on: what is handed over and not written
 * yet is gone if the server goes down hard in that moment. The file of the
 * crash protection is therefore not written here, see
 * {@link de.elia.cameraplugin.crash.CamCrashGuard}. When the plugin is
 * switched off, {@link #finish()} waits until everything is written.</p>
 */
public final class BackgroundWriter {

    /** How long {@link #finish()} waits at a time before it says it is still writing, in seconds. */
    private static final long WAIT_STEP_SECONDS = 10L;

    /** How long {@link #finish()} waits at most, in seconds. */
    private static final long WAIT_MAX_SECONDS = 120L;

    /** How long the thread waits for work before it ends, in seconds; the next file starts it again. */
    private static final long IDLE_SECONDS = 30L;

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final ThreadPoolExecutor executor;

    /**
     * The text last handed over for a file and not yet on the disk, by the
     * file. Each entry is an object of its own, so that a write that is done
     * takes away only its own entry and not a newer one for the same file.
     */
    private final Map<Path, Pending> pending = new ConcurrentHashMap<>();

    /** A text on its way to the disk. Compared by identity on purpose, see {@link #pending}. */
    private static final class Pending {
        final String text;

        Pending(String text) {
            this.text = text;
        }
    }

    public BackgroundWriter(JavaPlugin plugin, ConsoleLog log) {
        this.plugin = plugin;
        this.log = log;
        // One thread and a queue without an end: the order is kept, and
        // nothing handed over is ever turned away while the plugin runs. A
        // daemon that ends when there is nothing to do, so that it never
        // keeps the server from stopping.
        this.executor = new ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                work -> {
                    Thread thread = new Thread(work, "CamFly file writer");
                    thread.setDaemon(true);
                    return thread;
                });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Writes a text into a file as a whole: under another name first and then
     * moved into place, so that a crash in the middle of it leaves no half a
     * file.
     *
     * @param what      what the file holds, for the warning when it cannot be written
     * @param onFailure run on the main thread when it could not be written, or {@code null}
     */
    public void write(Path target, String text, String what, Runnable onFailure) {
        Path key = key(target);
        Pending entry = new Pending(text);
        pending.put(key, entry);
        submit(() -> {
            try {
                writeWhole(key, text);
            } catch (IOException | RuntimeException ex) {
                log.log(Level.WARNING, what + " could not be written: " + ex);
                if (onFailure != null) {
                    onMainThread(onFailure);
                }
            } finally {
                pending.remove(key, entry);
            }
        });
    }

    /**
     * Adds a line at the end of a file, which is made if it is not there.
     *
     * @param what what the file is, for the warning when the line cannot be written
     */
    public void append(Path target, String line, String what) {
        Path key = key(target);
        submit(() -> {
            try {
                Files.createDirectories(key.getParent());
                Files.writeString(key, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ex) {
                log.log(Level.WARNING, what + ": a line could not be written to " + key + ": " + ex);
            }
        });
    }

    /**
     * Does a piece of work on files in turn with everything handed over
     * before it, on the thread of the writer. It must not touch anything of
     * the game.
     */
    public void run(Runnable work) {
        submit(() -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "A file of CamFly could not be dealt with: " + ex);
            }
        });
    }

    /**
     * The text handed over for a file that is not on the disk yet, or
     * {@code null} when the disk has the newest one.
     */
    public String pendingText(Path target) {
        Pending entry = pending.get(key(target));
        return entry == null ? null : entry.text;
    }

    /**
     * Every file directly in a folder that has a text on its way to the disk,
     * with that text - also one that is not on the disk at all yet.
     */
    public Map<Path, String> pendingIn(Path folder) {
        Path inside = key(folder);
        Map<Path, String> found = new HashMap<>();
        for (Map.Entry<Path, Pending> entry : pending.entrySet()) {
            if (inside.equals(entry.getKey().getParent())) {
                found.put(entry.getKey(), entry.getValue().text);
            }
        }
        return found;
    }

    /**
     * Waits until everything handed over is on the disk, when the plugin is
     * switched off. What comes after that is written straight away, on the
     * thread that hands it over.
     */
    public void finish() {
        executor.shutdown();
        long waited = 0L;
        try {
            while (!executor.awaitTermination(WAIT_STEP_SECONDS, TimeUnit.SECONDS)) {
                waited += WAIT_STEP_SECONDS;
                int left = executor.getQueue().size() + executor.getActiveCount();
                if (waited >= WAIT_MAX_SECONDS) {
                    log.log(Level.SEVERE, "CamFly gave up waiting for the disk after " + waited + " seconds, "
                            + left + " file(s) of statistics, spots, settings or the cam log may not have been"
                            + " written.");
                    return;
                }
                log.log(Level.WARNING, "CamFly is still writing " + left + " file(s), the disk is slow ...");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ex) {
            // After finish(): the plugin is being switched off, and nobody
            // would wait for the thread any more.
            task.run();
        }
    }

    private void onMainThread(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** Writes a file under another name and moves it into place. */
    private static void writeWhole(Path target, String text) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectories(target.getParent());
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
