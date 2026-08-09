package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** HopperDebug — their module: queue-scans chunks for hoppers below max-y (optionally under deepslate), flags dense chunks. */
public class HopperDebug extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder().name("scan-radius").description("Chunk scan radius.").defaultValue(6).min(1).max(16).build());
    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder().name("max-y").description("Maximum Y level to scan.").defaultValue(0).min(-64).max(64).build());
    private final Setting<Integer> minHoppers = sgGeneral.add(new IntSetting.Builder().name("min-hoppers").description("Minimum hoppers to flag a chunk.").defaultValue(1).min(1).max(32).build());
    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder().name("chunks-per-tick").description("Chunks scanned per tick.").defaultValue(3).min(1).max(12).build());
    private final Setting<Boolean> requireDeepslate = sgGeneral.add(new BoolSetting.Builder().name("under-deepslate").description("Only count hoppers with deepslate above.").defaultValue(false).build());
    private final Setting<Integer> minDeepslate = sgGeneral.add(new IntSetting.Builder().name("min-deepslate").description("Minimum deepslate blocks above.").defaultValue(2).min(1).max(10).visible(requireDeepslate::get).build());
    private final Setting<Double> chunkMarkY = sgRender.add(new DoubleSetting.Builder().name("chunk-y-level").description("Y level for chunk mark.").defaultValue(55.0).min(-64).max(320).build());
    private final Setting<Boolean> signalDetect = sgGeneral.add(new BoolSetting.Builder().name("signal-detect").description("Also watch hopper comparator signals and flag ones that CHANGE (active item flow = a running farm).").defaultValue(false).build());
    private final Setting<Integer> signalDelta = sgGeneral.add(new IntSetting.Builder().name("signal-delta").description("How much a hopper's signal must change to flag it.").defaultValue(1).min(1).max(15).visible(signalDetect::get).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").description("Highlight colour.").defaultValue(new SettingColor(255, 140, 0, 120)).build());

    private final Map<Long, Set<BlockPos>> hoppersByChunk = new ConcurrentHashMap<>();
    private final Set<Long> notifiedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<Long> scanQueue = new ArrayDeque<>();
    private int tickCounter;
    private final Map<Long, Integer> lastSignal = new ConcurrentHashMap<>();
    private final Map<Long, Long> signalFlagged = new ConcurrentHashMap<>();

    public HopperDebug() { super(shama.addon.ShamaAddon.HUNT, "hopper-debug++", "Finds hoppers and reads the signal strength around them, which gives away sorting systems and hidden storage."); }

    @Override public void onActivate() { hoppersByChunk.clear(); notifiedChunks.clear(); scanQueue.clear(); lastSignal.clear(); signalFlagged.clear(); }
    @Override public void onDeactivate() { hoppersByChunk.clear(); notifiedChunks.clear(); scanQueue.clear(); lastSignal.clear(); signalFlagged.clear(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (++tickCounter % 20 == 0) { // periodically refill the scan queue around the player
            ChunkPos center = mc.player.getChunkPos();
            int r = scanRadius.get();
            for (int cx = center.x - r; cx <= center.x + r; cx++) for (int cz = center.z - r; cz <= center.z + r; cz++) {
                long k = ChunkPos.toLong(cx, cz);
                if (!scanQueue.contains(k)) scanQueue.add(k);
            }
        }
        for (int i = 0; i < chunksPerTick.get() && !scanQueue.isEmpty(); i++) {
            long k = scanQueue.poll();
            var ch = mc.world.getChunk(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k), net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (ch instanceof WorldChunk wc) scanChunk(wc, k);
        }
        if (signalDetect.get()) signalPass();
    }

    private void signalPass() {
        ChunkPos center = mc.player.getChunkPos(); int r = scanRadius.get();
        for (int cx = center.x - r; cx <= center.x + r; cx++) for (int cz = center.z - r; cz <= center.z + r; cz++) {
            var ch = mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (!(ch instanceof WorldChunk wc)) continue;
            for (var en : wc.getBlockEntities().entrySet()) {
                if (!(en.getValue() instanceof net.minecraft.block.entity.HopperBlockEntity hop)) continue;
                if (en.getKey().getY() > maxY.get()) continue;
                long key = en.getKey().asLong();
                int sig = net.minecraft.screen.ScreenHandler.calculateComparatorOutput((net.minecraft.inventory.Inventory) hop);
                Integer prev = lastSignal.put(key, sig);
                if (prev != null && Math.abs(sig - prev) >= signalDelta.get()) {
                    signalFlagged.put(key, System.currentTimeMillis());
                    shama.addon.util.Chat.info("[HopperDebug] hopper signal change at %d, %d, %d (%d)", en.getKey().getX(), en.getKey().getY(), en.getKey().getZ(), sig);
                }
            }
        }
        long now = System.currentTimeMillis();
        signalFlagged.entrySet().removeIf(e -> now - e.getValue() > 5000L);
    }

    private void scanChunk(WorldChunk chunk, long key) {
        Set<BlockPos> found = new HashSet<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = chunk.getBottomY(); y <= maxY.get(); y++) {
            if (!chunk.getBlockState(m.set(bx + x, y, bz + z)).isOf(Blocks.HOPPER)) continue;
            if (requireDeepslate.get()) {
                int ds = 0;
                for (int dy = 1; dy <= minDeepslate.get() + 2 && ds < minDeepslate.get(); dy++)
                    if (chunk.getBlockState(m.set(bx + x, y + dy, bz + z)).isOf(Blocks.DEEPSLATE)) ds++;
                if (ds < minDeepslate.get()) continue;
            }
            found.add(new BlockPos(bx + x, y, bz + z));
        }
        if (found.size() >= minHoppers.get()) {
            hoppersByChunk.put(key, found);
            if (notifiedChunks.add(key)) shama.addon.util.Chat.info("[HopperDebug] %d hoppers in chunk (%d, %d)", found.size(), chunk.getPos().x, chunk.getPos().z);
        } else hoppersByChunk.remove(key);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!signalFlagged.isEmpty()) {
            var sc = color.get();
            for (long k : signalFlagged.keySet()) {
                var bp = net.minecraft.util.math.BlockPos.fromLong(k);
                event.renderer.box(bp.getX(), bp.getY(), bp.getZ(), bp.getX()+1, bp.getY()+1, bp.getZ()+1, new meteordevelopment.meteorclient.utils.render.color.Color(sc.r, sc.g, sc.b, 60), sc, ShapeMode.Both, 0);
            }
        }
        if (hoppersByChunk.isEmpty()) return;
        var c = color.get();
        var side = new meteordevelopment.meteorclient.utils.render.color.Color(c.r, c.g, c.b, 40);
        for (long key : hoppersByChunk.keySet()) {
            ChunkPos cp = new ChunkPos(key);
            double x0 = cp.getStartX(), z0 = cp.getStartZ(), y = chunkMarkY.get();
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, side, c, ShapeMode.Both, 0);
        }
    }
}
