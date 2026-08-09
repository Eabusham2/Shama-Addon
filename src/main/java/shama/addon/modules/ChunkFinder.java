package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Chunk Finder++ — merged from several base/stash chunk-finder variants. Pick which
 * detection METHOD(s) run; a chunk is flagged if any enabled method hits. Each method is
 * the one the originals actually used:
 *
 *   Geology  - isolated vertical mineral LINES (cobblestone/tuff/andesite/diorite/obsidian
 *              columns over a length, with no same-block within an isolation radius = a
 *              player pillar/tunnel, not natural), plus deepslate anomalies (normal above
 *              Y16, cobbled, or rotated). Trial-chamber-heavy chunks are skipped.
 *   Entities - kept pets / farm mobs above Y16 (villager, iron golem, fox, cat) within range.
 *   Growth   - farm-scale growth: kelp or vine columns at least 'growth-length' tall.
 *   Velocity - a nonzero entity-velocity packet reveals a loaded/active chunk.
 *
 * Flagged chunks render a flat box at 'render-y' with optional tracers, and nearby flags
 * merge so one base doesn't spam markers.
 */
public class ChunkFinder extends Module {
    /** Extra chunks beyond the draw range that we keep results for, so highlights stay put as you move. */
    private static final int KEEP_MARGIN = 12;
    /** Chunks already announced in chat — never pruned, so nothing is reported twice. */
    private final java.util.Set<Long> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public enum MethodPreset { All, Fast, Thorough, Custom }

    private final SettingGroup sgMethods = settings.getDefaultGroup();
    private final Setting<Integer> ignoreNearMe = sgMethods.add(new IntSetting.Builder()
        .name("ignore-near-me")
        .description("Never flag chunks this close to you. Mining or building changes the very blocks this looks for, so without it your own work keeps flagging the chunk you are standing in.")
        .defaultValue(2).min(0).max(16).sliderRange(0, 8).build());

    private final Setting<Double> chunkDelay = sgMethods.add(new DoubleSetting.Builder()
        .name("chunk-delay")
        .description("Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.")
        .defaultValue(0.0).min(0).max(10).sliderRange(0, 3).decimalPlaces(1).build());

    private final Setting<Integer> minEvidence = sgMethods.add(new IntSetting.Builder().name("sensitivity").description("How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.").defaultValue(3).min(1).max(20).sliderRange(1, 20).build());
    private final Setting<Integer> zoneSize = sgMethods.add(new IntSetting.Builder()
        .name("scan-zone-size")
        .description("Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.")
        .defaultValue(1).min(1).max(6).sliderRange(1, 6).build());

    /** Collapse a chunk coord onto its zone, so neighbouring chunks count as one place. */
    private long zoneKey(int cx, int cz) {
        int n = Math.max(1, zoneSize.get());
        return ChunkPos.toLong(Math.floorDiv(cx, n) * n, Math.floorDiv(cz, n) * n);
    }

    private final Setting<Boolean> customThresholds = sgMethods.add(new BoolSetting.Builder()
        .name("custom-thresholds")
        .description("Set each threshold by hand instead of letting the sensitivity slider work them out. Leave this off unless you want to fine-tune one particular detection.")
        .defaultValue(false).build());

    /**
     * How many hits are needed before something is flagged. Normally that's just the sensitivity
     * slider — one number, so sensitivity 1 flags on a single detection and sensitivity 10 needs
     * ten. Turning on custom thresholds lets each detection use its own number instead.
     */
    /**
     * A threshold that isn't a simple hit count — a column height, a weighted score, a tunnel
     * length. It follows sensitivity in the same direction: a low number is looser, a high number
     * is tighter. At the default sensitivity it sits on its own tuned value.
     */
    private int scaled(Setting<Integer> setting, int base) {
        if (customThresholds.get()) return setting.get();
        return Math.max(1, (int) Math.round(base * (minEvidence.get() / 3.0)));
    }

    private int hitsNeeded(Setting<Integer> setting) {
        return customThresholds.get() ? setting.get() : minEvidence.get();
    }

