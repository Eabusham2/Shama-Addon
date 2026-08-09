package shama.addon.util;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry path lookups cached per block TYPE.
 *
 * Registries.BLOCK.getId(block).getPath() allocates an Identifier and a String every call. The
 * chunk scanners call it once per block — around 98,000 times per chunk — so on a busy world that
 * was a large slice of the stutter when chunks streamed in while moving. There are only a couple
 * of thousand block types, so caching the result makes it a single map lookup after the first hit.
 *
 * Thread-safe: the scanners run on background threads.
 */
public final class BlockPaths {
    private static final Map<Block, String> CACHE = new ConcurrentHashMap<>();

    private BlockPaths() {}

    public static String of(Block block) {
        String p = CACHE.get(block);
        if (p == null) {
            p = Registries.BLOCK.getId(block).getPath();
            CACHE.put(block, p);
        }
        return p;
    }
}
