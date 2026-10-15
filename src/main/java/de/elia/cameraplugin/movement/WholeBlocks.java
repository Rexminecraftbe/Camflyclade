package de.elia.cameraplugin.movement;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Which blocks fill their space, or so nearly that nobody gets past what is
 * left of them: stone, glass, leaves, stairs - and soul sand, mud, farmland,
 * a cactus, which lack a sliver at the top or at the sides but look whole all
 * the same.
 *
 * <p>The wall of {@link CamBorderWall} asks this twice: a whole block keeps
 * its place in the wall, as it stops the camera on its own, and a block next
 * to the wall that is whole is one the camera cannot be in, so the wall need
 * not stand there for it, see {@link WallSections}.</p>
 *
 * <p>The answer is kept for every state of a block. It is the same wherever
 * the block stands, and working it out takes the shape of the block apart -
 * the wall asks about thousands of them.</p>
 */
final class WholeBlocks {

    /**
     * How much a block may lack at its sides and at its bottom and still
     * count as whole: a sixteenth, as a cactus does.
     */
    private static final double SIDE_SLIVER = 1.0 / 16.0;

    /** And how much at its top: two sixteenths, as soul sand and mud do. */
    private static final double TOP_SLIVER = 2.0 / 16.0;

    private final Map<BlockData, Boolean> known = new HashMap<>();

    /** Whether this block is whole, see the class. */
    boolean whole(Block block) {
        BlockData data = block.getBlockData();
        Boolean whole = known.get(data);
        if (whole == null) {
            whole = measure(block);
            known.put(data, whole);
        }
        return whole;
    }

    private static boolean measure(Block block) {
        Collection<BoundingBox> parts = block.getCollisionShape().getBoundingBoxes();
        if (parts.isEmpty()) {
            return false;
        }
        double minX = 1.0;
        double minY = 1.0;
        double minZ = 1.0;
        double maxX = 0.0;
        double maxY = 0.0;
        double maxZ = 0.0;
        for (BoundingBox part : parts) {
            minX = Math.min(minX, part.getMinX());
            minY = Math.min(minY, part.getMinY());
            minZ = Math.min(minZ, part.getMinZ());
            maxX = Math.max(maxX, part.getMaxX());
            maxY = Math.max(maxY, part.getMaxY());
            maxZ = Math.max(maxZ, part.getMaxZ());
        }
        return minX <= SIDE_SLIVER && maxX >= 1.0 - SIDE_SLIVER
                && minZ <= SIDE_SLIVER && maxZ >= 1.0 - SIDE_SLIVER
                && minY <= SIDE_SLIVER && maxY >= 1.0 - TOP_SLIVER;
    }
}
