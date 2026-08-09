package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Signal Scanner++ — flags chunks packed with redstone/automation blocks (hoppers, comparators, observers, pistons, droppers, dispensers) = contraptions/farms. */
public class SignalScanner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder().name("threshold").description("Redstone components per chunk to flag.").defaultValue(8).range(1, 60).sliderRange(2, 40).build());
    private final Setting<Boolean> hopperEsp = sg.add(new BoolSetting.Builder().name("hopper-esp").description("Also box individual hoppers/droppers/dispensers (merged from HopperESP).").defaultValue(false).build());
    private final Setting<Boolean> lightAnomalies = sg.add(new BoolSetting.Builder().name("light-anomalies").description("Also add score for light-emitting blocks below Y0 (hidden underground lighting = base).").defaultValue(true).build());
    private final Setting<Double> boxY = sgRender.add(new DoubleSetting.Builder().name("box-y").description("The Y height to draw the box/marker at.").defaultValue(70).min(-64).max(320).sliderRange(-64, 320).build());
    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder().name("render-distance").description("How far away (in blocks) things are still drawn.").defaultValue(512).min(32).sliderRange(64, 1024).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(255, 0, 0, 230)).build());

    private final SettingGroup sgConnected = settings.createGroup("Connected Scan");
    private final Setting<Boolean> connectedScan = sgConnected.add(new BoolSetting.Builder().name("connected-scan").description("Live-scan the blocks below a Y line for clusters of connected redstone/rails/hoppers (contraptions you haven\'t rendered the chunk-data for yet).").defaultValue(false).build());
    private final Setting<Integer> connScanRadius = sgConnected.add(new IntSetting.Builder().name("conn-scan-radius").description("Chunk radius for the live connected-scan.").defaultValue(6).min(1).max(16).visible(connectedScan::get).build());
    private final Setting<Integer> connMaxY = sgConnected.add(new IntSetting.Builder().name("conn-max-y").description("Only scan blocks at or below this Y.").defaultValue(0).min(-64).max(64).visible(connectedScan::get).build());
    private final Setting<Integer> connMin = sgConnected.add(new IntSetting.Builder().name("conn-min-connected").description("Connected blocks a chunk needs to flag.").defaultValue(2).min(1).max(64).visible(connectedScan::get).build());
    private final Setting<Boolean> connRails = sgConnected.add(new BoolSetting.Builder().name("conn-rails").description("Count rails.").defaultValue(true).visible(connectedScan::get).build());
    private final Setting<Boolean> connRedstone = sgConnected.add(new BoolSetting.Builder().name("conn-redstone").description("Count redstone components (wire/repeaters/pistons…).").defaultValue(true).visible(connectedScan::get).build());
    private final Setting<Boolean> connHoppers = sgConnected.add(new BoolSetting.Builder().name("conn-hoppers").description("Count hoppers.").defaultValue(true).visible(connectedScan::get).build());
    private final Setting<SettingColor> connColor = sgConnected.add(new ColorSetting.Builder().name("connected-scan-color").description("Colour for connected-scan chunks.").defaultValue(new SettingColor(0, 200, 255, 200)).visible(connectedScan::get).build());

    private final Map<Long, Integer> scores = new ConcurrentHashMap<>();
    private final java.util.Set<net.minecraft.util.math.BlockPos> hoppers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> connectedChunks = ConcurrentHashMap.newKeySet();
    private int connTick;

    public SignalScanner() { super(shama.addon.ShamaAddon.HUNT, "signal-scanner++", "Hunts for the redstone and wiring signatures that give away hidden bases and farms."); }

    @Override public void onActivate() { scores.clear(); hoppers.clear(); connectedChunks.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        int n = 0;
        net.minecraft.world.chunk.ChunkSection[] secs = chunk.getSectionArray();
        for (int si = 0; si < secs.length; si++) {
            ChunkSection s = secs[si];
            if (s == null || s.isEmpty()) continue;
            int baseY = chunk.getBottomY() + (si << 4);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var st = s.getBlockState(x, y, z);
                String p = shama.addon.util.BlockPaths.of(st.getBlock());
                if (p.equals("hopper") || p.equals("comparator") || p.equals("repeater") || p.equals("observer")
                    || p.equals("dropper") || p.equals("dispenser") || p.endsWith("piston") || p.equals("redstone_wire")
                    || p.equals("redstone_torch") || p.equals("target")) n++;
                else if (lightAnomalies.get() && baseY + y < 0 && st.getLuminance() > 0) n++; // underground light source
            }
        }
        if (hopperEsp.get())
            for (java.util.Map.Entry<net.minecraft.util.math.BlockPos, net.minecraft.block.entity.BlockEntity> e : chunk.getBlockEntities().entrySet())
                if (e.getValue() instanceof net.minecraft.block.entity.HopperBlockEntity || e.getValue() instanceof net.minecraft.block.entity.DispenserBlockEntity)
                    hoppers.add(e.getKey().toImmutable());
        if (n > 0) scores.put(chunk.getPos().toLong(), n); else scores.remove(chunk.getPos().toLong());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || scores.isEmpty()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3d eye = mc.player.getEyePos();
        double y = boxY.get();
        Color line = lineColor.get(), fill = new Color(line.r, line.g, line.b, 45);
        for (Map.Entry<Long, Integer> e : scores.entrySet()) {
            if (e.getValue() < threshold.get()) continue;
            ChunkPos cp = new ChunkPos(e.getKey());
            double x0 = cp.x * 16, z0 = cp.z * 16;
            double dx = x0 + 8 - eye.x, dz = z0 + 8 - eye.z;
            if (dx * dx + dz * dz > maxSq) continue;
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, fill, line, ShapeMode.Both, 0);
        }
        if (hopperEsp.get()) {
            Color hf = new Color(180, 180, 180, 40), hl = new Color(180, 180, 180, 220);
            for (net.minecraft.util.math.BlockPos hp : hoppers) {
                double dx = hp.getX() + 0.5 - eye.x, dz = hp.getZ() + 0.5 - eye.z;
                if (dx * dx + dz * dz > maxSq) continue;
                event.renderer.box(hp.getX(), hp.getY(), hp.getZ(), hp.getX() + 1, hp.getY() + 1, hp.getZ() + 1, hf, hl, ShapeMode.Both, 0);
            }
        }
    }

    private boolean isConnTarget(net.minecraft.block.Block b) {
        String p = shama.addon.util.BlockPaths.of(b);
        if (connHoppers.get() && p.equals("hopper")) return true;
        if (connRails.get() && p.endsWith("rail")) return true;
        if (connRedstone.get() && (p.equals("redstone_wire") || p.equals("repeater") || p.equals("comparator")
            || p.equals("redstone_torch") || p.equals("redstone_wall_torch") || p.equals("observer")
            || p.endsWith("piston") || p.equals("dropper") || p.equals("dispenser"))) return true;
        return false;
    }

    @EventHandler
    private void onConnTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (!connectedScan.get() || mc.world == null || mc.player == null) return;
        if (++connTick % 20 != 0) return;
        ChunkPos center = mc.player.getChunkPos(); int r = connScanRadius.get();
        net.minecraft.util.math.BlockPos.Mutable m = new net.minecraft.util.math.BlockPos.Mutable();
        for (int cx = center.x - r; cx <= center.x + r; cx++) for (int cz = center.z - r; cz <= center.z + r; cz++) {
            var ch = mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (!(ch instanceof WorldChunk wc)) continue;
            int bx = wc.getPos().getStartX(), bz = wc.getPos().getStartZ(), found = 0;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = wc.getBottomY(); y <= connMaxY.get(); y++) {
                var st = wc.getBlockState(m.set(bx + x, y, bz + z));
                if (!st.isAir() && isConnTarget(st.getBlock())) found++;
            }
            long k = ChunkPos.toLong(cx, cz);
            if (found >= connMin.get()) connectedChunks.add(k); else connectedChunks.remove(k);
        }
    }

    @EventHandler
    private void onConnRender(Render3DEvent event) {
        if (!connectedScan.get() || connectedChunks.isEmpty() || mc.world == null) return;
        var c = connColor.get(); var side = new Color(c.r, c.g, c.b, 40);
        double y = boxY.get();
        for (long k : connectedChunks) {
            ChunkPos cp = new ChunkPos(k); double x0 = cp.getStartX(), z0 = cp.getStartZ();
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, side, c, ShapeMode.Both, 0);
        }
    }

    @Override public String getInfoString() { return scores.size() + " chunks"; }
}
