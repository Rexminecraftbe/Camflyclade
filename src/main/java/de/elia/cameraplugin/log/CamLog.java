package de.elia.cameraplugin.log;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.EndReason;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Writes down every start and end of camera mode, the section
 * {@code cam-log}: who, when, where, why it ended, how long it ran and how far
 * the camera got. One file a day, {@code logs/<date>.log} in the folder of the
 * plugin, so that a server admin can look up afterwards who was scouting
 * where.
 *
 * <p>The lines are in English, as the server console is, and are not meant
 * to be translated: they are a record, and a record reads the same whatever
 * language the players get their messages in.</p>
 */
public final class CamLog {

    /** The folder of the files, inside the folder of the plugin. */
    private static final String FOLDER = "logs";

    private static final String ENDING = ".log";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JavaPlugin plugin;
    private final CamSettings settings;
    private final ConsoleLog log;
    /** The day the last line was written on, to clear out old files once a day. */
    private LocalDate lastDay;

    public CamLog(JavaPlugin plugin, CamSettings settings, ConsoleLog log) {
        this.plugin = plugin;
        this.settings = settings;
        this.log = log;
    }

    /** Writes down a camera mode that has just started. */
    public void started(Player player, CameraData data) {
        if (!settings.isCamLogEnabled()) {
            return;
        }
        write(player.getName() + " (" + player.getUniqueId() + ") started cam mode at "
                + where(data.getBody().getLocation()));
    }

    /**
     * Writes down a camera mode that is ending.
     *
     * @param cameraAt where the camera was when it ended, before the player
     *                 was put back at his body
     */
    public void ended(Player player, CameraData data, EndReason reason, Location cameraAt) {
        if (!settings.isCamLogEnabled()) {
            return;
        }
        long seconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - data.getStartedAt());
        write(player.getName() + " ended cam mode (" + reason.logName() + ") after "
                + CamTimeLimit.formatDuration(seconds) + ", flew " + Math.round(data.getDistanceFlown())
                + " blocks, at most " + Math.round(data.getFarthestFromBody())
                + " blocks from the body, camera last at " + where(cameraAt));
    }

    /** A spot as the log writes it: the world and the block. */
    private static String where(Location at) {
        String world = at.getWorld() == null ? "?" : at.getWorld().getName();
        return world + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    private void write(String text) {
        LocalDate today = LocalDate.now();
        if (!today.equals(lastDay)) {
            lastDay = today;
            removeOldFiles(today);
        }
        if (settings.logsCamToConsole()) {
            log.log(Level.INFO, "[cam-log] " + text);
        }
        Path file = plugin.getDataFolder().toPath().resolve(FOLDER).resolve(today + ENDING);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, LocalTime.now().format(TIME) + " " + text + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.log(Level.WARNING, "cam-log: a line could not be written to " + file + ": " + ex);
        }
    }

    /**
     * Deletes the files older than {@code keep-days}. Only files named after
     * a day are touched - whatever else somebody puts into the folder stays.
     */
    private void removeOldFiles(LocalDate today) {
        int keep = settings.getCamLogKeepDays();
        Path folder = plugin.getDataFolder().toPath().resolve(FOLDER);
        if (keep <= 0 || !Files.isDirectory(folder)) {
            return;
        }
        LocalDate oldestKept = today.minusDays(keep - 1L);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + ENDING)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                LocalDate day;
                try {
                    day = LocalDate.parse(name.substring(0, name.length() - ENDING.length()));
                } catch (DateTimeParseException ex) {
                    continue;
                }
                if (day.isBefore(oldestKept)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "cam-log: old files could not be cleared out of " + folder + ": " + ex);
        }
    }

    /** Clears out old files again with the next line, after a reload may have changed {@code keep-days}. */
    public void reset() {
        lastDay = null;
    }
}
