package shama.addon.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Their ChunkStateCache helper — fingerprints scanned chunks so finders skip re-scanning unchanged ones. */
public final class ChunkStateCache {
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> fingerprints = new ConcurrentHashMap<>();

    public void markDirty(BlockPos pos) { dirty.add(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4)); }
    public void markDirty(ChunkPos pos) { dirty.add(pos.toLong()); }

    public boolean needsUpdate(ChunkPos pos, long fingerprint) {
        Long fp = fingerprints.get(pos.toLong());
        return dirty.contains(pos.toLong()) || fp == null || fp != fingerprint;
    }

    public void remember(ChunkPos pos, long fingerprint) { fingerprints.put(pos.toLong(), fingerprint); dirty.remove(pos.toLong()); }
    public void forget(ChunkPos pos) { fingerprints.remove(pos.toLong()); dirty.remove(pos.toLong()); }
    public void clear() { dirty.clear(); fingerprints.clear(); }
}
