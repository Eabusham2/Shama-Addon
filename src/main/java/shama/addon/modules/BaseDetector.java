package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base Detector++ — flags chunks emitting lots of particles. Active farms, redstone,
 * brewing, campfires and portals all spew particle packets the server sends you even
 * when far off; a chunk that keeps producing them is almost always a working base.
 * Ported from their BaseDetector (particle-info per chunk). Counts decay over time.
 */
public class BaseDetector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> weighted = sg.add(new BoolSetting.Builder().name("score-by-type").description("Score particles by how base-like they are (witch 4, flame/enchant 3, smoke/portal/campfire 2, redstone/note 1) instead of counting each one as 1.").defaultValue(true).build());
    private final Setting<Boolean> blockScoring = sg.add(new BoolSetting.Builder().name("block-scoring").description("Also score chunks by player-placed blocks (workstations/storage/farming/lighting), on top of particle activity.").defaultValue(false).build());
    private final Setting<Integer> blockScoreEvery = sg.add(new IntSetting.Builder().name("block-scan-interval").description("Ticks between block-scoring passes.").defaultValue(40).min(10).max(200).sliderRange(20,120).visible(blockScoring::get).build());
    private int blockTick;
    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder().name("threshold").description("Particle packets in the window to flag a chunk.").defaultValue(25).range(3, 200).sliderRange(5, 100).build());
    private final Setting<Integer> windowTicks = sg.add(new IntSetting.Builder().name("window-ticks").description("Length of the counting window, in ticks.").defaultValue(60).range(20, 400).sliderRange(20, 200).build());
    private final Setting<Double> boxY = sg.add(new DoubleSetting.Builder().name("box-y").description("The Y height to draw the box/marker at.").defaultValue(64).min(-64).max(320).sliderRange(-64, 320).build());
    private final Setting<SettingColor> lineColor = sg.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(255, 90, 255, 220)).build());

    private final Map<Long, Integer> counts = new ConcurrentHashMap<>();
    private int tick;

    public BaseDetector() { super(shama.addon.ShamaAddon.HUNT, "base-detector++", "Flags chunks emitting heavy particle activity (working bases)."); }

    @Override public void onActivate() { counts.clear(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof ParticleS2CPacket p)) return;
        long key = new ChunkPos((int) Math.floor(p.getX()) >> 4, (int) Math.floor(p.getZ()) >> 4).toLong();
        int weight = 1;
        if (weighted.get()) {
            String type = net.minecraft.registry.Registries.PARTICLE_TYPE.getId(p.getParameters().getType()).getPath();
            // their exact INTERESTING_PARTICLES weights
            if (type.contains("witch")) weight = 4;
            else if (type.contains("flame") || type.contains("enchant") || type.contains("spit")) weight = 3;
            else if (type.contains("smoke") || type.contains("portal") || type.contains("happy_villager") || type.contains("heart") || type.contains("campfire") || type.contains("squid_ink") || type.contains("fishing")) weight = 2;
            else if (type.contains("composter") || type.contains("redstone") || type.contains("note")) weight = 1;
        }
        counts.merge(key, weight, Integer::sum);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (blockScoring.get() && mc.world != null && mc.player != null && ++blockTick % blockScoreEvery.get() == 0) blockScorePass();
        if (++tick % Math.max(1, windowTicks.get()) == 0) counts.replaceAll((k, v) -> v / 2); // decay
        counts.entrySet().removeIf(e -> e.getValue() <= 0);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (counts.isEmpty()) return;
        Color l = lineColor.get(), f = new Color(l.r, l.g, l.b, 40);
        double y = boxY.get();
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() < threshold.get()) continue;
            ChunkPos cp = new ChunkPos(e.getKey());
            double x0 = cp.x * 16, z0 = cp.z * 16;
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, f, l, ShapeMode.Both, 0);
        }
    }

    private void blockScorePass() {
        int r = mc.options.getViewDistance().getValue();
        var center = mc.player.getChunkPos();
        for (int cx = center.x - r; cx <= center.x + r; cx++) for (int cz = center.z - r; cz <= center.z + r; cz++) {
            var ch = mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (!(ch instanceof net.minecraft.world.chunk.WorldChunk wc)) continue;
            int score = 0;
            for (var sec : wc.getSectionArray()) {
                if (sec == null || sec.isEmpty()) continue;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    var st = sec.getBlockState(x, y, z); if (st.isAir()) continue;
                    String pth = shama.addon.util.BlockPaths.of(st.getBlock());
                    if (pth.equals("crafting_table") || pth.endsWith("furnace") || pth.equals("smoker") || pth.equals("anvil") || pth.equals("enchanting_table") || pth.equals("brewing_stand") || pth.equals("smithing_table") || pth.equals("grindstone") || pth.equals("loom") || pth.equals("cartography_table") || pth.equals("stonecutter")) score += 5;
                    else if (pth.equals("chest") || pth.equals("trapped_chest") || pth.equals("barrel") || pth.endsWith("shulker_box") || pth.equals("hopper") || pth.equals("ender_chest") || pth.equals("dispenser") || pth.equals("dropper")) score += 4;
                    else if (pth.equals("farmland") || pth.equals("composter") || pth.equals("beehive") || pth.equals("bee_nest") || pth.equals("hay_block")) score += 3;
                    else if (pth.equals("torch") || pth.equals("wall_torch") || pth.equals("lantern") || pth.equals("glowstone") || pth.equals("sea_lantern") || pth.equals("shroomlight")) score += 2;
                }
            }
            if (score > 0) counts.merge(net.minecraft.util.math.ChunkPos.toLong(cx, cz), score, Integer::sum);
        }
    }

    @Override public String getInfoString() {
        int n = 0; for (int v : counts.values()) if (v >= threshold.get()) n++;
        return n + " bases";
    }
}
