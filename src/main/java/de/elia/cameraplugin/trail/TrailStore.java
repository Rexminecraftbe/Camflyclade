package de.elia.cameraplugin.trail;

import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.storage.BackgroundWriter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The files of the kept flights, {@code cam-trail.storage: file}: one for
 * each flight, {@code playerdata/trails/<uuid>/<start>.yml} in the folder of
 * the plugin, named after the player and when the flight began. A file is
 * written once, when the flight ends, and deleted with it - nothing is ever
 * written into it again, so there is never more to write than one flight.
 *
 * <p>Everything on the disk goes through the {@link BackgroundWriter}, in
 * turn: reading the heads of all files as well, so that it finds every file
 * handed over before it as it will be. Only the line of a single flight is
 * read on the main thread, when somebody looks at it - a few dozen KB at
 * most, see {@link FlightTrail#MAX_POINTS}.</p>
 */
final class TrailStore {

    /** The folder of everything the plugin keeps per player, inside the folder of the plugin. */
    private static final String PLAYER_FOLDER = "playerdata";

    /** The folder of the flights, in that folder. */
    private static final String FOLDER = "trails";

    private static final String ENDING = ".yml";

    private final JavaPlugin plugin;
    private final ConsoleLog log;
    private final BackgroundWriter writer;
    /** The files whose line could not be read, so that each is reported once. */
    private final Set<Path> reported = new HashSet<>();

    TrailStore(JavaPlugin plugin, ConsoleLog log, BackgroundWriter writer) {
        this.plugin = plugin;
        this.log = log;
        this.writer = writer;
    }

    private Path folder() {
        return plugin.getDataFolder().toPath().resolve(PLAYER_FOLDER).resolve(FOLDER);
    }

    private Path fileOf(UUID player, long startedAt) {
        return folder().resolve(player.toString()).resolve(startedAt + ENDING);
    }

    /** Writes the file of a flight that has ended. */
    void save(FlightTrail trail) {
        writer.write(fileOf(trail.playerId(), trail.startedAt()), TrailFile.text(trail.toData()),
                "A flight of " + trail.playerName() + " for /cam trail", null);
    }

    /** Deletes the file of a flight, and the folder of the player with his last one. */
    void delete(UUID player, long startedAt) {
        Path file = fileOf(player, startedAt);
        writer.run(() -> {
            try {
                Files.deleteIfExists(file);
                try {
                    Files.deleteIfExists(file.getParent());
                } catch (DirectoryNotEmptyException ex) {
                    // He has more flights.
                }
            } catch (IOException ex) {
                log.log(Level.WARNING, "A flight of /cam trail could not be deleted, " + file + ": " + ex);
            }
        });
    }

    /**
     * The line of a flight, read from its file - or from what is on its way
     * there. A file that cannot be read any more gives the two ends of the
     * flight, and the console is told once.
     */
    List<FlightTrail.Piece> pieces(FlightTrail trail) {
        Path file = fileOf(trail.playerId(), trail.startedAt());
        String problem;
        try {
            String text = writer.pendingText(file);
            TrailFile.Data data = TrailFile.read(text != null ? text : Files.readString(file, StandardCharsets.UTF_8));
            List<FlightTrail.Piece> pieces = data == null ? List.of() : FlightTrail.piecesOf(data.pieces());
            if (!pieces.isEmpty()) {
                return pieces;
            }
            problem = "it has no points";
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            problem = ex.toString();
        }
        if (reported.add(file)) {
            log.log(Level.WARNING, "The line of a flight of /cam trail could not be read from " + file
                    + ", only its two ends are shown: " + problem);
        }
        return trail.endsOnly();
    }

    /**
     * Reads what is said of every kept flight - not its line - on the thread
     * of the writer, after everything handed over before, and hands the
     * flights to the main thread. A file that cannot be read is left where it
     * is, and the console is told how many there are.
     */
    void readAll(Consumer<List<TrailFile.Data>> then) {
        writer.run(() -> {
            List<TrailFile.Data> found = readHeads();
            try {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> then.accept(found));
                }
            } catch (IllegalPluginAccessException ex) {
                // The plugin went off in the meantime: nobody is waiting any more.
            }
        });
    }

    private List<TrailFile.Data> readHeads() {
        List<TrailFile.Data> found = new ArrayList<>();
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return found;
        }
        List<Path> unreadable = new ArrayList<>();
        try (DirectoryStream<Path> players = Files.newDirectoryStream(folder)) {
            for (Path own : players) {
                UUID player = uuid(own.getFileName().toString());
                if (player == null || !Files.isDirectory(own)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(own, "*" + ENDING)) {
                    for (Path file : files) {
                        long startedAt = startedAt(file.getFileName().toString());
                        if (startedAt <= 0L) {
                            continue;
                        }
                        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            TrailFile.Data head = TrailFile.readHead(reader);
                            if (head == null) {
                                unreadable.add(file);
                            } else {
                                found.add(head.at(player, startedAt));
                            }
                        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
                            unreadable.add(file);
                        }
                    }
                } catch (IOException ex) {
                    log.log(Level.WARNING, "The flights of /cam trail in " + own + " could not be listed: " + ex);
                }
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "The flights of /cam trail could not be listed: " + ex);
        }
        if (!unreadable.isEmpty()) {
            log.log(Level.WARNING, unreadable.size() + " file(s) of /cam trail could not be read and were left"
                    + " where they are, e.g. " + unreadable.get(0));
        }
        return found;
    }

    private static UUID uuid(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** When a flight began, by the name of its file; 0 for a file of another name. */
    private static long startedAt(String fileName) {
        try {
            return Long.parseLong(fileName.substring(0, fileName.length() - ENDING.length()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
