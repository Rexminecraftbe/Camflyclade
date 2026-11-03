package de.elia.cameraplugin.ore;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
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
     * the chunk next door. An ore open to more sky light than
     * {@code maxSky} on any side within the chunk is left out at once: light
     * of the sky reaches there, so it can be seen from outside, on foot.
     *
     * <p>With {@code biomes}, {@code hide-ores.where: biomes}, only an ore in
     * one of them counts, and the sky does not matter: every ore of such a
     * biome open to a block that can be seen through is one to hide. The copy
     * has to carry its biomes then.</p>
     *
     * @param chunkX    the chunk, in chunks
     * @param minY      the lowest block of the world
     * @param maxY      one above its highest block
     * @param maxSky    the most sky light at the open side of an ore that
     *                  still counts as a cave; {@code -1} when the sky does not
     *                  matter - in a world without one, and among the biomes
     * @param highestOre the height of the highest ore that counts, {@code hide-ores.max-y}
     * @param lookFor   the ores, each with what it looks like for a camera player
     * @param through   every block that can be seen through
     * @param biomes    the biomes whose ores alone count, or {@code null} for every cave
     */
    static List<CamOreHider.Found> scan(ChunkSnapshot copy, int chunkX, int chunkZ, int minY, int maxY, int maxSky,
                                        int highestOre, Map<Material, BlockData> lookFor, Set<Material> through,
                                        Set<NamespacedKey> biomes) {
        List<CamOreHider.Found> found = new ArrayList<>();
        int sections = (maxY - minY) >> 4;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int section = 0; section < sections; section++) {
            if (copy.isSectionEmpty(section)) {
                continue;
            }
            int bottom = minY + (section << 4);
            if (bottom > highestOre) {
                break;
            }
            for (int y = bottom; y < Math.min(bottom + 16, highestOre + 1); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockData cover = lookFor.get(copy.getBlockType(x, y, z));
                        if (cover == null || biomes != null && !inBiome(copy, x, y, z, biomes)) {
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
                            if (maxSky >= 0 && copy.getBlockSkyLight(nx, ny, nz) > maxSky) {
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

    /** Whether the block lies in one of these biomes. Its key, not the biome: see CamAreaRules. */
    private static boolean inBiome(ChunkSnapshot copy, int x, int y, int z, Set<NamespacedKey> biomes) {
        Biome biome = copy.getBiome(x, y, z);
        NamespacedKey key = biome == null ? null : biome.getKey();
        return key != null && biomes.contains(key);
    }
}
