package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anomaly Scan — learns what ordinary ground looks like here, then finds the ground that isn't.
 *
 * Every other finder in this addon is told what to look for: a list of blocks, a shape, a signal.
 * That works until somebody builds out of the same stone they dug through, and then nothing on the
 * list ever fires.
 *
 * This is the other way round. It measures a handful of plain properties of each chunk — how much
 * of it is air, how flat the surface is, how far the light sources are apart, how much of the stone
 * has been replaced with something else — and builds a running average and spread of those numbers
 * from the chunks you fly over. Once it has enough to know what normal looks like, it scores each
 * new chunk by how many standard deviations it sits from that average.
 *
 * Because the baseline comes from the world you are actually in, it calibrates itself to the seed,
 * the biome and the server, and it needs no list of blocks at all. Anything built stands out from
 * the ground around it, whatever it was built from — that is the whole idea.
 */
public class AnomalyScan extends Module {
    /** The properties measured per chunk. Keep in step with sample(). */
    private static final int AIR = 0, SURFACE = 1, LIGHTS = 2, REPLACED = 3, ORDER = 4, COUNT = 5;
    private static final String[] LABELS = {
        "hollow space", "flattened ground", "spaced-out lighting", "swapped-out stone", "straight edges"
    };

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> threshold = sg.add(new DoubleSetting.Builder()
        .name("deviation")
        .description("How far from normal a chunk has to sit before it counts, measured in standard deviations. 2 flags roughly one chunk in twenty and finds plenty of nothing; 3 is the usual sweet spot; 4 only fires on the obvious.")
        .defaultValue(3.0).min(1.5).max(8).sliderRange(2, 6).decimalPlaces(1).build());

    private final Setting<Integer> learnFirst = sg.add(new IntSetting.Builder()
        .name("learn-first")
        .description("How many chunks to measure before it starts judging anything. It cannot know what is unusual until it has seen enough ordinary ground, and too few makes it flag everything.")
        .defaultValue(120).min(20).max(2000).sliderRange(50, 600).build());

    private final Setting<Integer> minSignals = sg.add(new IntSetting.Builder()
        .name("min-signals")
        .description("How many separate properties must be off at once. One odd number happens naturally all the time; two or three together rarely does.")
        .defaultValue(2).min(1).max(5).sliderRange(1, 4).build());

    private final Setting<Integer> minY = sg.add(new IntSetting.Builder()
        .name("min-y").description("Lowest height to measure.")
        .defaultValue(-60).min(-64).max(320).sliderRange(-64, 60).build());
    private final Setting<Integer> maxY = sg.add(new IntSetting.Builder()
        .name("max-y").description("Highest height to measure. Keeping this under the surface avoids trees and hills skewing the baseline.")
        .defaultValue(60).min(-64).max(320).sliderRange(0, 128).build());

    private final Setting<Integer> scanRate = sg.add(new IntSetting.Builder()
        .name("scan-rate")
        .description("How many newly loaded chunks to measure each tick. Lower this if the game stutters while flying.")
        .defaultValue(3).min(1).max(32).sliderRange(1, 12).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat")
        .description("Report each anomaly in chat, along with which properties were off.")
        .defaultValue(true).build());

    private final Setting<Boolean> relearn = sg.add(new BoolSetting.Builder()
        .name("relearn-on-dimension")
        .description("Throw the baseline away when you change dimension. The Nether looks nothing like the Overworld, so a baseline learned in one is meaningless in the other.")
        .defaultValue(true).build());

    // ---------------------------------------------------------------- render
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Integer> chunkRange = sgRender.add(new IntSetting.Builder()
        .name("range").description("How far out to keep showing flagged chunks, in chunks.")
        .defaultValue(16).min(1).max(64).sliderRange(4, 32).build());
    private final Setting<Double> boxY = sgRender.add(new DoubleSetting.Builder()
        .name("box-y").description("Height to draw the boxes at. Sea level by default.")
        .defaultValue(63).min(-64).max(320).sliderRange(-64, 200).build());
    private final Setting<SettingColor> weakColor = sgRender.add(new ColorSetting.Builder()
        .name("weak-color").description("Colour for a chunk that only just crosses the line.")
        .defaultValue(new SettingColor(255, 220, 60, 70)).build());
    private final Setting<SettingColor> strongColor = sgRender.add(new ColorSetting.Builder()
        .name("strong-color").description("Colour for a chunk far outside normal — the ones worth flying to.")
        .defaultValue(new SettingColor(255, 60, 60, 110)).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.")
        .defaultValue(ShapeMode.Both).build());