    private final Setting<MethodPreset> methodPreset = sgMethods.add(new EnumSetting.Builder<MethodPreset>()
        .name("methods")
        .description("Which detection methods to run. Fast = the cheap packet-based ones only (kind to your framerate). Thorough = everything including the block scans. All = same as Thorough. Custom = pick them yourself below.")
        .defaultValue(MethodPreset.All).build());
    private final Setting<Integer> scanRate = sgMethods.add(new IntSetting.Builder()
        .name("scan-rate")
        .description("How many newly loaded chunks to analyse each tick. Lower this if the game stutters while flying or after an RTP; raise it to find things sooner.")
        .defaultValue(4).min(1).max(64).sliderRange(1, 16).build());
    private final SettingGroup sgGeo = settings.createGroup("Geology");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Which methods run.
    private final Setting<Boolean> mGeology = sgMethods.add(new BoolSetting.Builder().name("method-geology").description("Isolated mineral lines + deepslate anomalies (mined bases/tunnels).").defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Boolean> mEntities = sgMethods.add(new BoolSetting.Builder().name("method-entities").description("Kept pets / farm mobs above Y16 (villager, iron golem, fox, cat).").defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Boolean> mGrowth = sgMethods.add(new BoolSetting.Builder().name("method-growth").description("Farm-scale kelp/vine columns.").defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Boolean> mVelocity = sgMethods.add(new BoolSetting.Builder().name("method-velocity").description("Flag chunks that emit nonzero entity-velocity packets.").defaultValue(false).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Boolean> mStorage = sgMethods.add(new BoolSetting.Builder().name("method-storage").description("Flag chunks packed with containers (stash / storage room).").defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Integer> storageThreshold = sgMethods.add(new IntSetting.Builder().name("storage-threshold").description("Containers in a chunk to flag it.").defaultValue(5).range(1, 50).sliderRange(1, 30).visible(() -> customThresholds.get() && mStorage.get()).build());
    private final Setting<Boolean> mSkylight = sgMethods.add(new BoolSetting.Builder().name("method-skylight").description("Roof detection (AnomalyColumnScanner method): flags surface columns that SHOULD see open sky but have blocked skylight above sea level = someone built a roof/overhang over them.").defaultValue(false).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Integer> skylightThreshold = sgMethods.add(new IntSetting.Builder().name("skylight-columns").description("Roofed columns in a chunk to flag it.").defaultValue(20).range(1, 200).sliderRange(4, 100).visible(mSkylight::get).build());
    private final Setting<Boolean> mUnnatural = sgMethods.add(new BoolSetting.Builder()
        .name("method-unnatural")
        .description("Blocks that do not generate underground: cobblestone, planks of any wood, torches, rails, ladders and crafting tables. None of it forms naturally down there, so a cluster of it is somebody's build. From the PlayerChunkFinder approach in the shared files.")
        .defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());
    private final Setting<Integer> unnaturalY = sgMethods.add(new IntSetting.Builder()
        .name("unnatural-below-y")
        .description("Only count those blocks below this height, where none of them belong.")
        .defaultValue(50).min(-64).max(320).sliderRange(-64, 64)
        .visible(() -> methodPreset.get() == MethodPreset.Custom).build());

    private final Setting<Boolean> mRedstone = sgMethods.add(new BoolSetting.Builder()
        .name("method-redstone")
        .description("Powered redstone: repeaters and comparators that are currently carrying a signal. Redstone only stays powered while something is driving it, so a live circuit underground is a base that is running right now rather than one somebody abandoned. Comes from the TuffChunkFinder approach in the shared files.")
        .defaultValue(true).visible(() -> methodPreset.get() == MethodPreset.Custom).build());

    private final Setting<Boolean> mScore = sgMethods.add(new BoolSetting.Builder().name("method-score").description("Weighted scoring (WizzyScanner / LarpDebug method): each signal in a chunk adds weighted points; flag if total >= min-score. Catches bases that are only mildly suspicious on any single signal but add up.").defaultValue(false).build());
    private final Setting<Integer> minScore = sgMethods.add(new IntSetting.Builder().name("min-score").description("Total weighted score to flag a chunk.").defaultValue(30).range(1, 300).sliderRange(5, 150).visible(() -> customThresholds.get() && mScore.get()).build());

    // Geology tuning (faithful defaults from the originals).
    private final Setting<Integer> lineLength = sgGeo.add(new IntSetting.Builder().name("line-length").description("Min vertical run to count as a line.").defaultValue(10).range(4, 30).sliderRange(5, 20).visible(mGeology::get).build());
    private final Setting<Integer> isolationRadius = sgGeo.add(new IntSetting.Builder().name("isolation-radius").description("A line is only counted if no same block sits within this radius (natural rock is clustered).").defaultValue(8).range(2, 20).sliderRange(3, 16).visible(mGeology::get).build());
    // Per-type deepslate detection (their V1: each type has its own toggle + threshold, each flags independently)
    private final Setting<Boolean> detectNormalDeepslate = sgGeo.add(new BoolSetting.Builder().name("detect-normal-deepslate").description("Look for ordinary deepslate placed where it shouldn't be. Deepslate only forms deep underground, so finding it up high usually means someone put it there.").defaultValue(true).visible(mGeology::get).build());
    private final Setting<Integer> deepslateThreshold = sgGeo.add(new IntSetting.Builder().name("deepslate-threshold").description("How many suspicious deepslate blocks (of the types ticked above) must be in a chunk before it's flagged. Lower = more sensitive, more false alarms.").defaultValue(2).range(1, 30).sliderRange(1, 20).visible(() -> customThresholds.get() && mGeology.get()).build());
    private final Setting<Boolean> detectCobbledDeepslate = sgGeo.add(new BoolSetting.Builder().name("detect-cobbled-deepslate").description("Look for cobbled deepslate (the cracked kind you get from mining it). It doesn't occur naturally, so a pile of it means player mining/building.").defaultValue(true).visible(mGeology::get).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgGeo.add(new BoolSetting.Builder().name("detect-rotated-deepslate").description("Look for deepslate turned on its side. Natural deepslate always stands upright, so sideways ones were placed by a player.").defaultValue(true).visible(mGeology::get).build());
    private final Setting<Integer> normalDeepslateMinY = sgGeo.add(new IntSetting.Builder().name("deepslate-min-y").description("Normal deepslate at/above this Y is suspicious (it's naturally below 0).").defaultValue(16).range(-64, 320).sliderRange(0, 64).visible(mGeology::get).build());
    private final Setting<Boolean> ignoreExposed = sgGeo.add(new BoolSetting.Builder().name("ignore-exposed").description("Ignore blocks touching air/fluid (natural exposure).").defaultValue(true).visible(mGeology::get).build());
    private final Setting<Integer> trialChamberSkip = sgGeo.add(new IntSetting.Builder().name("trial-chamber-skip").description("Skip a chunk with at least this many trial-chamber blocks.").defaultValue(40).range(0, 200).sliderRange(10, 100).visible(mGeology::get).build());

    private final Setting<Integer> growthLength = sgMethods.add(new IntSetting.Builder().name("min-column-height").description("Kelp/vine column height that counts as a farm .").defaultValue(25).range(4, 60).sliderRange(6, 40).visible(() -> customThresholds.get() && mGrowth.get()).build());
    private final Setting<Double> entityRange = sgMethods.add(new DoubleSetting.Builder().name("entity-range").description("Max distance to react to a suspicious entity/velocity.").defaultValue(256).min(16).sliderRange(64, 512).build());

    private final Setting<Integer> chunkRange = sgRender.add(new IntSetting.Builder().name("range").description("How far out to look for and show flagged chunks, measured in chunks around you. 0 = only the chunk you stand in, 8 = a 17x17 chunk area. Bigger = see more but heavier.").defaultValue(8).min(0).max(32).sliderRange(0, 16).build());
    private final Setting<Boolean> ignoreHoley = sgRender.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder().name("ignore-holey").description("Don't highlight a flagged chunk if it has an exposed vertical hole (cuts natural cave false positives).").defaultValue(false).build());
    private final Setting<Boolean> evidenceWindow = sgRender.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder().name("require-min-evidence").description("Also require a chunk to hold at least 'sensitivity' evidence blocks (vines, kelp, or placed deepslate) before showing it. Filters out chunks that tripped on one weak signal.").defaultValue(false).build());
    private final Setting<Boolean> loadedTicks = sgRender.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder().name("loaded-ticks").description("Also flag chunks that stay loaded near you for a long time (persistent = base).").defaultValue(false).build());
    private final Setting<Integer> loadedTicksRequired = sgRender.add(new IntSetting.Builder().name("loaded-ticks-required").description("Ticks a chunk must stay loaded to flag.").defaultValue(600).min(20).max(6000).sliderRange(100, 2400).visible(loadedTicks::get).build());
    private final Setting<Double> renderY = sgRender.add(new DoubleSetting.Builder().name("render-y").description("Y level to draw the chunk box.").defaultValue(64).min(-64).max(320).sliderRange(-64, 320).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder().name("tracers").description("Draw a line from you to each flagged chunk so you can see the direction at a glance.").defaultValue(false).build());
    private final Setting<Boolean> chatAlert = sgRender.add(new BoolSetting.Builder().name("chat").description("Print a message in chat when a new suspicious chunk is found.").defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How the boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("fill-color").description("The colour of the filled/shaded part of the box.").defaultValue(new SettingColor(0, 255, 255, 90)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("The colour of the box outline.").defaultValue(new SettingColor(0, 255, 255, 255)).build());
    private final Setting<Boolean> highlightBlocks = sgRender.add(new BoolSetting.Builder().name("highlight-blocks").description("Instead of only a chunk box, colour each individual anomaly block (deepslate/cobbled/rotated) inside a flagged chunk.").defaultValue(false).build());
    private final Setting<SettingColor> highlightColor = sgRender.add(new ColorSetting.Builder().name("highlight-color").description("The colour used when highlighting the individual suspicious blocks inside a chunk.").defaultValue(new SettingColor(255, 255, 0, 160)).visible(highlightBlocks::get).build());

    // Mineral types scanned for lines.
    private static final Block[] LINE_BLOCKS = {Blocks.COBBLESTONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DIORITE, Blocks.OBSIDIAN, Blocks.GRANITE, Blocks.CALCITE, Blocks.STONE};
    private static final Set<Block> TRIAL_CHAMBER = Set.of(
        Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF, Blocks.CHISELED_TUFF_BRICKS, Blocks.POLISHED_TUFF,
        Blocks.TUFF_BRICK_SLAB, Blocks.TUFF_BRICK_STAIRS, Blocks.TUFF_BRICK_WALL, Blocks.TRIAL_SPAWNER, Blocks.VAULT);

    private final Map<Long, BlockPos> flagged = new ConcurrentHashMap<>();
    private final Map<Long, java.util.List<BlockPos>> highlights = new ConcurrentHashMap<>();
    private final Set<Integer> seenEntities = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> alerts = new ConcurrentLinkedQueue<>();
    // Background scan pool so heavy chunk analysis never blocks the render thread (matches the original's ExecutorService design).
    private final Set<Long> scanned = ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService scanner;
    private static final int MAX_PENDING = 512;
    private record Queued(WorldChunk chunk, long arrived) {}
    private final java.util.ArrayDeque<Queued> pending = new java.util.ArrayDeque<>();

    /** Fast preset = packet-driven methods only; the block scans are the expensive part. */
    private boolean useMethod(Setting<Boolean> tickbox, boolean cheap) {
        return switch (methodPreset.get()) {
            case All, Thorough -> true;
            case Fast -> cheap;
            case Custom -> tickbox.get();
        };
    }

    public ChunkFinder() {
        super(shama.addon.ShamaAddon.HUNT, "chunk-finder++", "Base/stash chunk finder with selectable detection methods (geology, entities, growth, velocity).");
    }

    private final java.util.Map<Long,Integer> loaded = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onActivate() {
        flagged.clear(); seenEntities.clear(); alerts.clear(); highlights.clear(); scanned.clear();
        scanner = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "shama-chunkfinder"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    }

    @Override
    public void onDeactivate() {
        announced.clear();
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        synchronized (pending) { pending.clear(); }
        flagged.clear(); seenEntities.clear(); alerts.clear(); highlights.clear(); scanned.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        long key = zoneKey(chunk.getPos().x, chunk.getPos().z);
        if (!scanned.add(key)) return;                 // each chunk analyzed once, never re-scanned
        // Queue it rather than submitting immediately. Flying fast or RTPing delivers hundreds of
        // chunks at once; dumping them all on the pool at once starves the CPU and the whole game
        // hitches even though the work is off-thread.
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) pending.pollFirst();   // drop the oldest, keep up with movement
            pending.addLast(new Queued(chunk, System.currentTimeMillis()));
        }
    }

    /** Feed a few queued chunks to the scan pool each tick so load spikes are spread out. */
    @EventHandler
    private void onScanPump(TickEvent.Post event) {
        java.util.concurrent.ExecutorService s = scanner;
        if (s == null || s.isShutdown()) return;
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
    }

    /** Full per-chunk analysis — runs on a background thread. */
    private void analyze(WorldChunk chunk) {
        // blocks that never generate underground
        if (useMethod(mUnnatural, false)) {
            int made = 0;
            int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
            BlockPos.Mutable um = new BlockPos.Mutable();
            int topY = Math.min(unnaturalY.get(), chunk.getTopYInclusive());
            outerUnnatural:
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = chunk.getBottomY(); y <= topY; y++) {
                    var st = chunk.getBlockState(um.set(bx + x, y, bz + z));
                    if (st.isAir()) continue;
                    String p = shama.addon.util.BlockPaths.of(st.getBlock());
                    boolean built = p.equals("cobblestone") || p.endsWith("_planks")
                        || p.equals("torch") || p.equals("wall_torch") || p.equals("soul_torch")
                        || p.endsWith("_rail") || p.equals("rail") || p.equals("ladder")
                        || p.equals("crafting_table") || p.equals("furnace") || p.equals("chest")
                        || p.equals("barrel") || p.equals("oak_fence") || p.equals("glass");
                    if (!built) continue;
                    if (++made >= hitsNeeded(storageThreshold)) {
                        flag(new BlockPos(bx + 8, (int) (double) renderY.get(), bz + 8));
                        break outerUnnatural;
                    }
                }
        }

        // powered redstone — a circuit under load means somebody's base is live
        if (useMethod(mRedstone, false)) {
            int powered = 0;
            int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
            BlockPos.Mutable rm = new BlockPos.Mutable();
            outerRedstone:
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = chunk.getBottomY(); y <= chunk.getTopYInclusive(); y++) {
                    var st = chunk.getBlockState(rm.set(bx + x, y, bz + z));
                    if (st.isAir()) continue;
                    String p = shama.addon.util.BlockPaths.of(st.getBlock());
                    if (!p.equals("repeater") && !p.equals("comparator")) continue;
                    // the powered flag lives in the state text, which needs no property API
                    if (!st.toString().contains("powered=true")) continue;
                    if (++powered >= hitsNeeded(storageThreshold)) {
                        flag(new BlockPos(bx + 8, (int) (double) renderY.get(), bz + 8));
                        break outerRedstone;
                    }
                }
        }

        if (useMethod(mStorage, true)) {
            int containers = 0;
            for (BlockEntity be : chunk.getBlockEntities().values()) if (be instanceof Inventory) containers++;
            if (containers >= hitsNeeded(storageThreshold)) flag(new BlockPos(chunk.getPos().getStartX() + 8, (int) (double) renderY.get(), chunk.getPos().getStartZ() + 8));
        }
        if (useMethod(mScore, true)) {
            int score = 0;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof Inventory) score += 3;               // container weight
                else score += 5;                                       // spawner/other BE weight
            }
            java.util.Map<net.minecraft.util.math.BlockPos, net.minecraft.block.entity.BlockEntity> bes = chunk.getBlockEntities();
            if (!bes.isEmpty()) {
                int cx0s = chunk.getPos().getStartX(), cz0s = chunk.getPos().getStartZ();
                BlockPos.Mutable ms = new BlockPos.Mutable();
                for (int x = 0; x < 16 && score < scaled(minScore, 30); x += 2)
                    for (int z = 0; z < 16 && score < scaled(minScore, 30); z += 2)
                        for (int y = chunk.getBottomY(); y <= chunk.getTopYInclusive(); y += 4) {
                            String pth = shama.addon.util.BlockPaths.of(chunk.getBlockState(ms.set(cx0s + x, y, cz0s + z)).getBlock());
                            if (pth.equals("vine") || pth.startsWith("kelp") || pth.contains("amethyst")) score += 1;
                        }
            }
            if (score >= scaled(minScore, 30)) flag(new BlockPos(chunk.getPos().getStartX() + 8, (int) (double) renderY.get(), chunk.getPos().getStartZ() + 8));
        }
        if (useMethod(mSkylight, true) && mc.world != null) {
            int cx0 = chunk.getPos().getStartX(), cz0 = chunk.getPos().getStartZ();
            int roofed = 0;
            BlockPos.Mutable mm = new BlockPos.Mutable();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int surfaceY = chunk.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE).get(x, z);
                if (surfaceY <= 62) continue; // only above sea level
                if (mc.world.getLightLevel(LightType.SKY, mm.set(cx0 + x, surfaceY + 1, cz0 + z)) < 15) roofed++;
            }
            if (roofed >= skylightThreshold.get()) flag(new BlockPos(cx0 + 8, (int) (double) renderY.get(), cz0 + 8));
        }
        // Scan off the render path.
        scan(chunk);
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        tickLoaded();
        if (mc.world == null || mc.player == null) return;
        if (useMethod(mEntities, true) || useMethod(mVelocity, true)) {
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player) continue;
                if (mc.player.distanceTo(e) > entityRange.get()) continue;
                boolean hit = false;
                if (useMethod(mEntities, true) && e.getBlockPos().getY() > 16
                    && (e instanceof VillagerEntity || e instanceof IronGolemEntity || e instanceof FoxEntity || e instanceof CatEntity || e instanceof GuardianEntity))
                    hit = true;
                // velocity method: an entity the server is actively moving (nonzero velocity) = a loaded/active chunk
                if (!hit && useMethod(mVelocity, true) && e.getVelocity().lengthSquared() > 0.02) hit = true;
                if (hit && seenEntities.add(e.getId())) flag(e.getBlockPos());
            }
        }
        if (mc.player != null) {   // prune flagged data outside the chunk range
            int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get() + 2;
            // Keep found chunks well past the draw range, otherwise walking a few blocks prunes them
            // and they re-appear on the next scan — that's the flicker. Only `scanned` is pruned at the
            // wider margin so chunks genuinely get re-checked once you're far away.
            int keep = r + KEEP_MARGIN;
            flagged.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep || Math.abs(new ChunkPos(k).z - pcz) > keep);
            highlights.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep || Math.abs(new ChunkPos(k).z - pcz) > keep);
            scanned.removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep || Math.abs(new ChunkPos(k).z - pcz) > keep);
        }
        String line; int n = 0;
        while ((line = alerts.poll()) != null && n++ < 20) shama.addon.util.Chat.info(line);
    }

    private void scan(WorldChunk chunk) {
        if (!useMethod(mGeology, false) && !useMethod(mGrowth, false)) return;
        int minY = chunk.getBottomY();
        int maxY = chunk.getTopYInclusive();
        int cx = chunk.getPos().getStartX(), cz = chunk.getPos().getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        var surface = chunk.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE);

        boolean geo = useMethod(mGeology, false), grow = useMethod(mGrowth, false);
        int trialThresh = geo ? trialChamberSkip.get() : 0;

        int trialCount = 0, normalCount = 0, cobbledCount = 0, rotatedCount = 0, lines = 0;
        boolean growthHit = false;
        BlockPos growthWhere = null;

        // ONE pass: trial-chamber count + deepslate + mineral lines + growth columns, each column
        // capped at the surface heightmap so the all-air region above ground is never touched.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int colTop = Math.min(maxY, surface.get(x, z) + 1);
                int run = 0; Block runBlock = null;       // mineral line run
                int gRun = 0; Block gBlock = null;         // growth column run
                for (int y = minY; y <= colTop; y++) {
                    BlockState bs = chunk.getBlockState(m.set(cx + x, y, cz + z));
                    Block b = bs.getBlock();

                    if (trialThresh > 0 && TRIAL_CHAMBER.contains(b)) trialCount++;

                    if (geo) {
                        if (b == Blocks.DEEPSLATE) {
                            boolean rotated = bs.contains(Properties.AXIS) && bs.get(Properties.AXIS) != Direction.Axis.Y;
                            if (rotated) { rotatedCount++; if (highlightBlocks.get()) collectHighlight(chunk, cx + x, y, cz + z); }
                            else if (y >= normalDeepslateMinY.get()) { normalCount++; if (highlightBlocks.get()) collectHighlight(chunk, cx + x, y, cz + z); }
                        } else if (b == Blocks.COBBLED_DEEPSLATE) { cobbledCount++; if (highlightBlocks.get()) collectHighlight(chunk, cx + x, y, cz + z); }

                        boolean lineBlock = isLineBlock(b);
                        if (lineBlock && b == runBlock) run++;
                        else {
                            if (runBlock != null && run >= lineLength.get() && isolatedLine(chunk, cx + x, cz + z, y - run, y - 1, runBlock, minY, maxY)) lines++;
                            runBlock = lineBlock ? b : null;
                            run = lineBlock ? 1 : 0;
                        }
                    }
                    if (grow && !growthHit) {
                        boolean g = (b == Blocks.KELP || b == Blocks.KELP_PLANT || b == Blocks.VINE);
                        if (g && b == gBlock) { if (++gRun >= scaled(growthLength, 25)) { growthHit = true; growthWhere = new BlockPos(cx + x, y, cz + z); } }
                        else { gBlock = g ? b : null; gRun = g ? 1 : 0; }
                    }
                }
                // close a line run that reached the column top
                if (geo && runBlock != null && run >= lineLength.get() && isolatedLine(chunk, cx + x, cz + z, colTop - run + 1, colTop, runBlock, minY, maxY)) lines++;
            }
        }

        if (trialThresh > 0 && trialCount >= trialThresh) return; // trial chamber -> skip

        boolean geoHit = geo && (
            (detectNormalDeepslate.get() && normalCount >= hitsNeeded(deepslateThreshold))
            || (detectCobbledDeepslate.get() && cobbledCount >= hitsNeeded(deepslateThreshold))
            || (detectRotatedDeepslate.get() && rotatedCount >= hitsNeeded(deepslateThreshold))
            || lines >= 1);

        if (geoHit) flag(new BlockPos(cx + 8, (int) (double) renderY.get(), cz + 8));
        else if (growthHit) flag(growthWhere);
    }

    private void collectHighlight(WorldChunk chunk, int x, int y, int z) {
        java.util.List<BlockPos> list = highlights.computeIfAbsent(zoneKey(chunk.getPos().x, chunk.getPos().z), k -> new java.util.ArrayList<>());
        if (list.size() < 512) list.add(new BlockPos(x, y, z));
    }

    private boolean isLineBlock(Block b) {
        for (Block lb : LINE_BLOCKS) if (b == lb) return true;
        return false;
    }

    /** A run is a "player line" only if no same block sits within isolationRadius horizontally. */
    private boolean isolatedLine(WorldChunk chunk, int x, int z, int y1, int y2, Block b, int minY, int maxY) {
        if (ignoreExposed.get() && exposed(chunk, x, y1, z) && exposed(chunk, x, y2, z)) return false;
        int r = isolationRadius.get();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int y = Math.max(minY, y1 - 2); y <= Math.min(maxY, y2 + 2); y++) {
                if (chunk.getBlockState(m.set(x + dx, y, z + dz)).getBlock() == b) return false;
            }
        }
        return true;
    }

    private boolean exposed(WorldChunk chunk, int x, int y, int z) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (Direction d : Direction.values()) {
            BlockState n = chunk.getBlockState(m.set(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()));
            if (n.isAir() || !n.getFluidState().isEmpty()) return true;
        }
        return false;
    }


    /** Flag a chunk, merging with any existing flag within 2 chunks. */
    private void flag(BlockPos pos) {
        // your own mining and building trips the same signals, so skip anything right next to you
        if (mc.player != null && ignoreNearMe.get() > 0) {
            int d = ignoreNearMe.get();
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            if (Math.abs((pos.getX() >> 4) - pcx) <= d && Math.abs((pos.getZ() >> 4) - pcz) <= d) return;
        }
        // Don't wipe everything at the cap (that made every highlight vanish at once) — drop only the
        // scan cache, which rebuilds harmlessly.
        if (scanned.size() > 20000) scanned.clear();
        if (announced.size() > 50000) announced.clear();   // hard ceiling so it can't grow forever
        ChunkPos cp = new ChunkPos(pos);
        long key = cp.toLong();
        if (flagged.containsKey(key)) return;
        for (Long existing : flagged.keySet()) {
            ChunkPos e = new ChunkPos(existing);
            if (Math.abs(e.x - cp.x) <= 2 && Math.abs(e.z - cp.z) <= 2) return;
        }
        flagged.put(key, pos.toImmutable());
        // `flagged` gets pruned as you move away, so on its own it would re-announce a chunk every
        // time you wander back. `announced` is never pruned, so each chunk is only ever reported once.
        if (chatAlert.get() && announced.add(key))
            alerts.add(String.format("[ChunkFinder] suspicious chunk at X:%d Z:%d", cp.x * 16 + 8, cp.z * 16 + 8));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || flagged.isEmpty()) return;
        double y = renderY.get();
        Color fill = sideColor.get(), line = lineColor.get();
        for (Long key : flagged.keySet()) {
            ChunkPos cp = new ChunkPos(key);
            double x0 = cp.x * 16, z0 = cp.z * 16;
            if (Math.abs(cp.x - mc.player.getChunkPos().x) > chunkRange.get() || Math.abs(cp.z - mc.player.getChunkPos().z) > chunkRange.get()) continue;
            if (ignoreHoley.get() && mc.world != null && chunkHasHole(cp)) continue;
            if (evidenceWindow.get() && mc.world != null && countEvidence(cp) < minEvidence.get()) continue;
            event.renderer.box(x0, y, z0, x0 + 16, y + 0.3, z0 + 16, fill, line, shapeMode.get(), 0);
            if (tracers.get())
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x0 + 8, y + 0.15, z0 + 8, line);

            if (highlightBlocks.get()) {
                java.util.List<BlockPos> hl = highlights.get(key);
                if (hl != null) {
                    Color hc = highlightColor.get();
                    Color hl2 = new Color(hc.r, hc.g, hc.b, 255);
                    for (BlockPos hp : hl)
                        event.renderer.box(hp.getX(), hp.getY(), hp.getZ(), hp.getX() + 1, hp.getY() + 1, hp.getZ() + 1, hc, hl2, shapeMode.get(), 0);
                }
            }
        }
    }

    private void tickLoaded() {
        if (!loadedTicks.get() || mc.world == null || mc.player == null) return;
        ChunkPos c = mc.player.getChunkPos();
        int r = chunkRange.get();
        for (int cx = c.x - r; cx <= c.x + r; cx++) for (int cz = c.z - r; cz <= c.z + r; cz++) {
            if (mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false) instanceof net.minecraft.world.chunk.WorldChunk) {
                long k = ChunkPos.toLong(cx, cz);
                int t = loaded.merge(k, 1, Integer::sum);
                if (t == loadedTicksRequired.get()) { ChunkPos fp = new ChunkPos(k); flagged.put(k, new BlockPos(fp.getStartX() + 8, 64, fp.getStartZ() + 8)); }
            }
        }
        loaded.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - c.x) > r || Math.abs(new ChunkPos(k).z - c.z) > r);
    }

    private int countEvidence(ChunkPos cp) {
        // their ChunkFinderV2/V3 evidence blocks scanned in the underground band -50..16
        int bx = cp.getStartX(), bz = cp.getStartZ(), n = 0;
        net.minecraft.util.math.BlockPos.Mutable m = new net.minecraft.util.math.BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 16; y >= -50; y--) {
            var b = mc.world.getBlockState(m.set(bx + x, y, bz + z)).getBlock();
            // their exact ChunkFinderV2/V3 evidence set: vine, kelp, kelp_plant, deepslate
            if (b == net.minecraft.block.Blocks.VINE || b == net.minecraft.block.Blocks.KELP
                || b == net.minecraft.block.Blocks.KELP_PLANT || b == net.minecraft.block.Blocks.DEEPSLATE) n++;
        }
        return n;
    }

    private boolean chunkHasHole(ChunkPos cp) {
        // quick vertical-hole probe: a tall air column (>6) open to the surface near the chunk center
        int x = cp.getStartX() + 8, z = cp.getStartZ() + 8;
        int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
        int air = 0;
        net.minecraft.util.math.BlockPos.Mutable m = new net.minecraft.util.math.BlockPos.Mutable();
        for (int y = top; y > top - 24 && y > mc.world.getBottomY(); y--) {
            if (mc.world.getBlockState(m.set(x, y, z)).isAir()) { if (++air > 6) return true; } else air = 0;
        }
        return false;
    }

    @Override
    public String getInfoString() { return String.valueOf(flagged.size()); }
}
