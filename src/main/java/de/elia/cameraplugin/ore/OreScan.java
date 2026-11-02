package de.elia.cameraplugin.ore;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The look at the copy of a chunk for the ores of its caves, away from the
 * main thread: it touches nothing but the copy and what it is handed.
 */
final class OreScan {

    /** The six sides of a block. */
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private OreScan() {
    }

    /**
     * Every ore of the copy that lies open to a cave, or might, by a side in
     * the chunk next door. An ore open under the sky on any side within the
     * chunk is left out at once.
     *
     * @param chunkX  the chunk, in chunks
     * @param minY    the lowest block of the world
     * @param maxY    one above its highest block
     * @param sky     whether the world has a sky at all: in one without, no ore lies under it
     * @param lookFor the ores, each with what it looks like for a camera player
     * @param through every block that can be seen through
     */
    static List<CamOreHider.Found> scan(ChunkSnapshot copy, int chunkX, int chunkZ, int minY, int maxY, boolean sky,
                                        Map<Material, BlockData> lookFor, Set<Material> through) {
        List<CamOreHider.Found> found = new ArrayList<>();
        int sections = (maxY - minY) >> 4;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int section = 0; section < sections; section++) {
            if (copy.isSectionEmpty(section)) {
                continue;
            }
            int bottom = minY + (section << 4);
            for (int y = bottom; y < bottom + 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockData cover = lookFor.get(copy.getBlockType(x, y, z));
                        if (cover == null) {
                            continue;
                        }
                        boolean cave = false;
                        boolean open = false;
                        List<int[]> outside = null;
                        for (int[] side : SIDES) {
                            int nx = x + side[0];
                            int ny = y + side[1];
                            int nz = z + side[2];
                            if (ny < minY || ny >= maxY) {
                                continue;
                            }
                            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                                if (outside == null) {
                                    outside = new ArrayList<>(2);
                                }
                                outside.add(new int[]{baseX + nx, ny, baseZ + nz});
                                continue;
                            }
                            if (!through.contains(copy.getBlockType(nx, ny, nz))) {
                                continue;
                            }
                            if (sky && ny > copy.getHighestBlockYAt(nx, nz)) {
                                open = true;
                                break;
                            }
                            cave = true;
                        }
                        if (!open && (cave || outside != null)) {
                            found.add(new CamOreHider.Found(baseX + x, y, baseZ + z, cover, cave,
                                    outside == null ? List.of() : outside));
                        }
                    }
                }
            }
        }
        return found;
    }
}
