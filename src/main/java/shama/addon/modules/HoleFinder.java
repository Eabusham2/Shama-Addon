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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hole Finder — finds shafts somebody dug and then plugged behind them.
 *
 * A hole counts when an out-of-place block (cobble, obsidian, dirt) caps a narrow column of air.
 * Only the capping block itself is drawn, never the chunk it sits in, so you get the exact spot to
 * break rather than a box around sixteen blocks of ground.
 */
public class HoleFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> chunkDelay = sg.add(new DoubleSetting.Builder()
        .name("chunk-delay")
        .description("Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.")
        .defaultValue(0.0).min(0).max(10).sliderRange(0, 3).decimalPlaces(1).build());


    private final Setting<Integer> sensitivity = sg.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("How many covered holes a chunk needs before they're shown, out of 20. This also sets how deep a shaft has to be: five blocks at 1, twenty at 20. Low catches shallow plugs and single holes, high only reports serious dug-out shafts.")
        .defaultValue(5).min(1).max(20).sliderRange(1, 20).build());

    private final Setting<Integer> chunkRange = sg.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("How far out to keep showing holes, in chunks.")
        .defaultValue(16).min(1).max(64).sliderRange(4, 32).build());

    private final Setting<Integer> scanRate = sg.add(new IntSetting.Builder()
        .name("scan-rate")
        .description("How many newly loaded chunks to check each tick. Lower this if the game stutters while flying.")
        .defaultValue(4).min(1).max(64).sliderRange(1, 16).build());

    private final Setting<Integer> maxWidth = sg.add(new IntSetting.Builder()
        .name("max-width")
        .description("Widest tunnel to treat as dug rather than natural. 3 covers everything people actually make: 1x1 ladder shafts, 2x1 and 3x1 corridors, and 3x3 rooms. Raise it for wider excavations, lower it to only catch tight shafts.")
        .defaultValue(3).min(1).max(8).sliderRange(1, 5).build());
    private final Setting<Integer> surfaceCapY = sg.add(new IntSetting.Builder()
        .name("surface-cap-y")
        .description("Above this height, a cap with open sky over it still counts — that is what a hole plugged flush with the ground looks like. Below it, a block with air above is just something sitting inside a shaft that is already open, so it is ignored.")
        .defaultValue(50).min(-64).max(320).sliderRange(0, 120).build());

    private final Setting<Boolean> obscureFills = sg.add(new BoolSetting.Builder()
        .name("obscure-fills")
        .description("Also count caps made of blocks that never form underground — planks, wool, concrete, terracotta, glass, bricks, copper, quartz and the rest. None of it generates down there, so a single one sealing a shaft is somebody's doing whatever the depth or shape. People plug a hole with whatever is in their hotbar, and it is usually not cobblestone.")
        .defaultValue(true).build());

    private final Setting<Boolean> stairs = sg.add(new BoolSetting.Builder()
        .name("stairs")
        .description("Also catch staircases. They step sideways as they go down, so their walls sit further out than a straight shaft's and would otherwise be missed.")
        .defaultValue(true).build());
    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat")
        .description("Print a message the first time each chunk's holes are found.")
        .defaultValue(false).build());

    private final SettingGroup sgPockets = settings.createGroup("Sealed Pockets");
    private final Setting<Boolean> sealedPockets = sgPockets.add(new BoolSetting.Builder()
        .name("sealed-pockets")
        .description("Find air that has been walled in completely — blocks on every side including above and below. Nothing in the world generates like that, so a sealed pocket is somebody hiding something: a buried chest, a covered entrance, or a stash sealed behind stone.")
        .defaultValue(false).build());
    private final Setting<Integer> maxPocketHeight = sgPockets.add(new IntSetting.Builder()
        .name("max-height")
        .description("Tallest pocket to count, in blocks. One or two is the giveaway — anything taller is usually just a cave.")
        .defaultValue(2).min(1).max(4).sliderRange(1, 3).visible(sealedPockets::get).build());
    private final Setting<Integer> pocketMinY = sgPockets.add(new IntSetting.Builder()
        .name("pocket-min-y")
        .description("Ignore pockets above this height. Sealed air near the surface is usually part of a build rather than something hidden.")
        .defaultValue(-64).min(-64).max(320).sliderRange(-64, 64).visible(sealedPockets::get).build());
    private final Setting<SettingColor> pocketColor = sgPockets.add(new ColorSetting.Builder()
        .name("pocket-color").description("Colour used for sealed pockets.")
        .defaultValue(new SettingColor(255, 220, 0, 220)).visible(sealedPockets::get).build());

    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("Colour of the filled faces on each capping block.")
        .defaultValue(new SettingColor(40, 90, 255, 90)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Colour of the outline on each capping block.")
        .defaultValue(new SettingColor(60, 120, 255, 230)).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.")
        .defaultValue(ShapeMode.Both).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each hole.").defaultValue(false).build());

    /** chunk key -> the capping blocks found in it */
    private final Map<Long, List<int[]>> holes = new ConcurrentHashMap<>();
    private final Map<Long, List<int[]>> pocketsByChunk = new ConcurrentHashMap<>();
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    private record Queued(WorldChunk chunk, long arrived) {}
    private final ArrayDeque<Queued> pending = new ArrayDeque<>();
    private static final int MAX_PENDING = 512;
    private java.util.concurrent.ExecutorService scanner;

    public HoleFinder() {
        super(shama.addon.ShamaAddon.HUNT, "hole-finder++",
            "Finds shafts someone dug and then plugged behind them, and marks the exact block that caps each one.");
    }

    @Override
    public void onActivate() {
        holes.clear(); pocketsByChunk.clear(); announced.clear();
        synchronized (pending) { pending.clear(); }
        scanner = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "shama-holefinder");
            t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        synchronized (pending) { pending.clear(); }
        holes.clear(); pocketsByChunk.clear(); announced.clear();
    }

    /** How deep the air column must be: 5 at sensitivity 1, 20 at sensitivity 20. */
    private int holeDepth() {
        double t = (Math.max(1, Math.min(20, sensitivity.get())) - 1) / 19.0;
        return (int) Math.round(5 + t * 15);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) pending.pollFirst();
            pending.addLast(new Queued(chunk, System.currentTimeMillis()));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        java.util.concurrent.ExecutorService s = scanner;
        if (s == null || s.isShutdown() || mc.player == null) return;

        int budget = scanRate.get();
        while (budget-- > 0) {
            WorldChunk c;
            synchronized (pending) {
                Queued head = pending.peekFirst();
                if (head == null) break;
                // the queue is oldest-first, so if the head is not ready none behind it are either
                if (System.currentTimeMillis() - head.arrived() < (long) (chunkDelay.get() * 1000)) break;
                c = pending.pollFirst().chunk();
            }
            if (c == null) break;
            s.submit(() -> { try { analyze(c); } catch (Throwable ignored) {} });
        }

        // forget chunks well outside the draw range so the map doesn't grow forever
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int keep = chunkRange.get() + 12;
        holes.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep
                                  || Math.abs(new ChunkPos(k).z - pcz) > keep);
    }

    private void analyze(WorldChunk chunk) {
        List<int[]> found = new ArrayList<>();
        List<int[]> pockets = new ArrayList<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY() + 1, top = chunk.getTopYInclusive() - 1;
        int depth = holeDepth();                  // same for the whole chunk, so work it out once
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = bottom; y <= top; y++) {
                if (sealedPockets.get() && y >= pocketMinY.get() && isSealedPocket(chunk, bx + x, y, bz + z, bottom, top))
                    pockets.add(new int[]{bx + x, y, bz + z});
                if (!isFillBlock(chunk, bx + x, y, bz + z)) continue;

                // The block has to be SEALING the shaft, not just sitting inside one that is already
                // open. If there is air above it, you are looking down an open hole at a block that
                // happens to be in it — which is not somebody plugging anything.
                if (y + 1 <= top && chunk.getBlockState(m.set(bx + x, y + 1, bz + z)).isAir()) {
                    // the one exception is a cap flush with the surface, where the sky is above it
                    boolean openToSky = true;
                    for (int u = y + 1; u <= Math.min(top, y + 6); u++) {
                        if (!chunk.getBlockState(m.set(bx + x, u, bz + z)).isAir()) { openToSky = false; break; }
                    }
                    if (!openToSky || y < surfaceCapY.get()) continue;
                }

                boolean deepEnough = true;
                for (int d = 1; d <= depth; d++) {
                    if (y - d < bottom || !chunk.getBlockState(m.set(bx + x, y - d, bz + z)).isAir()) { deepEnough = false; break; }
                }
                if (!deepEnough) continue;
                if (!isNarrowHole(chunk, bx + x, y - 1, bz + z)) continue;
                found.add(new int[]{bx + x, y, bz + z});
            }
        }

        long key = chunk.getPos().toLong();
        // The minimum is checked HERE, before anything is stored, so a chunk under the threshold
        // never gets highlighted at all.
        if (!pockets.isEmpty()) pocketsByChunk.put(key, pockets); else pocketsByChunk.remove(key);
        if (found.size() >= sensitivity.get()) {
            holes.put(key, found);
            if (chat.get() && announced.add(key))
                shama.addon.util.Chat.info("[HoleFinder] %d covered holes in chunk %d, %d",
                    found.size(), chunk.getPos().x, chunk.getPos().z);
        } else {
            holes.remove(key);
        }
    }

    /**
     * True when this air block is part of a pocket walled in on every side, no taller than the
     * limit. The bottom of the pocket is what gets reported, so a two-high pocket marks once.
     */
    /**
     * Is there a solid block here? Reads from the chunk being scanned, and steps into the
     * neighbouring chunk when the position falls outside it. A neighbour that is not loaded counts
     * as solid, so a pocket on the boundary is kept rather than thrown away for being half-unknown.
     */
    private boolean solidAt(WorldChunk chunk, BlockPos.Mutable m, int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx == chunk.getPos().x && cz == chunk.getPos().z) return !chunk.getBlockState(m.set(x, y, z)).isAir();
        if (mc.world == null) return true;
        var n = mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
        if (!(n instanceof WorldChunk wc)) return true;          // not loaded: assume solid
        return !wc.getBlockState(m.set(x, y, z)).isAir();
    }

    private boolean airAt(WorldChunk chunk, BlockPos.Mutable m, int x, int y, int z) {
        return !solidAt(chunk, m, x, y, z);
    }

    private boolean isSealedPocket(WorldChunk chunk, int x, int y, int z, int bottom, int top) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        if (!chunk.getBlockState(m.set(x, y, z)).isAir()) return false;
        if (y - 1 < bottom || airAt(chunk, m, x, y - 1, z)) return false;                      // floor must be solid

        // how tall is the column of air above this block
        int h = 1;
        while (y + h <= top && airAt(chunk, m, x, y + h, z)) {
            h++;
            if (h > maxPocketHeight.get()) return false;                                       // too tall, it is a cave
        }
        if (y + h > top || airAt(chunk, m, x, y + h, z)) return false;                          // must be capped

        // every side of every level has to be solid, or it opens into something
        for (int d = 0; d < h; d++) {
            if (airAt(chunk, m, x + 1, y + d, z)) return false;
            if (airAt(chunk, m, x - 1, y + d, z)) return false;
            if (airAt(chunk, m, x, y + d, z + 1)) return false;
            if (airAt(chunk, m, x, y + d, z - 1)) return false;
        }
        return true;
    }

    /**
     * A block used to cap a hole.
     *
     * Two kinds count. The first is the obvious patch job — cobble, obsidian, dirt — which does
     * occur naturally, so it is the depth and shape checks that make those worth anything.
     *
     * The second is the giveaway: blocks that do not generate underground at all. Terracotta,
     * concrete, wool, planks, bricks, glass, copper, netherrack in the Overworld — none of it forms
     * down there on its own, so a single one capping a shaft is somebody's doing regardless of how
     * deep the hole is or what shape it takes. People reach for whatever is in their hotbar when
     * they are sealing something behind them, and it is usually not cobblestone.
     */
    private boolean isFillBlock(WorldChunk chunk, int x, int y, int z) {
        String p = shama.addon.util.BlockPaths.of(chunk.getBlockState(new BlockPos(x, y, z)).getBlock());

        // patch-job blocks: common, and do occur naturally
        if (p.equals("cobblestone") || p.equals("mossy_cobblestone") || p.equals("cobbled_deepslate")
            || p.equals("obsidian") || p.equals("crying_obsidian")
            || p.equals("dirt") || p.equals("coarse_dirt") || p.equals("rooted_dirt") || p.equals("grass_block")
            || p.equals("netherrack") || p.equals("cobbled_deepslate") || p.equals("stone")) return true;

        if (!obscureFills.get()) return false;

        // things that never generate underground, so one of them alone is enough
        return p.endsWith("_planks") || p.endsWith("_wool") || p.endsWith("_concrete")
            || p.endsWith("_concrete_powder") || p.endsWith("_terracotta") || p.equals("terracotta")
            || p.endsWith("_glass") || p.equals("glass") || p.endsWith("_glass_pane")
            || p.endsWith("_stained_glass") || p.equals("bricks") || p.endsWith("_bricks")
            || p.equals("stone_bricks") || p.equals("smooth_stone") || p.equals("stone_slab")
            || p.endsWith("_slab") || p.endsWith("_stairs") || p.endsWith("_log") || p.endsWith("_wood")
            || p.equals("bookshelf") || p.equals("crafting_table") || p.equals("furnace")
            || p.equals("copper_block") || p.endsWith("_copper") || p.equals("iron_block")
            || p.equals("gold_block") || p.equals("diamond_block") || p.equals("netherite_block")
            || p.equals("quartz_block") || p.endsWith("_quartz") || p.equals("blackstone")
            || p.endsWith("_blackstone") || p.equals("basalt") || p.equals("smooth_basalt")
            || p.equals("purpur_block") || p.equals("end_stone") || p.equals("end_stone_bricks")
            || p.equals("prismarine") || p.endsWith("_prismarine") || p.equals("sea_lantern")
            || p.equals("glowstone") || p.equals("shroomlight") || p.equals("hay_block")
            || p.equals("packed_ice") || p.equals("blue_ice") || p.equals("snow_block")
            || p.equals("moss_block") || p.equals("mud_bricks") || p.equals("packed_mud");
    }

    /**
     * True when the air below is an enclosed dig rather than open cave. Accepts every shape people
     * actually make: 1x1 ladder shafts, 2x1 and 3x1 corridors, 3x3 rooms, and staircases (which
     * step sideways as they descend, so the walls sit further out on one axis).
     */
    private boolean isNarrowHole(WorldChunk chunk, int x, int y, int z) {
        int w = maxWidth.get();
        boolean boundedX = wallWithin(chunk, x, y, z, 1, 0, w) && wallWithin(chunk, x, y, z, -1, 0, w);
        boolean boundedZ = wallWithin(chunk, x, y, z, 0, 1, w) && wallWithin(chunk, x, y, z, 0, -1, w);
        if (boundedX && boundedZ) return true;                 // fully enclosed: 1x1 up to 3x3
        if (boundedX || boundedZ) return true;                 // corridor: 2x1, 3x1
        return stairs.get() && stairLike(chunk, x, y, z, w);
    }

    /** A staircase drops one block per step, so look for enclosing walls a little further out. */
    private boolean stairLike(WorldChunk chunk, int x, int y, int z, int w) {
        int reach = w + 2;
        boolean x1 = wallWithin(chunk, x, y, z, 1, 0, reach), x2 = wallWithin(chunk, x, y, z, -1, 0, reach);
        boolean z1 = wallWithin(chunk, x, y, z, 0, 1, reach), z2 = wallWithin(chunk, x, y, z, 0, -1, reach);
        return (x1 && x2) || (z1 && z2);
    }

    private boolean wallWithin(WorldChunk chunk, int x, int y, int z, int dx, int dz, int max) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int i = 1; i <= max; i++) {
            if (!chunk.getBlockState(m.set(x + dx * i, y, z + dz * i)).isAir()) return true;
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || (holes.isEmpty() && pocketsByChunk.isEmpty())) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int r = chunkRange.get();
        SettingColor fc = fillColor.get(), lc = lineColor.get();
        Color fill = new Color(fc.r, fc.g, fc.b, fc.a), line = new Color(lc.r, lc.g, lc.b, lc.a);
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;

        if (sealedPockets.get()) {
            SettingColor pc = pocketColor.get();
            Color pf = new Color(pc.r, pc.g, pc.b, 70), pl = new Color(pc.r, pc.g, pc.b, pc.a);
            for (Map.Entry<Long, List<int[]>> e : pocketsByChunk.entrySet()) {
                ChunkPos cp = new ChunkPos(e.getKey());
                if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
                for (int[] b : e.getValue())
                    event.renderer.box(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1, pf, pl, shapeMode.get(), 0);
            }
        }
        for (Map.Entry<Long, List<int[]>> e : holes.entrySet()) {
            ChunkPos cp = new ChunkPos(e.getKey());
            if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
            for (int[] b : e.getValue()) {
                // only the capping block, never a box around the whole chunk
                event.renderer.box(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1, fill, line, shapeMode.get(), 0);
                if (tracers.get())
                    event.renderer.line(cam.x, cam.y, cam.z, b[0] + 0.5, b[1] + 0.5, b[2] + 0.5, line);
            }
        }
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (List<int[]> l : holes.values()) n += l.size();
        return n == 0 ? null : Integer.toString(n);
    }
}
