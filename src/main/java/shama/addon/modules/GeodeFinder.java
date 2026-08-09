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
 * Geode Finder — marks amethyst so you can tell a farmed geode from an untouched one at a glance.
 *
 * Crystals mode colours each cluster by how far it has grown, which is what tells you whether
 * somebody is harvesting: a geode nobody touches fills up with fully-grown clusters, while a farmed
 * one is mostly bare budding blocks and small buds because the grown ones keep getting broken.
 *
 * Geode mode instead boxes the whole structure once it looks suspicious, for when you just want to
 * find the thing rather than read it.
 */
public class GeodeFinder extends Module {
    public enum Mode {
        /** Colour each crystal by growth stage. */
        Crystals,
        /** Box the whole geode when it trips the suspicion rules. */
        Geode,
        /** Do both at once. */
        Both
    }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> chunkDelay = sg.add(new DoubleSetting.Builder()
        .name("chunk-delay")
        .description("Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.")
        .defaultValue(0.0).min(0).max(10).sliderRange(0, 3).decimalPlaces(1).build());


    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Crystals colours every cluster by how grown it is, so you can read whether a geode is being harvested. Geode boxes the whole structure once it looks suspicious. Both does each at the same time.")
        .defaultValue(Mode.Crystals).build());

    private final Setting<Integer> chunkRange = sg.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("How far out to keep showing geodes, in chunks.")
        .defaultValue(8).min(1).max(32).sliderRange(2, 16).build());

    private final Setting<Integer> scanRate = sg.add(new IntSetting.Builder()
        .name("scan-rate")
        .description("How many newly loaded chunks to check each tick. Lower this if the game stutters while flying.")
        .defaultValue(4).min(1).max(64).sliderRange(1, 16).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat")
        .description("Print a message the first time each geode is found.")
        .defaultValue(false).build());

    // ---------------------------------------------------------------- crystals
    private final SettingGroup sgCrystals = settings.createGroup("Crystals");

    private final Setting<Boolean> showBudding = sgCrystals.add(new BoolSetting.Builder()
        .name("budding")
        .description("Show budding amethyst — the block the crystals grow out of. It can't be mined, so a geode stripped down to bare budding blocks is one somebody farms.")
        .defaultValue(true).build());
    private final Setting<Boolean> showBlocks = sgCrystals.add(new BoolSetting.Builder()
        .name("amethyst-blocks")
        .description("Show plain amethyst blocks too. Geodes are full of them, so this gets noisy.")
        .defaultValue(false).build());

    private final Setting<SettingColor> buddingColor = sgCrystals.add(new ColorSetting.Builder()
        .name("budding-color").description("Colour for budding amethyst.")
        .defaultValue(new SettingColor(255, 90, 220, 220)).visible(showBudding::get).build());
    private final Setting<SettingColor> smallColor = sgCrystals.add(new ColorSetting.Builder()
        .name("small-color").description("Colour for a small bud — just started growing.")
        .defaultValue(new SettingColor(120, 80, 200, 200)).build());
    private final Setting<SettingColor> mediumColor = sgCrystals.add(new ColorSetting.Builder()
        .name("medium-color").description("Colour for a medium bud.")
        .defaultValue(new SettingColor(160, 90, 230, 210)).build());
    private final Setting<SettingColor> largeColor = sgCrystals.add(new ColorSetting.Builder()
        .name("large-color").description("Colour for a large bud — nearly grown.")
        .defaultValue(new SettingColor(200, 110, 245, 220)).build());
    private final Setting<SettingColor> clusterColor = sgCrystals.add(new ColorSetting.Builder()
        .name("grown-color").description("Colour for a fully-grown cluster, the one worth breaking.")
        .defaultValue(new SettingColor(240, 150, 255, 235)).build());
    private final Setting<SettingColor> blockColor = sgCrystals.add(new ColorSetting.Builder()
        .name("block-color").description("Colour for plain amethyst blocks.")
        .defaultValue(new SettingColor(150, 110, 190, 120)).visible(showBlocks::get).build());

    // ---------------------------------------------------------------- geode
    private final Setting<Boolean> cleanClusters = sgCrystals.add(new BoolSetting.Builder()
        .name("clean-clusters")
        .description("Mark clusters that still have growing buds on them in their own colour. A cluster nobody has touched keeps its part-grown shards; one that gets harvested is snapped off the moment it matures, so leftover partial growth means that spot is being left alone.")
        .defaultValue(false).build());
    private final Setting<SettingColor> cleanColor = sgCrystals.add(new ColorSetting.Builder()
        .name("clean-color").description("Colour for those untouched clusters.")
        .defaultValue(new SettingColor(80, 255, 180, 230)).visible(cleanClusters::get).build());

    private final Setting<Boolean> strippedGeodes = sgCrystals.add(new BoolSetting.Builder()
        .name("stripped-geodes")
        .description("Mark geodes with no budding amethyst left at all. Budding blocks cannot be mined with anything ordinary, so a geode without them has been deliberately cleared out — that is somebody working it, not natural.")
        .defaultValue(false).build());
    private final Setting<SettingColor> strippedColor = sgCrystals.add(new ColorSetting.Builder()
        .name("stripped-color").description("Colour for a geode that has been stripped of its budding blocks.")
        .defaultValue(new SettingColor(180, 0, 0, 200)).visible(strippedGeodes::get).build());

    private final Setting<Boolean> lookBypass = sgCrystals.add(new BoolSetting.Builder()
        .name("anti-growth-esp-bypass")
        .description("Keep showing amethyst the server has stopped sending. Some servers only send it while you are level with it or looking down at it, so it vanishes the moment you rise above. This remembers what was there and keeps drawing it rather than letting it blink out.")
        .defaultValue(true).build());
    private final Setting<Boolean> forgetOnUpdate = sgCrystals.add(new BoolSetting.Builder()
        .name("forget-on-update")
        .description("Only drop a crystal or a dripstone tip when the server actually says it changed, instead of after a timer. The server has to send a block update when somebody breaks something — it cannot hide that and still keep your world correct — so a position that has had no update is still there, however long ago you saw it. Covers amethyst and dripstone both, and sends nothing, so there is no risk in it.")
        .defaultValue(true).build());

    private final Setting<Integer> rememberSeconds = sgCrystals.add(new IntSetting.Builder()
        .name("remember-for")
        .description("How long to keep showing a find after the server stops sending it, in seconds. Only used when forget-on-update is off — with that on, a find is kept until the server says it changed, which needs no timer at all.")
        .defaultValue(120).min(5).max(1800).sliderRange(30, 600).visible(() -> lookBypass.get() && !forgetOnUpdate.get()).build());


    private final SettingGroup sgDrip = settings.createGroup("Dripstone");
    private final Setting<Boolean> dripstone = sgDrip.add(new BoolSetting.Builder()
        .name("dripstone")
        .description("Also mark pointed dripstone. Servers hide it the same way they hide amethyst, and a farmed cave gets its tips broken off constantly, so what is left tells you the same story. Uses the bypass above, so it stays visible once seen.")
        .defaultValue(false).build());
    private final Setting<Boolean> dripGrown = sgDrip.add(new BoolSetting.Builder()
        .name("only-overgrown")
        .description("Only mark dripstone that has grown longer than it naturally would. A stalactite only lengthens while its chunk stays loaded, so a long one means somebody has been holding that ground open — the same tell as overgrown kelp or sugar cane. Natural caves are full of short dripstone, so leave this on.")
        .defaultValue(true).visible(dripstone::get).build());
    private final Setting<Integer> dripMinLength = sgDrip.add(new IntSetting.Builder()
        .name("min-length")
        .description("How many blocks long a stalactite or stalagmite must be to count. Natural growth rarely passes four without somebody keeping the chunk loaded.")
        .defaultValue(5).min(2).max(20).sliderRange(3, 12).visible(dripGrown::get).build());
    private final Setting<SettingColor> dripColor = sgDrip.add(new ColorSetting.Builder()
        .name("dripstone-color").description("Colour used for dripstone.")
        .defaultValue(new SettingColor(200, 160, 120, 200)).visible(dripstone::get).build());

    private final SettingGroup sgGeode = settings.createGroup("Geode");
    private final Setting<Boolean> pillar = sgGeode.add(new BoolSetting.Builder()
        .name("pillar")
        .description("Shoot a beam up from each geode so you can spot one from across the map instead of only when you are on top of it. Taken from the shared AmethystESP.")
        .defaultValue(false).build());
    private final Setting<SettingColor> pillarColor = sgGeode.add(new ColorSetting.Builder()
        .name("pillar-color").description("Colour of that beam.")
        .defaultValue(new SettingColor(180, 100, 255, 90)).visible(pillar::get).build());

    private final Setting<Boolean> toastAlert = sgGeode.add(new BoolSetting.Builder()
        .name("toast")
        .description("Raise a popup in the corner when a geode is found, with the cluster count and an amethyst icon. Quieter than a title across the middle of the screen.")
        .defaultValue(false).build());

    private final Setting<Boolean> useFloodFill = sgGeode.add(new BoolSetting.Builder()
        .name("group-whole-geodes")
        .description("Group connected amethyst into whole geodes rather than counting loose blocks in a chunk. A geode is one connected lump, so this reports it as one find and gets its size right even when it straddles a chunk edge.")
        .defaultValue(true).build());
    private final Setting<Integer> geodeThreshold = sgGeode.add(new IntSetting.Builder()
        .name("geode-threshold")
        .description("How many connected amethyst blocks make a geode worth reporting.")
        .defaultValue(12).min(1).max(100).sliderRange(4, 40)
        .visible(useFloodFill::get).build());
    private final Setting<Integer> scanMinY = sgGeode.add(new IntSetting.Builder()
        .name("scan-min-y").description("Lowest height to scan for amethyst.")
        .defaultValue(-58).min(-64).max(320).sliderRange(-64, 64).build());
    private final Setting<Integer> scanMaxY = sgGeode.add(new IntSetting.Builder()
        .name("scan-max-y").description("Highest height to scan for amethyst.")
        .defaultValue(30).min(-64).max(320).sliderRange(-64, 128).build());

    private final Setting<Integer> minAmethyst = sgGeode.add(new IntSetting.Builder()
        .name("min-amethyst")
        .description("How much amethyst a chunk needs before it counts as a geode at all.")
        .defaultValue(12).min(2).max(200).sliderRange(4, 60).build());

    private final Setting<Boolean> onlySuspicious = sgGeode.add(new BoolSetting.Builder()
        .name("only-suspicious")
        .description("Only box geodes that look farmed rather than every one you pass. A natural geode is thick with grown clusters; a harvested one is mostly bare budding blocks, because the grown ones keep getting taken.")
        .defaultValue(true).build());

    private final Setting<Integer> grownPercent = sgGeode.add(new IntSetting.Builder()
        .name("max-grown-percent")
        .description("Below this share of fully-grown clusters, the geode is treated as harvested. Natural ones sit high; a farmed one is stripped down.")
        .defaultValue(25).min(0).max(100).sliderRange(5, 60)
        .visible(onlySuspicious::get).build());

    private final Setting<Boolean> requireDeep = sgGeode.add(new BoolSetting.Builder()
        .name("underground-only")
        .description("Ignore geodes near the surface. Those get found by accident; a deep one somebody has been working is the interesting case.")
        .defaultValue(false).build());
    private final Setting<Integer> deepY = sgGeode.add(new IntSetting.Builder()
        .name("below-y").description("What counts as underground.")
        .defaultValue(20).min(-64).max(320).sliderRange(-64, 64)
        .visible(requireDeep::get).build());

    private final Setting<Boolean> tracers = sgGeode.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from you to each geode. From the shared AmethystESP, and useful when a geode is behind terrain and the beam alone is hard to place.")
        .defaultValue(false).build());
    private final Setting<SettingColor> tracerColor = sgGeode.add(new ColorSetting.Builder()
        .name("tracer-color").description("Colour of those lines.")
        .defaultValue(new SettingColor(180, 100, 255, 160)).visible(tracers::get).build());

    private final Setting<SettingColor> geodeColor = sgGeode.add(new ColorSetting.Builder()
        .name("geode-color").description("Colour of the box drawn around a whole geode.")
        .defaultValue(new SettingColor(255, 60, 255, 90)).build());

    /** One amethyst block: position plus which stage it is. */
    private record Crystal(int x, int y, int z, int stage) {}

    private static final int BUDDING = 0, SMALL = 1, MEDIUM = 2, LARGE = 3, GROWN = 4, PLAIN = 5, DRIP = 6;

    private final Map<Long, List<Crystal>> byChunk = new ConcurrentHashMap<>();
    private final Map<Long, int[]> bounds = new ConcurrentHashMap<>();   // {minX,minY,minZ,maxX,maxY,maxZ,suspicious}
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<Long, Integer> geodeSizes = new ConcurrentHashMap<>();   // chunk -> biggest connected geode
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> toastQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private record Queued(WorldChunk chunk, long arrived) {}
    private final ArrayDeque<Queued> pending = new ArrayDeque<>();
    private java.util.concurrent.ExecutorService scanner;

    public GeodeFinder() {
        super(shama.addon.ShamaAddon.HUNT, "geode-finder++",
            "Marks amethyst so you can tell a farmed geode from an untouched one — colour each crystal by how grown it is, or box the whole geode.");
    }

    @Override
    public void onActivate() {
        byChunk.clear(); bounds.clear(); announced.clear(); lastSeen.clear(); geodeSizes.clear(); toastQueue.clear();
        synchronized (pending) { pending.clear(); }
        scanner = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "shama-geode");
            t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        synchronized (pending) { pending.clear(); }
        byChunk.clear(); bounds.clear(); announced.clear(); lastSeen.clear(); geodeSizes.clear(); toastQueue.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        synchronized (pending) {
            if (pending.size() >= 512) pending.pollFirst();
            pending.addLast(new Queued(chunk, System.currentTimeMillis()));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // popups have to be raised on the main thread, so the scanner queues them
        int[] t = toastQueue.poll();
        if (t != null) shama.addon.util.AmethystToast.show(t[0], t[1]);
        var s = scanner;
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

        if (lookBypass.get() && !forgetOnUpdate.get()) {
            long cutoff = System.currentTimeMillis() - rememberSeconds.get() * 1000L;
            lastSeen.entrySet().removeIf(e -> {
                if (e.getValue() > cutoff) return false;
                byChunk.remove(e.getKey()); bounds.remove(e.getKey());
                return true;
            });
        }
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int keep = chunkRange.get() + 8;
        byChunk.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep || Math.abs(new ChunkPos(k).z - pcz) > keep);
        bounds.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > keep || Math.abs(new ChunkPos(k).z - pcz) > keep);
    }

    /** Which growth stage this block is, or -1 when it isn't amethyst at all. */
    /** How many dripstone blocks run vertically through this one, counting both ways. */
    private int dripRun(WorldChunk chunk, int x, int y, int z, int bottom, int top) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        int n = 1;
        for (int d = 1; y - d >= bottom; d++) {
            if (stageOf(shama.addon.util.BlockPaths.of(chunk.getBlockState(m.set(x, y - d, z)).getBlock())) != DRIP) break;
            n++;
        }
        for (int d = 1; y + d <= top; d++) {
            if (stageOf(shama.addon.util.BlockPaths.of(chunk.getBlockState(m.set(x, y + d, z)).getBlock())) != DRIP) break;
            n++;
        }
        return n;
    }

    private int stageOf(String path) {
        return switch (path) {
            case "budding_amethyst" -> BUDDING;
            case "small_amethyst_bud" -> SMALL;
            case "medium_amethyst_bud" -> MEDIUM;
            case "large_amethyst_bud" -> LARGE;
            case "amethyst_cluster" -> GROWN;
            case "amethyst_block" -> PLAIN;
            case "pointed_dripstone" -> DRIP;
            default -> -1;
        };
    }

    private void analyze(WorldChunk chunk) {
        List<Crystal> found = new ArrayList<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY(), top = chunk.getTopYInclusive();
        BlockPos.Mutable m = new BlockPos.Mutable();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int grown = 0, growable = 0, buddingCount = 0, partial = 0;

        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = bottom; y <= top; y++) {
            var st = chunk.getBlockState(m.set(bx + x, y, bz + z));
            if (st.isAir()) continue;
            int stage = stageOf(shama.addon.util.BlockPaths.of(st.getBlock()));
            if (stage < 0) continue;
            if (stage == PLAIN && !showBlocks.get()) {
                // still counts toward the geode's extent, just not drawn
                minX = Math.min(minX, bx + x); maxX = Math.max(maxX, bx + x);
                minY = Math.min(minY, y);      maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, bz + z); maxZ = Math.max(maxZ, bz + z);
                continue;
            }
            if (stage == BUDDING && !showBudding.get()) continue;
            if (stage == DRIP) {
                if (!dripstone.get()) continue;
                // walk the column: length is what tells you the chunk has been held open
                if (dripGrown.get() && dripRun(chunk, bx + x, y, bz + z, bottom, top) < dripMinLength.get()) continue;
            }
            found.add(new Crystal(bx + x, y, bz + z, stage));
            minX = Math.min(minX, bx + x); maxX = Math.max(maxX, bx + x);
            minY = Math.min(minY, y);      maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, bz + z); maxZ = Math.max(maxZ, bz + z);
            if (stage == GROWN) grown++;
            if (stage == BUDDING) buddingCount++;
            if (stage == SMALL || stage == MEDIUM || stage == LARGE) partial++;   // still growing
            if (stage != PLAIN && stage != DRIP) growable++;
        }

        // whole-geode grouping from the supplied AmethystESP scanner
        if (useFloodFill.get() && mc.world != null) {
            try {
                var geodes = shama.addon.util.AmethystScan.findGeodes(
                    mc.world, chunk, geodeThreshold.get(), scanMinY.get(), scanMaxY.get());
                if (!geodes.isEmpty()) {
                    int biggest = 0;
                    for (var g : geodes) biggest = Math.max(biggest, g.size());
                    geodeSizes.put(chunk.getPos().toLong(), biggest);
                    // queued rather than raised here, since this runs off the main thread
                    if (toastAlert.get() && toastQueue.size() < 16) toastQueue.add(new int[]{biggest, geodeThreshold.get()});
                } else geodeSizes.remove(chunk.getPos().toLong());
            } catch (Throwable ignored) {}
        }

        long key = chunk.getPos().toLong();
        if (found.isEmpty() || growable < minAmethyst.get()) {
            // An empty rescan usually means the server stopped sending it, not that it is gone.
            if (!lookBypass.get()) { byChunk.remove(key); bounds.remove(key); }
            else lastSeen.putIfAbsent(key, System.currentTimeMillis());
            return;
        }
        lastSeen.remove(key);                       // being sent again, so it is current
        if (requireDeep.get() && minY > deepY.get()) { byChunk.remove(key); bounds.remove(key); return; }

        // A geode nobody touches keeps its grown clusters. A farmed one is stripped back to buds.
        int pct = growable == 0 ? 100 : (grown * 100) / growable;
        boolean suspicious = pct <= grownPercent.get();

        byChunk.put(key, found);
        // no budding blocks left means somebody cleared them out; partial growth means it is untouched
        int stripped = buddingCount == 0 ? 1 : 0;
        int untouched = partial > 0 ? 1 : 0;
        bounds.put(key, new int[]{minX, minY, minZ, maxX, maxY, maxZ, suspicious ? 1 : 0, stripped, untouched});

        if (chat.get() && announced.add(key)) {
            ChunkPos cp = chunk.getPos();
            shama.addon.util.Chat.info("[GeodeFinder] geode at %d, %d, %d — %d%% grown%s",
                cp.getStartX() + 8, minY, cp.getStartZ() + 8, pct, suspicious ? " (looks farmed)" : "");
        }
    }

    private Color colourFor(int stage) {
        SettingColor c = switch (stage) {
            case BUDDING -> buddingColor.get();
            case SMALL -> smallColor.get();
            case MEDIUM -> mediumColor.get();
            case LARGE -> largeColor.get();
            case GROWN -> clusterColor.get();
            case DRIP -> dripColor.get();
            default -> blockColor.get();
        };
        return new Color(c.r, c.g, c.b, c.a);
    }

    /**
     * Drop a remembered position only when the server says that block changed.
     *
     * This is the model from the supplied AmethystESP: found positions live in the map and are
     * removed by an explicit update, never by a rescan coming back empty. That is what makes it a
     * real bypass rather than a guess — hiding a block from chunk data is easy, but the server still
     * has to send a block update when somebody breaks it, or your world would go wrong. No update
     * means it is still there.
     */
    @EventHandler
    private void onBlockUpdate(meteordevelopment.meteorclient.events.packets.PacketEvent.Receive event) {
        if (!forgetOnUpdate.get() || mc.world == null) return;
        if (!(event.packet instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket p)) return;

        BlockPos pos = p.getPos().toImmutable();
        long ck = new ChunkPos(pos).toLong();
        List<Crystal> list = byChunk.get(ck);
        if (list == null) return;

        boolean still = shama.addon.util.AmethystScan.isAmethystLike(p.getState())
            || stageOf(shama.addon.util.BlockPaths.of(p.getState().getBlock())) == DRIP;
        if (still) return;                                   // still amethyst or dripstone, keep it

        // gone for real — this is the only thing that removes a find
        list.removeIf(c -> c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ());
        if (list.isEmpty()) { byChunk.remove(ck); bounds.remove(ck); }
        lastSeen.remove(ck);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || byChunk.isEmpty()) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get();
        Mode md = mode.get();

        if (md == Mode.Crystals || md == Mode.Both) {
            for (Map.Entry<Long, List<Crystal>> e : byChunk.entrySet()) {
                ChunkPos cp = new ChunkPos(e.getKey());
                if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
                int[] b = bounds.get(e.getKey());
                boolean clean = cleanClusters.get() && b != null && b.length > 8 && b[8] == 1;
                for (Crystal c : e.getValue()) {
                    Color line = colourFor(c.stage());
                    if (clean && (c.stage() == SMALL || c.stage() == MEDIUM || c.stage() == LARGE)) {
                        var cc = cleanColor.get();
                        line = new Color(cc.r, cc.g, cc.b, cc.a);
                    }
                    Color fill = new Color(line.r, line.g, line.b, Math.min(120, line.a / 3));
                    event.renderer.box(c.x(), c.y(), c.z(), c.x() + 1, c.y() + 1, c.z() + 1,
                        fill, line, ShapeMode.Both, 0);
                }
            }
        }

        if (md == Mode.Geode || md == Mode.Both) {
            SettingColor gc = geodeColor.get();
            Color fill = new Color(gc.r, gc.g, gc.b, gc.a);
            Color line = new Color(gc.r, gc.g, gc.b, Math.min(255, gc.a + 140));
            if (pillar.get()) {
                SettingColor pc = pillarColor.get();
                Color pf = new Color(pc.r, pc.g, pc.b, pc.a);
                Color pl = new Color(pc.r, pc.g, pc.b, Math.min(255, pc.a + 120));
                for (Map.Entry<Long, int[]> pe : bounds.entrySet()) {
                    ChunkPos cp2 = new ChunkPos(pe.getKey());
                    if (Math.abs(cp2.x - pcx) > r || Math.abs(cp2.z - pcz) > r) continue;
                    int[] b2 = pe.getValue();
                    double mx = (b2[0] + b2[3]) / 2.0, mz = (b2[2] + b2[5]) / 2.0;
                    event.renderer.box(mx - 0.4, b2[1], mz - 0.4, mx + 0.4, b2[1] + 320, mz + 0.4,
                        pf, pl, ShapeMode.Both, 0);
                }
            }
            if (tracers.get()) {
                var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;
                SettingColor tc = tracerColor.get();
                Color tl = new Color(tc.r, tc.g, tc.b, tc.a);
                for (Map.Entry<Long, int[]> te : bounds.entrySet()) {
                    ChunkPos cp3 = new ChunkPos(te.getKey());
                    if (Math.abs(cp3.x - pcx) > r || Math.abs(cp3.z - pcz) > r) continue;
                    int[] b3 = te.getValue();
                    event.renderer.line(cam.x, cam.y, cam.z,
                        (b3[0] + b3[3]) / 2.0, (b3[1] + b3[4]) / 2.0, (b3[2] + b3[5]) / 2.0, tl);
                }
            }
            for (Map.Entry<Long, int[]> e : bounds.entrySet()) {
                ChunkPos cp = new ChunkPos(e.getKey());
                if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
                int[] b = e.getValue();
                boolean isStripped = strippedGeodes.get() && b.length > 7 && b[7] == 1;
                if (onlySuspicious.get() && b[6] == 0 && !isStripped) continue;
                Color gf = fill, gl = line;
                if (isStripped) {
                    var sc = strippedColor.get();
                    gf = new Color(sc.r, sc.g, sc.b, Math.min(120, sc.a / 2));
                    gl = new Color(sc.r, sc.g, sc.b, sc.a);
                }
                event.renderer.box(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, gf, gl, ShapeMode.Both, 0);
            }
        }
    }

    @Override
    public String getInfoString() {
        if (bounds.isEmpty()) return null;
        int biggest = 0;
        for (int v : geodeSizes.values()) biggest = Math.max(biggest, v);
        return biggest > 0 ? bounds.size() + " (largest " + biggest + ")" : Integer.toString(bounds.size());
    }
}
