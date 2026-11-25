package de.elia.cameraplugin.trail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The way the camera of one player flew, for {@code /cam trail}: a line of
 * points from his body to where the camera was last. Wherever the camera was
 * put somewhere else - by {@code /cam back}, a portal or a command - the line
 * breaks off and goes on in a new piece, so that nothing is drawn where it
 * never flew.
 *
 * <p>A point is noted every {@link #step} blocks. Before there would be more
 * than {@link #MAX_POINTS}, every other one is dropped and the step doubles: a
 * long flight keeps its whole way, only coarser, and no flight takes more
 * memory than that.</p>
 *
 * <p>A flight kept in a file ({@code storage: file}) holds only what
 * is said of it in memory - who, when, how long, how far, from where to where.
 * Its line is read from the file when somebody looks at it, and kept while
 * the memory has room for it, see {@link #keepInFile}.</p>
 */
final class FlightTrail {

    /** The most points a flight keeps, three numbers each: a few dozen KB at most. */
    static final int MAX_POINTS = 1024;

    /** How far apart two points are at first, in blocks. */
    private static final double FIRST_STEP = 1.0;

    /**
     * How far a step may start from where the last one ended, in blocks,
     * before the camera counts as put there instead of flown. A step starts
     * exactly where the one before ended; this only leaves room for rounding.
     */
    private static final double JUMP = 0.5;

    /** One unbroken piece of the way, in one world. */
    static final class Piece {
        private final UUID worldId;
        private final String worldName;
        /** x, y and z of each point, one after the other. */
        private double[] coords = new double[3 * 16];
        private int size;

        private Piece(Location at) {
            World world = at.isWorldLoaded() ? at.getWorld() : null;
            this.worldId = world == null ? null : world.getUID();
            this.worldName = world == null ? "?" : world.getName();
            add(at);
        }

        /** A piece read from the file of a flight. */
        private Piece(TrailFile.Line line) {
            this.worldId = line.worldId();
            this.worldName = line.world();
            this.coords = line.coords().clone();
            this.size = coords.length / 3;
        }

        /** Whether it lies in this world: by its id, or by its name where the file had no id. */
        boolean isIn(World world) {
            return world != null && (worldId != null ? world.getUID().equals(worldId)
                    : world.getName().equals(worldName));
        }

        String worldName() {
            return worldName;
        }

        int size() {
            return size;
        }

        double x(int i) {
            return coords[3 * i];
        }

        double y(int i) {
            return coords[3 * i + 1];
        }

        double z(int i) {
            return coords[3 * i + 2];
        }

        /** How far it is from one point to the next, in blocks. */
        double length(int i) {
            double dx = x(i + 1) - x(i);
            double dy = y(i + 1) - y(i);
            double dz = z(i + 1) - z(i);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** How far the spot is from the last point, squared. */
        private double distanceSquaredToLast(Location at) {
            double dx = at.getX() - x(size - 1);
            double dy = at.getY() - y(size - 1);
            double dz = at.getZ() - z(size - 1);
            return dx * dx + dy * dy + dz * dz;
        }

        private void add(Location at) {
            if (3 * size == coords.length) {
                double[] more = new double[coords.length * 2];
                System.arraycopy(coords, 0, more, 0, coords.length);
                coords = more;
            }
            coords[3 * size] = at.getX();
            coords[3 * size + 1] = at.getY();
            coords[3 * size + 2] = at.getZ();
            size++;
        }

        /** The piece as the file of the flight writes it. */
        private TrailFile.Line toLine() {
            return new TrailFile.Line(worldName, worldId, Arrays.copyOf(coords, 3 * size));
        }

        /** Drops every other point, keeping the first and the last. */
        private void thinOut() {
            int kept = 0;
            for (int i = 0; i < size; i++) {
                if (i % 2 == 0 || i == size - 1) {
                    System.arraycopy(coords, 3 * i, coords, 3 * kept, 3);
                    kept++;
                }
            }
            size = kept;
        }
    }

    private final UUID playerId;
    private final String playerName;
    /**
     * Where the body stood, the start of the way even once its first piece is
     * dropped. Its world may be missing while it is not loaded, see
     * {@link #located}.
     */
    private final Location start;
    /** The name of the world of {@link #start}, to find it again once it is loaded. */
    private final String startWorld;
    private final long startedAt;
    /** When the flight ended, or 0 while the camera still flies. */
    private long endedAt;
    /**
     * The pieces of the way while they are held for good: while the camera
     * flies it, and while the flight is kept in memory only. {@code null} for
     * a flight kept in a file, see {@link #cached}.
     */
    private List<Piece> pieces;
    /** The pieces of a flight kept in a file, as long as the memory has room for them. */
    private SoftReference<List<Piece>> cached;
    /** Reads the pieces of a flight kept in a file from it, when they are not {@link #cached}. */
    private Supplier<List<Piece>> loader;
    /** Whether it has a file, written or on its way. */
    private boolean saved;
    private double step = FIRST_STEP;
    private int points;
    /** How far the camera flew, every step counted. */
    private double distanceFlown;
    /** Where the camera was after the last step, to tell a step from being put somewhere. */
    private Location lastSeen;
    /** The name of the world of {@link #lastSeen}. */
    private String lastWorld;

    FlightTrail(UUID playerId, String playerName, Location start) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.start = start.clone();
        this.startWorld = worldName(start);
        this.startedAt = System.currentTimeMillis();
        this.lastSeen = start.clone();
        this.lastWorld = startWorld;
        this.pieces = new ArrayList<>();
        pieces.add(new Piece(start));
        points = 1;
    }

    /** A flight read from its file, its line not yet: see {@link #keepInFile}. */
    private FlightTrail(TrailFile.Data head) {
        this.playerId = head.player();
        this.playerName = head.name();
        this.start = location(head.start());
        this.startWorld = head.start().world();
        this.startedAt = head.startedAt();
        this.endedAt = head.endedAt();
        this.distanceFlown = head.distance();
        this.lastSeen = location(head.last());
        this.lastWorld = head.last().world();
        this.saved = true;
    }

    /**
     * A flight read from its file, with what is said of it; its line comes
     * from the file once somebody looks at it, see {@link #keepInFile}.
     */
    static FlightTrail stored(TrailFile.Data head) {
        return new FlightTrail(head);
    }

    /** The pieces of a line read from a file, the oldest first; a line without a point is left out. */
    static List<Piece> piecesOf(List<TrailFile.Line> lines) {
        List<Piece> read = new ArrayList<>();
        for (TrailFile.Line line : lines) {
            if (line.coords().length >= 3) {
                read.add(new Piece(line));
            }
        }
        return read;
    }

    /**
     * A step of the camera. One that does not start where the last one ended
     * follows a teleport, and opens a new piece.
     */
    void moved(Location from, Location to) {
        if (!close(lastSeen, from)) {
            breakOff(from);
        }
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            distanceFlown += from.distance(to);
        }
        lastSeen = to.clone();
        lastWorld = worldName(to);
        Piece piece = pieces.get(pieces.size() - 1);
        if (!piece.isIn(to.getWorld())) {
            breakOff(to);
        } else if (piece.distanceSquaredToLast(to) >= step * step) {
            add(piece, to);
        }
    }

    /** The flight is over, with the camera at this spot. */
    void end(Location cameraAt) {
        if (close(lastSeen, cameraAt)) {
            finishPiece();
        } else {
            breakOff(cameraAt);
        }
        lastSeen = cameraAt.clone();
        lastWorld = worldName(cameraAt);
        endedAt = System.currentTimeMillis();
    }

    /**
     * The flight is kept in a file from now on, which is written or on its
     * way: its line goes from memory as soon as the memory needs the room,
     * and is read from the file again when it is looked at.
     *
     * @param loader reads the pieces from the file; never none
     */
    void keepInFile(Supplier<List<Piece>> loader) {
        this.loader = loader;
        if (pieces != null) {
            cached = new SoftReference<>(Collections.unmodifiableList(new ArrayList<>(pieces)));
            pieces = null;
        }
        saved = true;
    }

    /** Whether it has a file, written or on its way. */
    boolean saved() {
        return saved;
    }

    /** The flight as its file has it, see {@link TrailFile}. */
    TrailFile.Data toData() {
        List<TrailFile.Line> lines = new ArrayList<>();
        for (Piece piece : pieces()) {
            lines.add(piece.toLine());
        }
        return new TrailFile.Data(playerId, playerName, startedAt, endedAt, distanceFlown,
                place(start, startWorld), place(lastSeen, lastWorld), lines);
    }

    /**
     * The line of a flight whose file cannot be read any more: where the body
     * stood and where the camera was last, each a piece of its own - the two
     * ends are drawn, the way between them is not known.
     */
    List<Piece> endsOnly() {
        return List.of(new Piece(line(place(start, startWorld))), new Piece(line(place(lastSeen, lastWorld))));
    }

    /**
     * Ends the piece where the camera was last and opens a new one at the
     * spot it was put at.
     */
    private void breakOff(Location at) {
        finishPiece();
        makeRoom();
        pieces.add(new Piece(at));
        points++;
    }

    /** Adds the last spot of the camera to the piece, unless it is there already. */
    private void finishPiece() {
        Piece piece = pieces.get(pieces.size() - 1);
        if (piece.isIn(lastSeen.getWorld()) && piece.distanceSquaredToLast(lastSeen) > 0.0) {
            add(piece, lastSeen);
        }
    }

    /** Adds a point to the piece the camera is flying in now, the last one. */
    private void add(Piece piece, Location at) {
        makeRoom();
        piece.add(at);
        points++;
    }

    /**
     * Halves the points before one more would be too many. A piece of one or
     * two points has nothing to give, so should pieces like that make up most
     * of it - a camera sent back and forth over and over - the oldest ones go.
     * The last piece, the one the camera is in, always stays.
     */
    private void makeRoom() {
        if (points < MAX_POINTS) {
            return;
        }
        step *= 2.0;
        points = 0;
        for (Piece piece : pieces) {
            piece.thinOut();
            points += piece.size();
        }
        while (points >= MAX_POINTS && pieces.size() > 1) {
            points -= pieces.remove(0).size();
        }
    }

    private static boolean close(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.distanceSquared(b) <= JUMP * JUMP;
    }

    private static String worldName(Location at) {
        return at.isWorldLoaded() ? at.getWorld().getName() : "?";
    }

    private static TrailFile.Place place(Location at, String world) {
        World loaded = at.isWorldLoaded() ? at.getWorld() : null;
        return new TrailFile.Place(world, loaded == null ? null : loaded.getUID(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch());
    }

    private static TrailFile.Line line(TrailFile.Place place) {
        return new TrailFile.Line(place.world(), place.worldId(), new double[]{place.x(), place.y(), place.z()});
    }

    /** A spot of a file, in its world if that is loaded, else without one for now. */
    private static Location location(TrailFile.Place place) {
        World world = place.worldId() != null ? Bukkit.getWorld(place.worldId()) : null;
        if (world == null) {
            world = Bukkit.getWorld(place.world());
        }
        return new Location(world, place.x(), place.y(), place.z(), place.yaw(), place.pitch());
    }

    /**
     * A copy of a spot in its world: one that was not loaded when the
     * flight was read is looked up again by its name, so a world loaded later
     * - by another plugin - is found.
     */
    private static Location located(Location at, String world) {
        if (!at.isWorldLoaded() && world != null) {
            at.setWorld(Bukkit.getWorld(world));
        }
        return at.clone();
    }

    UUID playerId() {
        return playerId;
    }

    String playerName() {
        return playerName;
    }

    /** When it began, as {@link System#currentTimeMillis()} counts - which tells one flight from another. */
    long startedAt() {
        return startedAt;
    }

    /** Whether the camera is still flying it. */
    boolean isFlying() {
        return endedAt == 0L;
    }

    /** How long the flight took, or has taken so far, in milliseconds. */
    long duration() {
        return (isFlying() ? System.currentTimeMillis() : endedAt) - startedAt;
    }

    /** When it ended, as {@link System#currentTimeMillis()} counts, 0 while it lasts. */
    long endedAt() {
        return endedAt;
    }

    double distanceFlown() {
        return distanceFlown;
    }

    /** Where the body stood; without a world while that is not loaded. */
    Location start() {
        return located(start, startWorld);
    }

    /** The name of the world where the body stood. */
    String startWorld() {
        return startWorld;
    }

    /** Where the camera was last; without a world while that is not loaded. */
    Location last() {
        return located(lastSeen, lastWorld);
    }

    /** The name of the world where the camera was last. */
    String lastWorld() {
        return lastWorld;
    }

    /**
     * The pieces of the way, the oldest first; never none. Not to be changed.
     * For a flight kept in a file they are read from it, if the memory had
     * to give them up.
     */
    List<Piece> pieces() {
        if (pieces != null) {
            return Collections.unmodifiableList(pieces);
        }
        List<Piece> kept = cached == null ? null : cached.get();
        if (kept == null) {
            kept = Collections.unmodifiableList(loader.get());
            cached = new SoftReference<>(kept);
        }
        return kept;
    }
}
