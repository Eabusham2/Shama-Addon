package shama.addon.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * Amethyst geode scanning, taken from the AmethystESP source Eyad supplied.
 *
 * The matcher and the flood fill are that code as written — they are what make this find whole
 * geodes rather than counting loose blocks, so they are kept intact rather than reimplemented.
 * Everything in the original that depended on unverified client APIs (the toast popups, the
 * BlockUpdateEvent hook, boxLines) is left out and handled by each module's own alert and render
 * path instead, so nothing here can fail to build.
 *
 * Used by every module that looks at amethyst, so they all agree on what a geode is.
 */
public final class AmethystScan {
    public static final int MIN_Y = -58;
    public static final int MAX_Y = 30;

    /** Straight from the supplied source. */
    private static final int[][] NEIGHBORS = new int[][]{
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private AmethystScan() {}

    /** Straight from the supplied source. */
    public static boolean isAmethystLike(BlockState state) {
        return state.isOf(Blocks.AMETHYST_CLUSTER)
            || state.isOf(Blocks.LARGE_AMETHYST_BUD)
            || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
            || state.isOf(Blocks.SMALL_AMETHYST_BUD)
            || state.isOf(Blocks.AMETHYST_BLOCK)
            || state.isOf(Blocks.BUDDING_AMETHYST);
    }

    /**
     * Every connected geode in this chunk that reaches the threshold.
     *
     * The gather pass and the flood fill are the supplied code, with the world lookup swapped for a
     * chunk-local one so it stays safe to run off the main thread, and the results returned instead
     * of written into fields.
     */
    public static List<Set<BlockPos>> findGeodes(World world, WorldChunk chunk, int threshold, int minY, int maxY) {
        ChunkPos chunkPos = chunk.getPos();
        int minX = chunkPos.getStartX();
        int minZ = chunkPos.getStartZ();
        Set<BlockPos> cluster = new HashSet<>();

        int lo = Math.max(minY, chunk.getBottomY());
        int hi = Math.min(maxY, chunk.getTopYInclusive());

        for (int y = lo; y <= hi; ++y) {
            for (int x = minX; x < minX + 16; ++x) {
                for (int z = minZ; z < minZ + 16; ++z) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isAmethystLike(chunk.getBlockState(pos))) {
                        cluster.add(pos);
                    }
                }
            }
        }

        List<Set<BlockPos>> geodes = new ArrayList<>();
        if (cluster.isEmpty()) return geodes;

        Set<BlockPos> visited = new HashSet<>();
        Iterator<BlockPos> var19 = cluster.iterator();

        while (var19.hasNext()) {
            BlockPos seed = var19.next();
            if (visited.contains(seed)) continue;

            Set<BlockPos> geode = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                geode.add(current);
                int[][] var13 = NEIGHBORS;
                int var14 = var13.length;

                for (int var15 = 0; var15 < var14; ++var15) {
                    int[] n = var13[var15];
                    BlockPos next = current.add(n[0], n[1], n[2]);
                    if (cluster.contains(next) && !visited.contains(next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }

            if (geode.size() >= threshold) {
                geodes.add(geode);
            }
        }
        return geodes;
    }

    /** Chunk key in the supplied source's encoding. */
    public static long encodeChunk(int x, int z) {
        return (long) x << 32 | (long) z & 4294967295L;
    }
}
