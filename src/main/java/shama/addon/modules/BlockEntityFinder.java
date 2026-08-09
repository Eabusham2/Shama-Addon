package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Block Entity Debug++ — stash/base finder that reads block entities straight from
 * raw server packets, so it surfaces containers the client never renders.
 *
 * Two raw sources (best of both):
 *   - the chunk packet: every block entity the server ships with a chunk (chests,
 *     barrels, shulkers, hoppers, furnaces, spawners...). Render-independent, so
 *     hidden underground storage lights up just by loading chunks over an area.
 *   - BlockEntityUpdateS2CPacket: the live raw update stream (signs, spawners, etc.),
 *     which carries its own NBT.
 *
 * Dedupe keeps one marker per position (added once, like Krypton). Chat alert prints
 * each new find, optionally with its NBT. Boxes + tracers draw them.
 *
 * Limit: a container's CONTENTS aren't in these packets (the server only sends those
 * when you open it), so NBT is the observable data — custom name, sign text, etc.
 */
public class BlockEntityFinder extends Module {
    // Storage/loot types, for the container filter on the packet path (no world access,
    // so it's safe off the main thread).
    private static final Set<BlockEntityType<?>> CONTAINERS = Set.of(
        BlockEntityType.CHEST, BlockEntityType.TRAPPED_CHEST, BlockEntityType.BARREL,
        BlockEntityType.SHULKER_BOX, BlockEntityType.ENDER_CHEST, BlockEntityType.HOPPER,
        BlockEntityType.DISPENSER, BlockEntityType.DROPPER, BlockEntityType.FURNACE,
        BlockEntityType.BLAST_FURNACE, BlockEntityType.SMOKER, BlockEntityType.BREWING_STAND,
        BlockEntityType.BEACON, BlockEntityType.CRAFTER
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> yThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("y-threshold").description("Only keep block entities at or below this Y.")
        .defaultValue(40).min(-64).max(320).sliderRange(-64, 120).build());

