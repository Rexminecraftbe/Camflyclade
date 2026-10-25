package de.elia.cameraplugin.signal;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The way the radio signal of {@code max-distance-mode: signal} takes: a
 * straight line from the body to the camera, and the blocks it has to get
 * through on it.
 */
final class SignalPath {

    private SignalPath() {
    }

    /**
     * How strong the signal arrives at the camera, from 0 (gone) to 1 (full).
     *
     * <p>Each block of distance takes one block off the range, each block in
     * between what it costs, see {@link SignalCosts}. What is left of the
     * range, out of all of it, is the strength: right at the body it is full,
     * at the end of the range in the open it is gone, and so it is behind a
     * wall that eats up what was left.</p>
     *
     * @param from  the eyes of the body, in the same world as {@code to}
     * @param to    the eyes of the camera
     * @param range {@code camera-mode.max-distance}
     * @param costs what each block costs, {@code signal.block-cost} and {@code -costs}
     */
    static double strength(Location from, Location to, double range, SignalCosts costs) {
        if (range <= 0.0) {
            return 0.0;
        }
        double left = range - from.distance(to);
        if (left <= 0.0) {
            return 0.0;
        }
        if (!costs.free()) {
            left -= costBetween(from, to, costs, left);
        }
        return Math.max(0.0, left / range);
    }

    /**
     * What the blocks on the straight line between the two spots cost the
     * signal together, in blocks of range: out of the box the full blocks
     * that cannot be seen through, such as stone, earth or planks, while
     * glass, leaves, water, slabs and fences let it through - and so does the
     * wall of {@code border-mode: barrier}, made of barriers -, and whatever
     * {@code signal.block-costs} says otherwise.
     *
     * <p>The two blocks the line starts and ends in do not count; they hold
     * the eyes of the body and of the camera. Neither does a block in a chunk
     * that is not loaded: looking at it would load it, and nobody is near it
     * anyway.</p>
     *
     * <p>Every block the line passes through is visited once, along the line
     * from one block to the next on whichever side it leaves the block on
     * first - a block it only grazes at a corner included.</p>
     *
     * @param limit how much to add up at the most: once the blocks have used
     *              up what is left of the range, more would not make the
     *              signal weaker than gone
     */
    static double costBetween(Location from, Location to, SignalCosts costs, double limit) {
        World world = from.getWorld();
        double fromX = from.getX();
        double fromY = from.getY();
        double fromZ = from.getZ();
        double dx = to.getX() - fromX;
        double dy = to.getY() - fromY;
        double dz = to.getZ() - fromZ;
        int x = from.getBlockX();
        int y = from.getBlockY();
        int z = from.getBlockZ();
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        // How far along the line - 0 at from, 1 at to - it takes to cross
        // one whole block on each axis, and how far it is to the next side.
        double acrossX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double acrossY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double acrossZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double nextX = nextSide(fromX, x, stepX, acrossX);
        double nextY = nextSide(fromY, y, stepY, acrossY);
        double nextZ = nextSide(fromZ, z, stepZ, acrossZ);
        int steps = Math.abs(to.getBlockX() - x) + Math.abs(to.getBlockY() - y) + Math.abs(to.getBlockZ() - z);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        boolean loaded = false;
        double cost = 0.0;
        // One step less than it takes to get there: the last one lands in the
        // block of the camera.
        for (int i = 1; i < steps && cost < limit; i++) {
            if (nextX < nextY && nextX < nextZ) {
                x += stepX;
                nextX += acrossX;
            } else if (nextY < nextZ) {
                y += stepY;
                nextY += acrossY;
            } else {
                z += stepZ;
                nextZ += acrossZ;
            }
            if (y < minY || y >= maxY) {
                continue;
            }
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                chunkX = x >> 4;
                chunkZ = z >> 4;
                loaded = world.isChunkLoaded(chunkX, chunkZ);
            }
            if (loaded) {
                cost += costs.of(world.getType(x, y, z));
            }
        }
        return cost;
    }

    /** How far along the line the next side of the block lies on one axis, see {@link #costBetween}. */
    private static double nextSide(double start, int block, int step, double across) {
        if (step > 0) {
            return (block + 1 - start) * across;
        }
        if (step < 0) {
            return (start - block) * across;
        }
        return Double.POSITIVE_INFINITY;
    }
}