    /**
     * Running mean and spread for one property, kept with Welford's method so it stays accurate
     * over thousands of chunks without holding on to any of them.
     */
    private static final class Running {
        long n;
        double mean, m2;

        void add(double v) {
            n++;
            double d = v - mean;
            mean += d / n;
            m2 += d * (v - mean);
        }

        double sd() { return n < 2 ? 0 : Math.sqrt(m2 / (n - 1)); }

        /** How many standard deviations this value sits from the average. */
        double z(double v) {
            double s = sd();
            return s < 1.0e-9 ? 0 : Math.abs(v - mean) / s;
        }
    }

    private final Running[] stats = new Running[COUNT];
    private final Map<Long, double[]> flagged = new ConcurrentHashMap<>();   // chunk -> {score, signals}
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<WorldChunk> pending = new ArrayDeque<>();
    private long learned;
    private String dimension = "";

    public AnomalyScan() {
        super(shama.addon.ShamaAddon.HUNT, "anomaly-scan++",
            "Learns what ordinary ground looks like on this server, then flags the chunks that do not match — finds builds made from plain terrain that block lists never catch.");
    }

    @Override
    public void onActivate() {
        for (int i = 0; i < COUNT; i++) stats[i] = new Running();
        flagged.clear(); announced.clear(); learned = 0;
        synchronized (pending) { pending.clear(); }
        dimension = currentDimension();
    }

    @Override
    public void onDeactivate() {
        flagged.clear(); announced.clear();
        synchronized (pending) { pending.clear(); }
    }

    private String currentDimension() {
        try { return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString(); }
        catch (Throwable t) { return ""; }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        synchronized (pending) {
            if (pending.size() >= 256) pending.pollFirst();
            pending.addLast(chunk);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (relearn.get()) {
            String now = currentDimension();
            if (!now.equals(dimension)) {
                dimension = now;
                for (int i = 0; i < COUNT; i++) stats[i] = new Running();
                learned = 0;
                flagged.clear();
                if (chat.get()) shama.addon.util.Chat.info("[AnomalyScan] new dimension — learning what normal looks like here");
            }
        }

        int budget = scanRate.get();
        while (budget-- > 0) {
            WorldChunk c;
            synchronized (pending) { c = pending.pollFirst(); }
            if (c == null) break;
            try { measure(c); } catch (Throwable ignored) {}
        }

        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int keep = chunkRange.get() + 12;
        flagged.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep
                                    || Math.abs(new ChunkPos(k).z - pcz) > keep);
    }

