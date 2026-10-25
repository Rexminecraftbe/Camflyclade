package de.elia.cameraplugin.trail;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
            World world = at.getWorld();
            this.worldId = world == null ? null : world.getUID();
            this.worldName = world == null ? "?" : world.getName();
            add(at);
        }

        boolean isIn(World world) {
            return world != null && world.getUID().equals(worldId);
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
    /** Where the body stood, the start of the way even once its first piece is dropped. */
    private final Location start;
    private final long startedAt = System.currentTimeMillis();
    /** When the flight ended, or 0 while the camera still flies. */
    private long endedAt;
    private final List<Piece> pieces = new ArrayList<>();
    private double step = FIRST_STEP;
    private int points;
    /** How far the camera flew, every step counted. */
    private double distanceFlown;
    /** Where the camera was after the last step, to tell a step from being put somewhere. */
    private Location lastSeen;

    FlightTrail(UUID playerId, String playerName, Location start) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.start = start.clone();
        this.lastSeen = start.clone();
        pieces.add(new Piece(start));
        points = 1;
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
        endedAt = System.currentTimeMillis();
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

    UUID playerId() {
        return playerId;
    }

    String playerName() {
        return playerName;
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

    /** Where the body stood. */
    Location start() {
        return start.clone();
    }

    /** Where the camera was last. */
    Location last() {
        return lastSeen.clone();
    }

    /** The pieces of the way, the oldest first. Not to be changed. */
    List<Piece> pieces() {
        return Collections.unmodifiableList(pieces);
    }
}
