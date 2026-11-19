package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active Chunk Detector — finds ground somebody else has been keeping alive.
 *
 * A chunk the server has to read from disk or generate takes real time to hand over. One that is
 * already sitting in memory — because a player, a chunk loader or a farm is holding it open — comes
 * back almost instantly. A long run of instant arrivals is therefore a fingerprint of somebody
 * else's presence, and it shows up even when there is nothing to see and nobody in range.
 *
 * Alongside it: whether a chunk comes back different from how you left it, in the parts that only
 * change while a player is near it; whether its plants are grown past what they reach on their own;
 * and whether its blocks change while nobody visible is nearby. Everything you cause yourself —
 * pearls, teleports, ground you only just left — is put down to you and never flagged.
 */
public class ActiveChunkDetector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> chunkRange = sg.add(new IntSetting.Builder()
        .name("chunk-range").description("How far out to keep showing traces, in chunks.")
        .defaultValue(16).min(1).max(64).sliderRange(4, 32).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat").description("Print a message the first time each area is picked up.").defaultValue(true).build());

    // ---------------------------------------------------------------- methods
    private final SettingGroup sgMethods = settings.createGroup("Methods");

    private final Setting<Boolean> instantArrival = sgMethods.add(new BoolSetting.Builder()
        .name("held-in-memory")
        .description("Flag chunks the server hands back too fast to have come off disk. Reading a chunk from storage takes real time; one already sitting in memory does not, and it is only in memory because something is keeping it there.")
        .defaultValue(true).build());
    private final Setting<Integer> instantMs = sgMethods.add(new IntSetting.Builder()
        .name("instant-threshold")
        .description("A chunk arriving within this many milliseconds of the one before counts as instant. Lower is stricter.")
        .defaultValue(3).min(1).max(50).sliderRange(1, 20).visible(instantArrival::get).build());
    private final Setting<Integer> instantRun = sgMethods.add(new IntSetting.Builder()
        .name("instant-run")
        .description("How many instant arrivals in a row before it counts. A couple happen naturally; a long run does not.")
        .defaultValue(20).min(4).max(200).sliderRange(8, 60).visible(instantArrival::get).build());

    private final Setting<Boolean> adaptive = sgMethods.add(new BoolSetting.Builder()
        .name("adaptive-timing")
        .description("Work out what a normal arrival gap looks like on this server rather than using a fixed number. Servers that tick regions on separate threads — Folia and its forks — deliver chunks in bursts that a fixed threshold either misses entirely or fires on constantly. Learning the baseline first fixes both.")
        .defaultValue(true).visible(instantArrival::get).build());

    private final Setting<Boolean> tickEvidence = sgMethods.add(new BoolSetting.Builder()
        .name("has-grown")
        .description("Flag chunks holding crops, saplings or plants grown past the stage they reach on their own. Growth only happens while a chunk is being ticked, so advanced growth is proof the ground was held open — and because it is written into the block itself, it survives relogs, works at any distance and does not care how the server threads its regions. This is the one that cannot be fooled.")
        .defaultValue(true).build());
    private final Setting<Integer> grownMin = sgMethods.add(new IntSetting.Builder()
        .name("min-grown")
        .description("How many well-grown plants a chunk needs before it counts. A couple grow naturally near spawn chunks; a field of them does not.")
        .defaultValue(12).min(2).max(200).sliderRange(4, 60).visible(tickEvidence::get).build());

    private final Setting<Integer> myRange = sgMethods.add(new IntSetting.Builder()
        .name("ignore-my-range")
        .description("Ignore chunks this close to you, in chunks. Your own presence is what keeps nearby ground loaded and ticking, so anything inside your render distance would flag on you rather than on anybody else. Set it to roughly your render distance.")
        .defaultValue(12).min(0).max(48).sliderRange(4, 24).build());

    private final Setting<Boolean> stateCompare = sgMethods.add(new BoolSetting.Builder()
        .name("state-change")
        .description("Remember each chunk exactly as it was when it left your view, and compare it with how it comes back. Crops, saplings, leaves, grass, vines and the like only change while a player is close enough for the ground to be ticked, so if those have moved while you were away, somebody was there. On servers that hide the ground below a height by filling it in when you are alone, the same comparison catches the moment real ground starts being sent instead — which only happens because someone else is down there.")
        .defaultValue(true).build());
    private final Setting<Integer> minGapSeconds = sgMethods.add(new IntSetting.Builder()
        .name("min-gap")
        .description("How long you must have been away before a comparison counts, in seconds. Too short and you catch normal ticking from your own presence.")
        .defaultValue(60).min(5).max(3600).sliderRange(15, 600).visible(stateCompare::get).build());
    private final Setting<Integer> minDrift = sgMethods.add(new IntSetting.Builder()
        .name("min-change")
        .description("How many growing blocks have to have changed before it counts. A plant at the very edge can still be ticked for a moment after you leave; a handful more than that means somebody else.")
        .defaultValue(3).min(1).max(100).sliderRange(1, 20).visible(stateCompare::get).build());
    private final Setting<Integer> hiddenBelow = sgMethods.add(new IntSetting.Builder()
        .name("hidden-below-y")
        .description("The height below which a server may be hiding the ground by filling it in solid. A section below this that comes back solid with one or two kinds of block is treated as filled in; one that comes back with real caves and ores is real. A chunk switching from one to the other is the tell.")
        .defaultValue(0).min(-64).max(320).sliderRange(-64, 64).visible(stateCompare::get).build());

    private final Setting<Boolean> quietChanges = sgMethods.add(new BoolSetting.Builder()
        .name("changes-unattended")
        .description("Flag chunks whose blocks change while no player is visible to you. Something is running there — a farm ticking, or somebody out of render distance.")
        .defaultValue(true).build());
    private final Setting<Integer> quietMin = sgMethods.add(new IntSetting.Builder()
        .name("min-changes")
        .description("How many changes with nobody visible before it counts.")
        .defaultValue(20).min(2).max(500).sliderRange(5, 100).visible(quietChanges::get).build());

    // ---------------------------------------------------------------- render
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> boxY = sgRender.add(new DoubleSetting.Builder()
        .name("box-y").description("Height to draw the boxes at. Sea level by default so they sit where you can see them.")
        .defaultValue(63).min(-64).max(320).sliderRange(-64, 200).build());
    private final Setting<SettingColor> traceColor = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Colour of the trace boxes.")
        .defaultValue(new SettingColor(90, 0, 160, 90)).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.")
        .defaultValue(ShapeMode.Both).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each trace.").defaultValue(false).build());

    private final Map<Long, String> traces = new ConcurrentHashMap<>();
    private final Map<Long, Integer> quietCount = new ConcurrentHashMap<>();
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    private long prevChunkAt;
    private int streak;
    /** Recent gaps between chunk arrivals, used to learn what this server considers normal. */
    private final java.util.ArrayDeque<Long> gaps = new java.util.ArrayDeque<>();

    /** The gap below which an arrival counts as instant on this particular server. */
    private long instantCutoff() {
        if (!adaptive.get() || gaps.size() < 40) return instantMs.get();
        java.util.List<Long> sorted = new java.util.ArrayList<>(gaps);
        java.util.Collections.sort(sorted);
        long median = sorted.get(sorted.size() / 2);
        // a quarter of normal is comfortably "already in memory" on any server, threaded or not
        return Math.max(1, Math.min(median / 4, instantMs.get() * 4L));
    }

    public ActiveChunkDetector() {
        super(shama.addon.ShamaAddon.HUNT, "active-chunk-detector++",
            "Tells a chunk somebody is holding open from one nobody has touched, by reading the parts of its state that only move while a player is near it.");
    }

    @Override
    public void onActivate() { traces.clear(); quietCount.clear(); announced.clear(); prevChunkAt = 0; streak = 0; gaps.clear(); leftStates.clear(); }

    @Override
    public void onDeactivate() {
        traces.clear(); quietCount.clear(); announced.clear(); leftStates.clear();
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }

    /** True when this chunk is close enough that YOU are the reason it is live. */
    private boolean minesNotTheirs(long key) {
        if (mc.player == null || myRange.get() <= 0) return false;
        ChunkPos cp = new ChunkPos(key);
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        return Math.abs(cp.x - pcx) <= myRange.get() && Math.abs(cp.z - pcz) <= myRange.get();
    }

    private void mark(long key, String why) {
        if (minesNotTheirs(key)) return;        // you are what is keeping this one alive
        traces.put(key, why);
        if (chat.get() && announced.add(key)) {
            ChunkPos cp = new ChunkPos(key);
            shama.addon.util.Chat.info("[ActiveChunk] %d, %d — %s", cp.getStartX() + 8, cp.getStartZ() + 8, why);
        }
    }

    private java.util.concurrent.ExecutorService worker;

    private java.util.concurrent.ExecutorService worker() {
        if (worker == null || worker.isShutdown()) {
            worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "shama-activechunk");
                t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return worker;
    }

    private void offThread(Runnable r) {
        try { worker().submit(() -> { try { r.run(); } catch (Throwable ignored) {} }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        long key = chunk.getPos().toLong();
        long now = System.currentTimeMillis();
        var self = shama.addon.util.SelfActivity.INSTANCE;

        // instant arrivals: the server already had this open for somebody. Not after a pearl or a
        // teleport of yours, when everything streams back at once, and not ground you only just
        // left — the server is still holding that because of you.
        if (instantArrival.get()) {
            if (self.teleportedWithin(6000)) streak = 0;
            else if (!self.leftRecently(key, 10 * 60_000L)) {
                if (prevChunkAt > 0) {
                    gaps.addLast(now - prevChunkAt);
                    while (gaps.size() > 200) gaps.pollFirst();
                }
                if (prevChunkAt > 0 && now - prevChunkAt <= instantCutoff()) {
                    if (++streak >= instantRun.get()) mark(key, "came back instantly, already loaded");
                } else streak = 0;
            }
        }
        prevChunkAt = now;

        // the reading itself happens on a worker thread; these walk the sections of the chunk
        boolean compare = stateCompare.get(), growth = tickEvidence.get();
        if (!compare && !growth) return;
        final int grownNeeded = grownMin.get();
        offThread(() -> {
            if (compare) {
                State nowState = readState(chunk);
                State old = leftStates.remove(key);
                if (old != null) compareStates(key, old, nowState);
            }
            if (growth) {
                int grown = countGrown(chunk, grownNeeded);
                if (grown >= grownNeeded) mark(key, grown + " plants grown past what happens on their own");
            }
        });
    }

    /** A chunk leaving your view: remember exactly how it was, to compare when it comes back. */
    @EventHandler
    private void onUnload(PacketEvent.Receive event) {
        if (!stateCompare.get() || mc.world == null) return;
        if (!(event.packet instanceof net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket p)) return;
        ChunkPos cp = p.comp_1726();
        var ch = mc.world.getChunk(cp.x, cp.z, net.minecraft.world.chunk.ChunkStatus.FULL, false);
        if (!(ch instanceof WorldChunk wc)) return;
        long key = cp.toLong();
        // the chunk object stops changing once it is dropped, so reading it a moment later is exact
        offThread(() -> {
            if (leftStates.size() > 20000) leftStates.clear();
            leftStates.put(key, readState(wc));
        });
    }

    /**
     * What a chunk looked like, reduced to the parts that matter.
     *
     * growing: how many of each block that only changes when the ground is ticked — crops, saplings,
     *          leaves, grass, vines, kelp, cane and the rest. They sit still in a chunk nobody is near.
     * filled:  how many sections below the hiding height came back as a solid fill of one or two
     *          kinds of block, which natural ground never is.
     */
    private record State(Map<Integer, Integer> growing, int filled, long when) {}
    private final Map<Long, State> leftStates = new ConcurrentHashMap<>();

    private State readState(WorldChunk chunk) {
        Map<Integer, Integer> growing = new java.util.HashMap<>();
        int filled = 0;
        int below = hiddenBelow.get();
        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
        for (int si = 0; si < sections.length; si++) {
            net.minecraft.world.chunk.ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty()) continue;
            int sy = chunk.sectionIndexToCoord(si) << 4;
            boolean low = sy + 15 < below;
            boolean ticks = sec.hasRandomBlockTicks();
            if (!low && !ticks) continue;
            int[] kinds = {0, 0};                       // distinct solid states, solid blocks
            sec.getBlockStateContainer().count((st, n) -> {
                if (st.isAir()) return;
                kinds[0]++; kinds[1] += n;
                // amethyst and dripstone are left out: some servers hide those on their own, and
                // their coming and going would read as growth
                if (ticks && st.hasRandomTicks() && !isHiddenBySomeServers(st))
                    growing.merge(net.minecraft.block.Block.getRawIdFromState(st), n, Integer::sum);
            });
            if (low && kinds[1] == 4096 && kinds[0] <= 2) filled++;
        }
        return new State(growing, filled, System.currentTimeMillis());
    }

    private static boolean isHiddenBySomeServers(net.minecraft.block.BlockState st) {
        String p = shama.addon.util.BlockPaths.of(st.getBlock());
        return p.contains("amethyst") || p.contains("dripstone");
    }

    private void compareStates(long key, State old, State now) {
        long gap = (now.when() - old.when()) / 1000;
        if (gap < minGapSeconds.get()) return;               // you were only just here

        // ground that was filled in when you left and is real now — or the other way round
        if (old.filled() > 0 && now.filled() == 0) {
            mark(key, "hidden ground came back real after " + gap + "s — someone else is down there");
            return;
        }
        if (old.filled() == 0 && now.filled() >= 2) {
            mark(key, "real ground was being sent here and now it is filled in — someone was down there");
            return;
        }

        int changed = 0;
        for (var e : old.growing().entrySet()) changed += Math.abs(e.getValue() - now.growing().getOrDefault(e.getKey(), 0));
        for (var e : now.growing().entrySet()) if (!old.growing().containsKey(e.getKey())) changed += e.getValue();
        changed /= 2;                                        // each change moves one block between two states
        if (changed >= minDrift.get())
            mark(key, changed + " growing blocks changed in the " + gap + "s you were away");
    }

    /** How many well-grown plants a chunk holds, stopping once it has enough. */
    private int countGrown(WorldChunk chunk, int enough) {
        int grown = 0;
        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
        for (int si = 0; si < sections.length; si++) {
            net.minecraft.world.chunk.ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty() || !sec.hasAny(this::grownCandidate)) continue;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var st = sec.getBlockState(x, y, z);
                if (!st.isAir() && wellGrown(st) && ++grown >= enough) return grown;
            }
        }
        return grown;
    }

    /** Cheap first pass for the palette check: could this block type ever count as grown? */
    private boolean grownCandidate(net.minecraft.block.BlockState st) {
        if (st.isAir()) return false;
        return switch (shama.addon.util.BlockPaths.of(st.getBlock())) {
            case "wheat", "carrots", "potatoes", "beetroots", "nether_wart", "sugar_cane", "cactus", "bamboo",
                 "kelp", "twisting_vines", "weeping_vines", "pointed_dripstone", "amethyst_cluster", "large_amethyst_bud" -> true;
            default -> false;
        };
    }

    /** Plants that only reach an advanced stage if something kept ticking the chunk. */
    private boolean wellGrown(net.minecraft.block.BlockState st) {
        String p = shama.addon.util.BlockPaths.of(st.getBlock());
        // amethyst goes through the shared matcher, then only the grown stages count as tick evidence
        if (shama.addon.util.AmethystScan.isAmethystLike(st))
            return p.equals("amethyst_cluster") || p.equals("large_amethyst_bud");
        switch (p) {
            case "wheat", "carrots", "potatoes", "beetroots", "nether_wart" -> {
                // The age is read out of the state's own text rather than through the property API,
                // whose method names have moved between versions. toString gives e.g. [age=7].
                String s2 = st.toString();
                int i = s2.indexOf("age=");
                if (i < 0) return false;
                int j = i + 4, v = 0;
                while (j < s2.length() && Character.isDigit(s2.charAt(j))) { v = v * 10 + (s2.charAt(j) - '0'); j++; }
                return v >= 5;                                          // near or at full growth
            }
            // these only stack up over many random ticks
            case "sugar_cane", "cactus", "bamboo", "kelp", "twisting_vines", "weeping_vines" -> { return true; }
            case "pointed_dripstone" -> { return true; }
            default -> { return false; }
        }
    }

    @EventHandler
    private void onBlockChange(PacketEvent.Receive event) {
        if (!quietChanges.get() || mc.world == null || mc.player == null) return;
        if (!(event.packet instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket p)) return;

        // somebody visible could account for it; only count changes with nobody around
        for (var pl : mc.world.getPlayers()) {
            if (pl == mc.player) continue;
            if (pl.squaredDistanceTo(p.getPos().getX(), p.getPos().getY(), p.getPos().getZ()) < 64 * 64) return;
        }
        long key = ChunkPos.toLong(p.getPos().getX() >> 4, p.getPos().getZ() >> 4);
        if (quietCount.merge(key, 1, Integer::sum) >= quietMin.get()) {
            quietCount.remove(key);
            mark(key, "blocks changing with nobody in sight");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || traces.isEmpty()) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int keep = chunkRange.get() + 12;
        traces.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep
                                   || Math.abs(new ChunkPos(k).z - pcz) > keep);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || traces.isEmpty()) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get();
        SettingColor c = traceColor.get();
        Color fill = new Color(c.r, c.g, c.b, c.a);
        Color line = new Color(c.r, c.g, c.b, Math.min(255, c.a + 140));
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;

        for (long key : traces.keySet()) {
            ChunkPos cp = new ChunkPos(key);
            if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
            double x0 = cp.getStartX(), z0 = cp.getStartZ(), y = boxY.get();
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, fill, line, shapeMode.get(), 0);
            if (tracers.get()) event.renderer.line(cam.x, cam.y, cam.z, x0 + 8, y, z0 + 8, line);
        }
    }

    @Override
    public String getInfoString() { return traces.isEmpty() ? null : Integer.toString(traces.size()); }
}
