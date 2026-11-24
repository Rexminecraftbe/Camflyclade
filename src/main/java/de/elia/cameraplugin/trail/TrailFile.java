package de.elia.cameraplugin.trail;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The text of the file of one kept flight,
 * {@code playerdata/trails/<uuid>/<start>.yml}: a flight written as YAML, and
 * read back from it. Where the file lies and when it is written is up to
 * {@link TrailStore}; this only turns the one into the other, so the fast
 * tests check it without a server or a disk.
 *
 * <p>What the menu and the chat say of a flight - who, when, how long, how
 * far, from where to where - stands at the top, the points of its line last,
 * under {@code pieces}. So {@link #readHead} can stop reading where the line
 * begins: at the start of the server the head of every kept flight is read,
 * and its line only once somebody looks at it.</p>
 */
final class TrailFile {

    /** Which layout a file has, for a later version that changes it. */
    static final int FORMAT = 1;

    /** The key the points stand under, the last one of the file. */
    private static final String PIECES = "pieces";

    /** What stands at the top of every file, for whoever finds one and wonders. */
    private static final List<String> HEADER = List.of(
            "CamFly: one flight of the camera of this player, for /cam trail and the flights in the admin menu.",
            "cam-trail.flights and cam-trail.keep-days delete it, or an admin in the menu. Deleting the file",
            "deletes the flight.");

    private TrailFile() {
    }

    /** A spot: the start of a flight, where the body stood, or its end, where the camera was last. */
    record Place(String world, UUID worldId, double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * One unbroken piece of the line, in one world.
     *
     * @param coords x, y and z of each point, one after the other
     */
    record Line(String world, UUID worldId, double[] coords) {
    }

    /**
     * A flight as its file has it.
     *
     * @param endedAt when it ended, never before it began
     * @param pieces  the line, empty when only the head was read
     */
    record Data(UUID player, String name, long startedAt, long endedAt, double distance, Place start,
                Place last, List<Line> pieces) {

        /** The same flight, as the path of its file says whose it is and when it began. */
        Data at(UUID player, long startedAt) {
            return new Data(player, name, startedAt, Math.max(startedAt, endedAt), distance, start, last, pieces);
        }
    }

    /** The text of the file of a flight, as {@link #read} reads it back. */
    static String text(Data flight) {
        YamlConfiguration file = new YamlConfiguration();
        file.options().setHeader(HEADER);
        file.set("format", FORMAT);
        file.set("player", flight.player().toString());
        file.set("name", flight.name());
        file.set("started", flight.startedAt());
        file.set("ended", flight.endedAt());
        file.set("distance", Math.round(flight.distance() * 10.0) / 10.0);
        writePlace(file, "start", flight.start());
        writePlace(file, "last", flight.last());
        // Last, so that the head can be read without it.
        List<Map<String, Object>> pieces = new ArrayList<>();
        for (Line line : flight.pieces()) {
            Map<String, Object> piece = new LinkedHashMap<>();
            piece.put("world", line.world());
            if (line.worldId() != null) {
                piece.put("world-id", line.worldId().toString());
            }
            piece.put("points", points(line.coords()));
            pieces.add(piece);
        }
        file.set(PIECES, pieces);
        return file.saveToString();
    }

    /**
     * Reads a whole flight from the text of its file, its line with it. A
     * piece without a world, or with no point in it, is left out.
     *
     * @return the flight, or {@code null} when the file does not say whose it is or when it began
     * @throws InvalidConfigurationException when the text is no YAML
     */
    static Data read(String text) throws InvalidConfigurationException {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString(text);
        Data head = head(file);
        if (head == null) {
            return null;
        }
        List<Line> pieces = new ArrayList<>();
        for (Map<?, ?> piece : file.getMapList(PIECES)) {
            Object world = piece.get("world");
            double[] coords = coords(piece.get("points"));
            if (world == null || String.valueOf(world).isEmpty() || coords.length == 0) {
                continue;
            }
            pieces.add(new Line(String.valueOf(world), uuid(piece.get("world-id")), coords));
        }
        return new Data(head.player(), head.name(), head.startedAt(), head.endedAt(), head.distance(),
                head.start(), head.last(), pieces);
    }

    /**
     * Reads what a flight is without its line: the lines of the file up to
     * where the points begin.
     *
     * @return the flight with no pieces, or {@code null} when the file does
     *         not say whose it is or when it began
     * @throws InvalidConfigurationException when the text is no YAML
     */
    static Data readHead(BufferedReader reader) throws IOException, InvalidConfigurationException {
        StringBuilder head = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && !line.startsWith(PIECES + ":")) {
            head.append(line).append('\n');
        }
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString(head.toString());
        return head(file);
    }

    private static Data head(YamlConfiguration file) {
        UUID player = uuid(file.get("player"));
        long startedAt = file.getLong("started");
        Place start = readPlace(file.getConfigurationSection("start"));
        if (player == null || startedAt <= 0L || start == null) {
            return null;
        }
        Place last = readPlace(file.getConfigurationSection("last"));
        return new Data(player, file.getString("name", player.toString()), startedAt,
                Math.max(startedAt, file.getLong("ended")), Math.max(0.0, file.getDouble("distance")), start,
                last == null ? start : last, List.of());
    }

    private static void writePlace(YamlConfiguration file, String path, Place place) {
        file.set(path + ".world", place.world());
        if (place.worldId() != null) {
            file.set(path + ".world-id", place.worldId().toString());
        }
        file.set(path + ".x", round(place.x()));
        file.set(path + ".y", round(place.y()));
        file.set(path + ".z", round(place.z()));
        file.set(path + ".yaw", round(place.yaw()));
        file.set(path + ".pitch", round(place.pitch()));
    }

    /** A place, or {@code null} without a world. */
    private static Place readPlace(ConfigurationSection section) {
        String world = section == null ? null : section.getString("world");
        if (world == null || world.isEmpty()) {
            return null;
        }
        return new Place(world, uuid(section.get("world-id")), section.getDouble("x"), section.getDouble("y"),
                section.getDouble("z"), (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    /** The points as one line of numbers, x, y and z of each after the other, to the hundredth of a block. */
    static String points(double[] coords) {
        StringBuilder text = new StringBuilder(coords.length * 8);
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(round(coords[i]));
        }
        return text.toString();
    }

    /** The numbers of {@link #points}; whatever is no number, and a point not whole, is left out. */
    static double[] coords(Object points) {
        if (points == null) {
            return new double[0];
        }
        String[] words = String.valueOf(points).trim().split("\\s+");
        double[] coords = new double[words.length - words.length % 3];
        int size = 0;
        for (int i = 0; i + 2 < words.length; i += 3) {
            try {
                double x = Double.parseDouble(words[i]);
                double y = Double.parseDouble(words[i + 1]);
                double z = Double.parseDouble(words[i + 2]);
                if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                    coords[size++] = x;
                    coords[size++] = y;
                    coords[size++] = z;
                }
            } catch (NumberFormatException ex) {
                // Left out, the rest of the line stays.
            }
        }
        if (size == coords.length) {
            return coords;
        }
        double[] kept = new double[size];
        System.arraycopy(coords, 0, kept, 0, size);
        return kept;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
