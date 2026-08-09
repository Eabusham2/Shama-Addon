package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Search Map — keeps track of the ground you have already been over, and points you at the ground
 * you have not.
 *
 * Hunting bases means covering enormous distances, and after an hour of flying there is no way to
 * tell which direction you have already swept. You end up re-flying ground you cleared and leaving
 * gaps you never touch. This marks every chunk as you load it, draws what you have covered, and
 * works out which way has the most unsearched ground left so you always know where to go next.
 */
public class SearchMap extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> pauseWhenStill = sg.add(new BoolSetting.Builder()
        .name("pause-when-still")
        .description("Stop recording while you are standing still, so sitting in one spot does not keep marking the same ground as freshly searched.")
        .defaultValue(false).build());

    private final Setting<Integer> forgetMinutes = sg.add(new IntSetting.Builder()
        .name("forget-after")
        .description("Treat ground as unsearched again after this many minutes. Bases get built, so old sweeps go stale — 0 means never forget.")
        .defaultValue(0).min(0).max(1440).sliderRange(0, 240).build());

    // ---------------------------------------------------------------- guidance
    private final SettingGroup sgGuide = settings.createGroup("Where To Go");

    private final Setting<Boolean> suggest = sgGuide.add(new BoolSetting.Builder()
        .name("suggest-direction")
        .description("Work out which compass direction has the most unsearched ground within range and say so. This is the part that stops you covering the same ground twice.")
        .defaultValue(true).build());

    private final Setting<Integer> lookAhead = sgGuide.add(new IntSetting.Builder()
        .name("look-ahead")
        .description("How far out to weigh each direction, in chunks. Larger looks at the bigger picture; smaller reacts to the gap right in front of you.")
        .defaultValue(24).min(4).max(128).sliderRange(8, 64).visible(suggest::get).build());

    private final Setting<Boolean> arrow = sgGuide.add(new BoolSetting.Builder()
        .name("draw-arrow")
        .description("Draw a marker on the ground pointing the way it suggests.")
        .defaultValue(true).visible(suggest::get).build());

    // ---------------------------------------------------------------- overlay
    private final SettingGroup sgMap = settings.createGroup("Overlay");

    private final Setting<Boolean> overlay = sgMap.add(new BoolSetting.Builder()
        .name("overlay").description("Draw the searched area as a small map on screen.").defaultValue(true).build());
    private final Setting<Integer> mapRadius = sgMap.add(new IntSetting.Builder()
        .name("map-radius").description("How many chunks either side of you the map covers.")
        .defaultValue(24).min(4).max(64).sliderRange(8, 48).visible(overlay::get).build());
    private final Setting<Integer> cellPixels = sgMap.add(new IntSetting.Builder()
        .name("cell-size").description("How many pixels wide each chunk is on the map.")
        .defaultValue(3).min(1).max(12).sliderRange(1, 8).visible(overlay::get).build());
    private final Setting<Integer> mapX = sgMap.add(new IntSetting.Builder()
        .name("x").description("Distance from the left of the screen.")
        .defaultValue(6).min(0).max(3840).sliderRange(0, 1200).visible(overlay::get).build());
    private final Setting<Integer> mapY = sgMap.add(new IntSetting.Builder()
        .name("y").description("Distance from the top of the screen.")
        .defaultValue(420).min(0).max(2160).sliderRange(0, 900).visible(overlay::get).build());
    private final Setting<Boolean> showStats = sgMap.add(new BoolSetting.Builder()
        .name("stats").description("Write how much ground you have covered under the map.")
        .defaultValue(true).visible(overlay::get).build());

    private final Setting<SettingColor> coveredColor = sgMap.add(new ColorSetting.Builder()
        .name("covered-color").description("Colour for ground you have already been over.")
        .defaultValue(new SettingColor(60, 200, 120, 150)).visible(overlay::get).build());
    private final Setting<SettingColor> gapColor = sgMap.add(new ColorSetting.Builder()
        .name("gap-color").description("Colour for ground you have not.")
        .defaultValue(new SettingColor(40, 40, 40, 120)).visible(overlay::get).build());
    private final Setting<SettingColor> meColor = sgMap.add(new ColorSetting.Builder()
        .name("player-color").description("Colour of your marker on the map.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).visible(overlay::get).build());

    // ---------------------------------------------------------------- in world
    private final SettingGroup sgWorld = settings.createGroup("In World");

    private final Setting<Boolean> showFrontier = sgWorld.add(new BoolSetting.Builder()
        .name("frontier")
        .description("Outline the edge where searched ground meets unsearched ground, so you can fly along it and sweep cleanly instead of zig-zagging.")
        .defaultValue(false).build());
    private final Setting<Double> frontierY = sgWorld.add(new DoubleSetting.Builder()
        .name("frontier-y").description("Height to draw that edge at.")
        .defaultValue(120).min(-64).max(320).sliderRange(0, 250).visible(showFrontier::get).build());
    private final Setting<SettingColor> frontierColor = sgWorld.add(new ColorSetting.Builder()
        .name("frontier-color").description("Colour of the edge.")
        .defaultValue(new SettingColor(255, 200, 40, 140)).visible(showFrontier::get).build());

    /** chunk key -> when it was last searched */
    private final Map<Long, Long> covered = new ConcurrentHashMap<>();
    private double lastX, lastZ;
    private int tick;
    private String advice = "";
    private double adviceX, adviceZ;

    public SearchMap() {
        super(shama.addon.ShamaAddon.HUNT, "search-map++",
            "Remembers which ground you have already swept and points you at the ground you have not, so you stop covering the same area twice.");
    }

    @Override
    public void onActivate() {
        covered.clear();
        advice = "";
        if (mc.player != null) { lastX = mc.player.getX(); lastZ = mc.player.getZ(); }
    }

    @Override
    public void onDeactivate() { covered.clear(); advice = ""; }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null) return;
        if (pauseWhenStill.get()) {
            boolean moving = Math.abs(mc.player.getX() - lastX) > 0.05 || Math.abs(mc.player.getZ() - lastZ) > 0.05;
            if (!moving) return;
        }
        covered.put(event.chunk().getPos().toLong(), System.currentTimeMillis());
    }

    private boolean isCovered(int cx, int cz) {
        Long when = covered.get(ChunkPos.toLong(cx, cz));
        if (when == null) return false;
        if (forgetMinutes.get() <= 0) return true;
        return System.currentTimeMillis() - when < forgetMinutes.get() * 60_000L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        lastX = mc.player.getX(); lastZ = mc.player.getZ();

        if (forgetMinutes.get() > 0) {
            long cutoff = System.currentTimeMillis() - forgetMinutes.get() * 60_000L;
            covered.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        if (covered.size() > 200_000) covered.clear();       // hard ceiling

        if (!suggest.get() || tick++ % 20 != 0) return;
        pickDirection();
    }

    /**
     * Score each of the eight compass directions by how much unsearched ground lies that way, and
     * keep the best one. Nearer gaps count for more, since those are the ones you can actually reach.
     */
    private void pickDirection() {
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        int reach = lookAhead.get();
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double[] dx = {0, 0.7, 1, 0.7, 0, -0.7, -1, -0.7};
        double[] dz = {-1, -0.7, 0, 0.7, 1, 0.7, 0, -0.7};

        int best = -1;
        double bestScore = -1;
        for (int d = 0; d < 8; d++) {
            double score = 0;
            for (int step = 2; step <= reach; step++) {
                int cx = pcx + (int) Math.round(dx[d] * step);
                int cz = pcz + (int) Math.round(dz[d] * step);
                if (!isCovered(cx, cz)) score += 1.0 / step;     // closer gaps are worth more
            }
            if (score > bestScore) { bestScore = score; best = d; }
        }
        if (best < 0) { advice = ""; return; }
        advice = names[best];
        adviceX = mc.player.getX() + dx[best] * 48;
        adviceZ = mc.player.getZ() + dz[best] * 48;
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!overlay.get() || mc.player == null) return;
        int r = mapRadius.get(), cs = cellPixels.get();
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
        double x = mapX.get(), y = mapY.get();
        int size = (2 * r + 1) * cs;

        SettingColor cc = coveredColor.get(), gc = gapColor.get();
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 3, y - 3, size + 6, size + 6, new Color(0, 0, 0, 150));
        for (int gz = -r; gz <= r; gz++) {
            for (int gx = -r; gx <= r; gx++) {
                boolean got = isCovered(pcx + gx, pcz + gz);
                Renderer2D.COLOR.quad(x + (gx + r) * cs, y + (gz + r) * cs, cs, cs, got ? cc : gc);
            }
        }
        // you, in the middle
        Renderer2D.COLOR.quad(x + r * cs, y + r * cs, Math.max(2, cs), Math.max(2, cs), meColor.get());
        Renderer2D.COLOR.render();

        if (showStats.get()) {
            TextRenderer text = TextRenderer.get();
            int seen = 0, total = (2 * r + 1) * (2 * r + 1);
            for (int gz = -r; gz <= r; gz++)
                for (int gx = -r; gx <= r; gx++)
                    if (isCovered(pcx + gx, pcz + gz)) seen++;
            long blocks = (long) covered.size() * 256;
            String line1 = String.format("Searched %d%% nearby - %,d chunks total", (seen * 100) / total, covered.size());
            String line2 = advice.isEmpty() ? String.format("%,d blocks covered", blocks)
                                            : "Most unsearched ground: " + advice;
            text.beginBig();
            text.render(line1, x, y + size + 4, new Color(255, 255, 255, 255));
            text.render(line2, x, y + size + 4 + text.getHeight() + 2, new Color(190, 235, 255, 255));
            text.end();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        // the edge between searched and unsearched — fly along it to sweep cleanly
        if (showFrontier.get()) {
            int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z;
            int r = Math.min(mapRadius.get(), 32);
            SettingColor fc = frontierColor.get();
            Color fill = new Color(fc.r, fc.g, fc.b, fc.a);
            Color line = new Color(fc.r, fc.g, fc.b, Math.min(255, fc.a + 100));
            double y = frontierY.get();
            for (int gz = -r; gz <= r; gz++) {
                for (int gx = -r; gx <= r; gx++) {
                    int cx = pcx + gx, cz = pcz + gz;
                    if (!isCovered(cx, cz)) continue;
                    // covered, but touching something that is not: that is the edge
                    boolean edge = !isCovered(cx + 1, cz) || !isCovered(cx - 1, cz)
                                || !isCovered(cx, cz + 1) || !isCovered(cx, cz - 1);
                    if (!edge) continue;
                    double x0 = cx * 16.0, z0 = cz * 16.0;
                    event.renderer.box(x0, y, z0, x0 + 16, y + 0.2, z0 + 16, fill, line, ShapeMode.Both, 0);
                }
            }
        }

        if (arrow.get() && suggest.get() && !advice.isEmpty()) {
            SettingColor fc = frontierColor.get();
            Color line = new Color(fc.r, fc.g, fc.b, 255);
            double y = mc.player.getY();
            event.renderer.line(mc.player.getX(), y, mc.player.getZ(), adviceX, y, adviceZ, line);
            event.renderer.box(adviceX - 1, y - 1, adviceZ - 1, adviceX + 1, y + 1, adviceZ + 1,
                new Color(fc.r, fc.g, fc.b, 60), line, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        if (covered.isEmpty()) return null;
        return advice.isEmpty() ? covered.size() + " chunks" : covered.size() + " - go " + advice;
    }
}
