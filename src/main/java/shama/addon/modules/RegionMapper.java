package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region Mapper++ — merged from their region-map finders. Groups loaded chunks by
 * Minecraft region (32x32 chunks = 512 blocks) and boxes any region whose loaded-chunk
 * count crosses a threshold. On anarchy servers, a region that stays loaded (spawn
 * chunks, chunk loaders, active bases) lights up versus empty wilderness.
 */
public class RegionMapper extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Integer> minChunks = sg.add(new IntSetting.Builder().name("min-loaded-chunks").description("Loaded chunks in a region to flag it.").defaultValue(16).range(1, 1024).sliderRange(4, 256).build());
    private final Setting<Double> boxY = sgRender.add(new DoubleSetting.Builder().name("box-y").description("The Y height to draw the box/marker at.").defaultValue(64).min(-64).max(320).sliderRange(-64, 320).build());
    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder().name("render-distance").description("How far away (in blocks) things are still drawn.").defaultValue(2048).min(64).sliderRange(256, 4096).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(0, 255, 0, 200)).build());

    private final Map<Long, java.util.Set<Long>> regions = new ConcurrentHashMap<>();

    public RegionMapper() { super(shama.addon.ShamaAddon.HUNT, "loaded-region-finder++", "Finds 512x512 regions that have an unusual number of chunks loaded — chunk loaders, big bases and other places the server is working hard."); }

    @Override public void onActivate() { regions.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        int cx = event.chunk().getPos().x, cz = event.chunk().getPos().z;
        long region = (((long) (cx >> 5)) << 32) | ((cz >> 5) & 0xffffffffL);
        long chunkKey = (((long) cx) << 32) | (cz & 0xffffffffL);
        regions.computeIfAbsent(region, k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || regions.isEmpty()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3d eye = mc.player.getEyePos();
        double y = boxY.get();
        Color line = lineColor.get(), fill = new Color(line.r, line.g, line.b, 30);
        for (Map.Entry<Long, java.util.Set<Long>> e : regions.entrySet()) {
            if (e.getValue().size() < minChunks.get()) continue;
            int rx = (int) (e.getKey() >> 32);
            int rz = (int) (e.getKey() & 0xffffffffL);
            double x0 = rx * 512.0, z0 = rz * 512.0;
            double cxp = x0 + 256, czp = z0 + 256;
            double dx = cxp - eye.x, dz = czp - eye.z;
            if (dx * dx + dz * dz > maxSq) continue;
            event.renderer.box(x0, y, z0, x0 + 512, y + 1, z0 + 512, fill, line, ShapeMode.Both, 0);
        }
    }

    @Override public String getInfoString() { return regions.size() + " regions"; }
}