    private final Setting<Boolean> containersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("containers-only").description("Only storage (chests, barrels, shulkers, hoppers, furnaces...). Off = every block entity.")
        .defaultValue(true).build());

    private final Setting<Boolean> deduplicate = sgGeneral.add(new BoolSetting.Builder()
        .name("deduplicate").description("Keep one marker per position; don't re-add when a chunk reloads.")
        .defaultValue(true).build());

    private final Setting<Boolean> liveUpdates = sgGeneral.add(new BoolSetting.Builder()
        .name("live-updates").description("Also read the raw BlockEntityUpdate packet stream, not just chunk packets.")
        .defaultValue(true).build());

    private final Setting<Boolean> spoofDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-disguised-blocks")
        .description("Flag block entities the server packs down to bedrock (Y<=spoof-y) to HIDE their real position. On servers that scrub stash coords, these low-Y ghosts are the tell that a stash exists nearby.")
        .defaultValue(false).build());

    private final Setting<Integer> spoofY = sgGeneral.add(new IntSetting.Builder()
        .name("spoof-y").description("A block entity at or below this Y is treated as a hidden/spoofed position.")
        .defaultValue(0).min(-64).max(64).sliderRange(-64, 8)
        .visible(spoofDetect::get).build());

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
        .name("chat").description("Print each new find to chat.").defaultValue(true).build());

    private final Setting<Boolean> alertNbt = sgGeneral.add(new BoolSetting.Builder()
        .name("alert-nbt").description("Include the block entity's NBT in the chat alert (observable data only).")
        .defaultValue(false).visible(chatAlert::get).build());

    private final Setting<Integer> maxStored = sgGeneral.add(new IntSetting.Builder()
        .name("max-stored").description("Cap on finds kept/rendered.")
        .defaultValue(2000).min(64).sliderRange(256, 10000).build());

    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder()
        .name("render-distance").description("Only draw finds within this many blocks.")
        .defaultValue(256).min(16).sliderRange(32, 512).build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Line from camera to each find.").defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Box fill/outline.").defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("Colour of the filled faces of each box.").defaultValue(new SettingColor(225, 0, 255, 40)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Box outline.").defaultValue(new SettingColor(225, 0, 255, 255)).build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color").description("Tracer line.").defaultValue(new SettingColor(225, 0, 255, 160)).build());

    private final Setting<Boolean> chunkBeam = sgRender.add(new BoolSetting.Builder()
        .name("mark-deep-chunks")
        .description("Draw a flat box at sea level over any chunk that holds a block entity below deep-y, once it's past box range. Lets you spot deep-stash chunks from high ground / across the map.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> deepY = sgRender.add(new IntSetting.Builder()
        .name("deep-y").description("A block entity below this Y makes its chunk get a box.")
        .defaultValue(5).min(-64).max(320).sliderRange(-64, 40)
        .visible(chunkBeam::get).build());

    private final Setting<Integer> seaLevel = sgRender.add(new IntSetting.Builder()
        .name("box-y").description("Y level to draw the chunk box at (sea level by default).")
        .defaultValue(63).min(-64).max(320).sliderRange(0, 128)
        .visible(chunkBeam::get).build());

    private final Setting<Double> beamDistance = sgRender.add(new DoubleSetting.Builder()
        .name("box-distance").description("Max horizontal distance to draw deep-chunk boxes.")
        .defaultValue(1024).min(64).sliderRange(128, 2048)
        .visible(chunkBeam::get).build());

    private final Setting<SettingColor> beamColor = sgRender.add(new ColorSetting.Builder()
        .name("box-color").description("Deep-chunk box color.")
        .defaultValue(new SettingColor(255, 60, 60, 220))
        .visible(chunkBeam::get).build());

    // pos -> type name. Concurrent: chunk/packet handlers may run off the render thread.
    // ===== merged flow-finder: dense-chunk stash detection =====
    private final SettingGroup sgHoppers = settings.createGroup("Hoppers");
    private final Setting<Boolean> hopperSignals = sgHoppers.add(new BoolSetting.Builder()
        .name("hopper-signals")
        .description("Read the comparator signal coming off nearby hoppers. A hopper that reports a changing signal is moving items, which gives away a sorting system or an active farm even when the storage behind it is walled in. Also available on its own as hopper-debug++; running both is harmless.")
        .defaultValue(false).build());
    private final Setting<Integer> hopperMin = sgHoppers.add(new IntSetting.Builder()
        .name("min-hoppers")
        .description("How many hoppers must sit together before it's worth reporting.")
        .defaultValue(4).min(1).max(64).sliderRange(2, 20).visible(hopperSignals::get).build());
    private final Setting<SettingColor> hopperColor = sgHoppers.add(new ColorSetting.Builder()
        .name("hopper-color").description("Colour used for hopper clusters.")
        .defaultValue(new SettingColor(255, 200, 60, 200)).visible(hopperSignals::get).build());

    private final SettingGroup sgHidden = settings.createGroup("Hidden Storage");
    private final Setting<Boolean> hiddenStorage = sgHidden.add(new BoolSetting.Builder()
        .name("hidden-storage")
        .description("Compare the containers the server tells you about against the ones your game is actually drawing. Anything the server sent but you can't see is buried, walled in, or behind a chunk that never rendered — which is exactly where stashes are.")
        .defaultValue(false).build());
    private final Setting<Integer> hiddenMin = sgHidden.add(new IntSetting.Builder()
        .name("min-hidden").description("How many unseen containers a chunk needs before it's reported.")
        .defaultValue(4).min(1).max(100).sliderRange(2, 30).visible(hiddenStorage::get).build());
    private final Setting<Boolean> hiddenPinpoint = sgHidden.add(new BoolSetting.Builder()
        .name("pinpoint").description("Box each unseen container individually instead of just marking the chunk, so you know exactly where to dig.")
        .defaultValue(true).visible(hiddenStorage::get).build());
    private final Setting<SettingColor> hiddenColor = sgHidden.add(new ColorSetting.Builder()
        .name("hidden-color").description("Colour used for unseen containers.").defaultValue(new SettingColor(255, 0, 200, 220)).visible(hiddenStorage::get).build());

    private final Map<BlockPos, String> hidden = new ConcurrentHashMap<>();

    private final SettingGroup sgDense = settings.createGroup("Dense Chunks");
    private final Setting<Boolean> denseChunks = sgDense.add(new BoolSetting.Builder()
        .name("stash-chunks").description("Also box whole chunks that are packed with block entities (a stash), not just the individual ones.").defaultValue(false).build());
    private final Setting<Integer> denseThreshold = sgDense.add(new IntSetting.Builder()
        .name("stash-threshold").description("How many block entities a chunk needs before it counts as a stash.").defaultValue(30).min(2).max(400).sliderRange(5, 120).visible(denseChunks::get).build());
    private final Setting<Double> denseMarkerY = sgDense.add(new DoubleSetting.Builder()
        .name("stash-marker-y").description("Y height to draw the dense-chunk marker at.").defaultValue(64).min(-64).max(320).sliderRange(-64, 200).visible(denseChunks::get).build());
    private final Setting<SettingColor> denseColor = sgDense.add(new ColorSetting.Builder()
        .name("stash-color").description("Colour of the dense-chunk marker.").defaultValue(new SettingColor(255, 0, 120, 200)).visible(denseChunks::get).build());



    // ===== merged flow-finder: depth guard (chat warning only, never touches the connection) =====
    private final SettingGroup sgGuard = settings.createGroup("Depth Guard");
    private final Setting<Boolean> depthGuard = sgGuard.add(new BoolSetting.Builder()
        .name("depth-guard").description("Warn in chat as you approach the anti-cheat's Y limit, and remind you to re-log once you cross it. Chat only — it never disconnects you.").defaultValue(false).build());
    private final Setting<Integer> dangerY = sgGuard.add(new IntSetting.Builder()
        .name("danger-y").description("Y level that triggers the re-log reminder.").defaultValue(-55).min(-64).max(64).visible(depthGuard::get).build());
    private final Setting<Integer> warnDistance = sgGuard.add(new IntSetting.Builder()
        .name("warn-distance").description("Blocks above danger-Y to start warning.").defaultValue(6).min(1).max(32).visible(depthGuard::get).build());

    private int hiddenTick;
    private final java.util.Set<Long> hiddenAnnounced = ConcurrentHashMap.newKeySet();
    private boolean warningSent, kickLocked;

    private final Map<BlockPos, String> found = new ConcurrentHashMap<>();
    // New-find chat lines, drained on the main thread in onTick (packet events fire off-thread).
    private final ConcurrentLinkedQueue<String> alerts = new ConcurrentLinkedQueue<>();
    // Chunks (packed long) holding a block entity below deep-y, for the beam.
    private final Set<Long> deepChunks = ConcurrentHashMap.newKeySet();

    public BlockEntityFinder() {
        super(shama.addon.ShamaAddon.HUNT, "block-entity-debug++", "Finds chests, hoppers, spawners and other containers below a set height — a stash finder.");
    }

    @Override
    public void onActivate() { found.clear(); alerts.clear(); deepChunks.clear(); }

    @Override
    public void onDeactivate() { found.clear(); alerts.clear(); deepChunks.clear(); }

    // Bulk: block entities carried by each chunk packet (main thread; full instance access).
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = e.getValue();
            boolean container = be instanceof Inventory;
            String name = typeName(be.getType());
            NbtCompound nbt = null;
            if (alertNbt.get() && chatAlert.get()) {
                try { nbt = be.createNbt(mc.world.getRegistryManager()); } catch (Throwable ignored) {}
            }
            record(e.getKey(), name, container, nbt);
        }
    }

    // Live: raw BlockEntityUpdate stream (may be off-thread — packet data only, no world access).
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!liveUpdates.get() || !(event.packet instanceof BlockEntityUpdateS2CPacket p)) return;
        BlockEntityType<?> type = p.getBlockEntityType();
        record(p.getPos(), typeName(type), CONTAINERS.contains(type), alertNbt.get() && chatAlert.get() ? p.getNbt() : null);
    }

    private void record(BlockPos pos, String name, boolean container, NbtCompound nbt) {
        if (spoofDetect.get() && pos.getY() <= spoofY.get()) {
            if (found.put(pos.toImmutable(), name + " (spoofed?)") == null && chatAlert.get())
                alerts.add(String.format("[BE-Debug] SPOOFED %s at (%d, %d, %d) - hidden stash nearby", name, pos.getX(), pos.getY(), pos.getZ()));
            deepChunks.add(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
            return;
        }
        if (pos.getY() > yThreshold.get()) return;
        if (containersOnly.get() && !container) return;

        // Chunk-level tracking for the beam (kept even for duplicates, so the chunk stays known).
        if (pos.getY() < deepY.get() && deepChunks.size() < maxStored.get()) {
            deepChunks.add(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
        }

        if (deduplicate.get() && found.containsKey(pos)) return;
        if (found.size() >= maxStored.get()) return;

        if (found.put(pos.toImmutable(), name) == null && chatAlert.get()) {
            String line = String.format("[BE-Debug] %s at (%d, %d, %d)", name, pos.getX(), pos.getY(), pos.getZ());
            if (nbt != null) {
                String s = nbt.toString();
                line += " " + (s.length() > 300 ? s.substring(0, 300) + "\u2026" : s);
            }
            alerts.add(line);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        String line;
        int n = 0;
        while ((line = alerts.poll()) != null && n++ < 20) shama.addon.util.Chat.info(line);
    }

    @EventHandler
    private void onDepthGuardTick(TickEvent.Post event) {
        if (!depthGuard.get() || mc.player == null) { warningSent = false; kickLocked = false; return; }
        double y = mc.player.getY();
        int limit = dangerY.get();
        if (y > limit + warnDistance.get()) { warningSent = false; kickLocked = false; }
        else if (y > limit) {
            if (!warningSent) { shama.addon.util.Chat.warning("[BlockEntityDebug] approaching Y=%d - current Y: %.1f", limit, y); warningSent = true; }
        } else if (!kickLocked) {
            kickLocked = true;
            shama.addon.util.Chat.warning("Y Entity Limit Packet Blocked. RE LOG FLY UP");
        }
    }

    /**
     * A container the server sent us but the client isn't rendering is hidden behind something.
     * The client only builds a block entity into the render list when its chunk section is built,
     * so a container that never appears in a rendered section is buried, walled in, or in a
     * section your client skipped.
     */
    @EventHandler
    private void onHiddenTick(TickEvent.Post event) {
        if (!hiddenStorage.get() || mc.world == null || mc.player == null) { hidden.clear(); return; }
        if ((hiddenTick++ % 40) != 0) return;
        hidden.clear();
        java.util.Map<Long, Integer> perChunk = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, String> e : found.entrySet()) {
            BlockPos p = e.getKey();
            int cx = p.getX() >> 4, cz = p.getZ() >> 4;
            if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
                // server told us about it, but the chunk isn't even loaded here
                hidden.put(p, e.getValue());
                perChunk.merge(ChunkPos.toLong(cx, cz), 1, Integer::sum);
                continue;
            }
            // loaded, but is the block actually there client-side? if the client shows air or a
            // different block, the container is one the server sent and we never rendered
            if (mc.world.getBlockState(p).isAir()) {
                hidden.put(p, e.getValue());
                perChunk.merge(ChunkPos.toLong(cx, cz), 1, Integer::sum);
            }
        }
        for (var en : perChunk.entrySet()) {
            if (en.getValue() < hiddenMin.get()) continue;
            if (!hiddenAnnounced.add(en.getKey())) continue;
            ChunkPos cp = new ChunkPos(en.getKey());
            if (chatAlert.get())
                shama.addon.util.Chat.warning("[BlockEntityDebug] %d unseen containers around chunk %d, %d",
                    en.getValue(), cp.x, cp.z);
        }
    }

    /** Hopper clusters pulled from the block entities this module already collected. */
    @EventHandler
    private void onRenderHoppers(Render3DEvent event) {
        if (!hopperSignals.get() || found.isEmpty()) return;
        java.util.Map<Long, java.util.List<BlockPos>> byChunk = new java.util.HashMap<>();
        for (Map.Entry<BlockPos, String> e : found.entrySet()) {
            if (e.getValue() == null || !e.getValue().toLowerCase().contains("hopper")) continue;
            BlockPos p = e.getKey();
            byChunk.computeIfAbsent(ChunkPos.toLong(p.getX() >> 4, p.getZ() >> 4), k -> new java.util.ArrayList<>()).add(p);
        }
        SettingColor hc = hopperColor.get();
        Color hl = new Color(hc.r, hc.g, hc.b, 255), hf = new Color(hc.r, hc.g, hc.b, 60);
        for (var en : byChunk.entrySet()) {
            if (en.getValue().size() < hopperMin.get()) continue;
            for (BlockPos p : en.getValue())
                event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    hf, hl, ShapeMode.Both, 0);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (hiddenStorage.get() && !hidden.isEmpty()) {
            SettingColor hc = hiddenColor.get();
            Color hl = new Color(hc.r, hc.g, hc.b, 255);
            Color hf = new Color(hc.r, hc.g, hc.b, 60);
            if (hiddenPinpoint.get()) {
                for (BlockPos p : hidden.keySet())
                    event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                        hf, hl, ShapeMode.Both, 0);
            } else {
                java.util.Set<Long> ks = new java.util.HashSet<>();
                for (BlockPos p : hidden.keySet()) ks.add(ChunkPos.toLong(p.getX() >> 4, p.getZ() >> 4));
                for (long k : ks) {
                    ChunkPos cp = new ChunkPos(k);
                    event.renderer.box(cp.getStartX(), mc.world.getBottomY(), cp.getStartZ(),
                        cp.getStartX() + 16, yThreshold.get(), cp.getStartZ() + 16, hf, hl, ShapeMode.Both, 0);
                }
            }
        }
        // merged flow-finder: dense-chunk stash boxes
        if (denseChunks.get() && !found.isEmpty()) {
            Map<Long, Integer> perChunk = new java.util.HashMap<>();
            for (BlockPos bp : found.keySet())
                perChunk.merge(ChunkPos.toLong(bp.getX() >> 4, bp.getZ() >> 4), 1, Integer::sum);
            SettingColor dc = denseColor.get();
            Color dside = new Color(dc.r, dc.g, dc.b, 40);
            for (var en : perChunk.entrySet()) {
                if (en.getValue() < denseThreshold.get()) continue;
                ChunkPos cp = new ChunkPos(en.getKey());
                double x0 = cp.getStartX(), z0 = cp.getStartZ(), yy = denseMarkerY.get();
                event.renderer.box(x0, yy, z0, x0 + 16, yy + 0.4, z0 + 16, dside, dc, ShapeMode.Both, 0);
            }
        }
        if (mc.player == null || found.isEmpty()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3d eye = mc.player.getEyePos();
        for (BlockPos pos : found.keySet()) {
            double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;
            event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (tracers.get()) {
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, tracerColor.get());
            }
        }

        // Deep-chunk boxes: a flat box at sea level over chunks that hold a block entity
        // below deep-y, shown once the chunk is past box range — spot deep stashes from afar.
        if (chunkBeam.get() && !deepChunks.isEmpty()) {
            double beamSq = beamDistance.get() * beamDistance.get();
            double boxHorizSq = renderDistance.get() * renderDistance.get();
            double y = seaLevel.get();
            SettingColor line = beamColor.get();
            Color fill = new Color(line.r, line.g, line.b, 35);
            for (long key : deepChunks) {
                ChunkPos cp = new ChunkPos(key);
                double x0 = cp.x * 16, z0 = cp.z * 16;
                double hdx = x0 + 8 - eye.x, hdz = z0 + 8 - eye.z;
                double horizSq = hdx * hdx + hdz * hdz;
                if (horizSq > beamSq || horizSq <= boxHorizSq) continue;
                event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, fill, line, ShapeMode.Both, 0);
            }
        }
    }

    private static String typeName(BlockEntityType<?> type) {
        var id = Registries.BLOCK_ENTITY_TYPE.getId(type);
        return id != null ? id.getPath() : "block_entity";
    }

    @Override
    public String getInfoString() { return String.valueOf(found.size()); }
}
