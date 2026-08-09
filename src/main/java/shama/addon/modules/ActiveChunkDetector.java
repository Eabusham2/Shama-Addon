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
 * Player Trace — finds ground somebody else has been keeping alive.
 *
 * A chunk the server has to read from disk or generate takes real time to hand over. One that is
 * already sitting in memory — because a player, a chunk loader or a farm is holding it open — comes
 * back almost instantly. A long run of instant arrivals is therefore a fingerprint of somebody
 * else's presence, and it shows up even when there is nothing to see and nobody in range.
 *
 * Two other tells are checked alongside it: how long the game says players have spent in a chunk,
 * and whether its contents change while nobody visible is nearby.
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

    private final Setting<Boolean> stateCompare = sgMethods.add(new BoolSetting.Builder()
        .name("state-change")
        .description("Compare a chunk against how it looked last time and see whether the parts that only move while it is loaded have moved. The clock the game keeps of how long players have spent in a chunk is the main one: it advances while somebody is in range and sits perfectly still otherwise. So if it has gone up between two sightings, a player was there in between. Nothing else can move it, which makes this proof rather than a guess.")
        .defaultValue(true).build());
    private final Setting<Integer> minGapSeconds = sgMethods.add(new IntSetting.Builder()
        .name("min-gap")
        .description("How long you must have been away before a comparison counts, in seconds. Too short and you catch normal ticking from your own presence.")
        .defaultValue(60).min(5).max(3600).sliderRange(15, 600).visible(stateCompare::get).build());
    private final Setting<Integer> minDrift = sgMethods.add(new IntSetting.Builder()
        .name("min-change")
        .description("How many seconds the inhabited clock has to gain before it counts. One or two can come from you passing through yourself; more than that means somebody else.")
        .defaultValue(3).min(1).max(100).sliderRange(1, 20).visible(stateCompare::get).build());

    private final Setting<Boolean> inhabited = sgMethods.add(new BoolSetting.Builder()
        .name("total-time-here")
        .description("Flag chunks the game says players have spent a long time in. It is recorded per chunk and survives after they leave, so it points at where somebody actually lives rather than where they walked.")
        .defaultValue(true).build());
    private final Setting<Integer> inhabitedMinutes = sgMethods.add(new IntSetting.Builder()
        .name("minutes-spent")
        .description("How many minutes of recorded presence a chunk needs.")
        .defaultValue(120).min(1).max(6000).sliderRange(10, 600).visible(inhabited::get).build());

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
    public void onActivate() { traces.clear(); quietCount.clear(); announced.clear(); prevChunkAt = 0; streak = 0; gaps.clear(); snapshots.clear(); }

    @Override
    public void onDeactivate() { traces.clear(); quietCount.clear(); announced.clear(); }

    private void mark(long key, String why) {
        traces.put(key, why);
        if (chat.get() && announced.add(key)) {
            ChunkPos cp = new ChunkPos(key);
            shama.addon.util.Chat.info("[ActiveChunk] %d, %d — %s", cp.getStartX() + 8, cp.getStartZ() + 8, why);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        long key = chunk.getPos().toLong();
        long now = System.currentTimeMillis();

        // instant arrivals: the server already had this open for somebody
        if (instantArrival.get()) {
            if (prevChunkAt > 0) {
                gaps.addLast(now - prevChunkAt);
                while (gaps.size() > 200) gaps.pollFirst();
            }
            if (prevChunkAt > 0 && now - prevChunkAt <= instantCutoff()) {
                if (++streak >= instantRun.get()) mark(key, "came back instantly, already loaded");
            } else streak = 0;
        }
        prevChunkAt = now;

        // compare against what this chunk looked like last time we were here
        if (stateCompare.get()) {
            long[] now2 = chunkState(chunk);
            Snapshot old = snapshots.get(key);
            if (old != null) {
                long gap = (System.currentTimeMillis() - old.when()) / 1000;
                long clock = now2[0] - old.state()[0];          // inhabited ticks gained
                long ents = Math.abs(now2[1] - old.state()[1]);
                long bes = Math.abs(now2[2] - old.state()[2]);

                if (gap >= minGapSeconds.get()) {
                    if (clock >= minDrift.get() * 20L) {
                        // the clock only runs while somebody is in range, so this is not ambiguous
                        mark(key, "a player has been here for " + (clock / 20) + "s of the last " + gap + "s");
                    } else if (clock <= 0 && (ents + bes) >= minDrift.get()) {
                        // the clock did not move but the contents did: loaded from further out
                        mark(key, "contents changed with the clock still — loaded from out of range");
                    }
                }
            }
            // keep the map bounded; the oldest entries are the least useful anyway
            if (snapshots.size() > 20000) snapshots.clear();
            snapshots.put(key, new Snapshot(now2, System.currentTimeMillis()));
        }

        // growth: written into the blocks themselves, so it holds up on any server
        if (tickEvidence.get()) {
            int grown = 0;
            int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
            net.minecraft.util.math.BlockPos.Mutable m = new net.minecraft.util.math.BlockPos.Mutable();
            outer:
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = chunk.getBottomY(); y <= chunk.getTopYInclusive(); y++) {
                    var st = chunk.getBlockState(m.set(bx + x, y, bz + z));
                    if (st.isAir()) continue;
                    if (wellGrown(st) && ++grown >= grownMin.get()) {
                        mark(key, grown + " plants grown past what happens on their own");
                        break outer;
                    }
                }
        }

        // recorded presence
        if (inhabited.get()) {
            try {
                long ticks = chunk.getInhabitedTime();
                if (ticks / 20 / 60 >= inhabitedMinutes.get()) mark(key, (ticks / 20 / 60) + " minutes spent here");
            } catch (Throwable ignored) {}
        }
    }

    /** What a chunk looked like last time, and when. */
    private record Snapshot(long[] state, long when) {}
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * The parts of a chunk's own state that only move while it is loaded.
     *
     * The inhabited clock is the important one — the game advances it while a player is within
     * range of the chunk and leaves it completely alone otherwise, so it cannot drift on its own.
     * Entity and block-entity counts come along as a weaker second opinion, since neither changes
     * in a chunk nobody is ticking.
     */
    private long[] chunkState(WorldChunk chunk) {
        long inhabited = 0, entities = 0, blockEntities = 0;
        try { inhabited = chunk.getInhabitedTime(); } catch (Throwable ignored) {}
        try { blockEntities = chunk.getBlockEntities().size(); } catch (Throwable ignored) {}
        try {
            if (mc.world != null) {
                int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
                for (var e : mc.world.getEntities()) {
                    if (e.getBlockX() >> 4 == chunk.getPos().x && e.getBlockZ() >> 4 == chunk.getPos().z) entities++;
                }
            }
        } catch (Throwable ignored) {}
        return new long[]{inhabited, entities, blockEntities};
    }

    /** Plants that only reach an advanced stage if something kept ticking the chunk. */
    private boolean wellGrown(net.minecraft.block.BlockState st) {
        String p = shama.addon.util.BlockPaths.of(st.getBlock());
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
            case "amethyst_cluster", "large_amethyst_bud" -> { return true; }
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