    /** Reduce a chunk to five plain numbers. Nothing here names a block on purpose. */
    private double[] sample(WorldChunk chunk) {
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int lo = Math.max(minY.get(), chunk.getBottomY());
        int hi = Math.min(maxY.get(), chunk.getTopYInclusive());
        if (hi <= lo) return null;

        BlockPos.Mutable m = new BlockPos.Mutable();
        long air = 0, solid = 0, lights = 0, nonStone = 0, axisRuns = 0;
        int[] surface = new int[256];
        java.util.List<BlockPos> lightPositions = new java.util.ArrayList<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = lo;
                int run = 0;
                for (int y = lo; y <= hi; y++) {
                    BlockState st = chunk.getBlockState(m.set(bx + x, y, bz + z));
                    if (st.isAir()) {
                        air++;
                        run = 0;
                        continue;
                    }
                    solid++;
                    top = y;
                    String p = shama.addon.util.BlockPaths.of(st.getBlock());
                    // "stone" here means whatever the ground is naturally made of, not a fixed list
                    if (!p.contains("stone") && !p.contains("deepslate") && !p.contains("dirt")
                        && !p.contains("gravel") && !p.contains("sand") && !p.contains("tuff")
                        && !p.contains("granite") && !p.contains("diorite") && !p.contains("andesite")
                        && !p.contains("netherrack") && !p.contains("water") && !p.contains("lava")
                        && !p.contains("ore") && !p.contains("water") && !p.contains("basalt")) {
                        nonStone++;
                        // a straight run of the same placed block is a wall, floor or ceiling
                        run++;
                        if (run >= 4) axisRuns++;
                    } else run = 0;

                    try {
                        if (st.getLuminance() > 0) { lights++; lightPositions.add(new BlockPos(bx + x, y, bz + z)); }
                    } catch (Throwable ignored) {}
                }
                surface[x * 16 + z] = top;
            }
        }

        long total = air + solid;
        if (total == 0) return null;

        // how uneven the top surface is: natural ground rolls, floors do not
        double mean = 0;
        for (int v : surface) mean += v;
        mean /= surface.length;
        double var = 0;
        for (int v : surface) var += (v - mean) * (v - mean);
        double roughness = Math.sqrt(var / surface.length);

        // how evenly spaced the light sources are: torches go in at regular intervals, lava does not
        double spacing = 0;
        if (lightPositions.size() >= 3) {
            double[] nearest = new double[lightPositions.size()];
            for (int i = 0; i < lightPositions.size(); i++) {
                double best = Double.MAX_VALUE;
                for (int j = 0; j < lightPositions.size(); j++) {
                    if (i == j) continue;
                    best = Math.min(best, Math.sqrt(lightPositions.get(i).getSquaredDistance(lightPositions.get(j))));
                }
                nearest[i] = best;
            }
            double nm = 0;
            for (double d : nearest) nm += d;
            nm /= nearest.length;
            double nv = 0;
            for (double d : nearest) nv += (d - nm) * (d - nm);
            // low spread between gaps means regular spacing, which is a person's doing
            spacing = nm <= 0 ? 0 : 1.0 / (1.0 + Math.sqrt(nv / nearest.length));
        }

        double[] out = new double[COUNT];
        out[AIR] = (double) air / total;
        out[SURFACE] = roughness;
        out[LIGHTS] = spacing;
        out[REPLACED] = (double) nonStone / Math.max(1, solid);
        out[ORDER] = (double) axisRuns / Math.max(1, solid);
        return out;
    }

    private void measure(WorldChunk chunk) {
        double[] v = sample(chunk);
        if (v == null) return;

        long key = chunk.getPos().toLong();

        // still learning: take the reading in and judge nothing
        if (learned < learnFirst.get()) {
            for (int i = 0; i < COUNT; i++) stats[i].add(v[i]);
            learned++;
            if (learned == learnFirst.get() && chat.get())
                shama.addon.util.Chat.info("[AnomalyScan] baseline ready after %d chunks — now flagging the odd ones", learned);
            return;
        }

        int off = 0;
        double worst = 0;
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            double z = stats[i].z(v[i]);
            if (z >= threshold.get()) {
                off++;
                worst = Math.max(worst, z);
                if (why.length() > 0) why.append(", ");
                why.append(LABELS[i]);
            }
        }

        if (off >= minSignals.get()) {
            flagged.put(key, new double[]{worst, off});
            if (chat.get() && announced.add(key)) {
                ChunkPos cp = chunk.getPos();
                shama.addon.util.Chat.info("[AnomalyScan] %d, %d stands out (%.1f sd) — %s",
                    cp.getStartX() + 8, cp.getStartZ() + 8, worst, why);
            }
        } else {
            flagged.remove(key);
            // ordinary ground feeds back into the baseline, so it keeps up as the terrain changes
            for (int i = 0; i < COUNT; i++) stats[i].add(v[i]);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (flagged.isEmpty() || mc.player == null) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get();
        double y = boxY.get();

        for (Map.Entry<Long, double[]> e : flagged.entrySet()) {
            ChunkPos cp = new ChunkPos(e.getKey());
            if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;

            // the further outside normal, the stronger the colour
            boolean strong = e.getValue()[0] >= threshold.get() * 1.6 || e.getValue()[1] >= 3;
            SettingColor c = strong ? strongColor.get() : weakColor.get();
            Color fill = new Color(c.r, c.g, c.b, c.a);
            Color line = new Color(c.r, c.g, c.b, Math.min(255, c.a + 130));
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, fill, line, shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        if (learned < learnFirst.get()) return "learning " + learned + "/" + learnFirst.get();
        return flagged.isEmpty() ? null : Integer.toString(flagged.size());
    }
}
