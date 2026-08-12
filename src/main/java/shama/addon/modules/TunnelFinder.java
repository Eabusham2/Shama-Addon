package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tunnel Finder++ — scans each chunk for long straight air corridors along the 4
 * horizontal directions: a run of air at the same height and axis over 'min-length'
 * with solid floor beneath is a dug tunnel, not a cave, because caves are irregular.
 *
 * The shape ticks decide what counts. A crawl tunnel, a walking tunnel, a two-wide
 * corridor and a three-wide highway all read very differently about who dug them and
 * why, so each is its own option rather than one catch-all.
 */
public class TunnelFinder extends Module {
    private static final Direction[] DIRS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> size1x1 = sg.add(new BoolSetting.Builder()
        .name("1x1")
        .description("Crawl tunnels, one block wide and one high. The cheapest way to cover distance underground, so people digging long-haul routes use them and they are easy to miss.")
        .defaultValue(true).build());
    private final Setting<Boolean> size1x2 = sg.add(new BoolSetting.Builder()
        .name("1x2")
        .description("The ordinary walking tunnel, one wide and two high. By far the most common thing you will find.")
        .defaultValue(true).build());
    private final Setting<Boolean> size2x2 = sg.add(new BoolSetting.Builder()
        .name("2x2")
        .description("Two wide and two high — a main corridor rather than a branch, so it usually leads somewhere worth following.")
        .defaultValue(true).build());
    private final Setting<Boolean> size3x3 = sg.add(new BoolSetting.Builder()
        .name("3x3")
        .description("Three by three. Nobody digs this by hand for fun, so it means a highway, a nether route or something built to move a lot of material.")
        .defaultValue(true).build());

    private final Setting<Integer> minLength = sg.add(new IntSetting.Builder()
        .name("min-length").description("Minimum straight air-corridor length to flag as a tunnel.")
        .defaultValue(8).range(4, 30).sliderRange(5, 20).build());

    private final Setting<Integer> maxY = sg.add(new IntSetting.Builder()
        .name("max-y").description("Only scan below this Y (surface has too many false positives).")
        .defaultValue(40).min(-64).max(320).sliderRange(-64, 64).build());

    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder()
        .name("render-distance").description("How far away (in blocks) things are still drawn.").defaultValue(256).min(16).sliderRange(32, 512).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(0, 200, 255, 220)).build());

    // chunkKey -> list of [x1,y1,z1,x2,y2,z2] segments
    private final java.util.Map<Long, List<int[]>> segments = new ConcurrentHashMap<>();

    public TunnelFinder() {
        super(shama.addon.ShamaAddon.HUNT, "tunnel-finder++", "Finds long straight tunnels players have dug, including ones far underground you'd never stumble across.");
    }

    @Override
    public void onActivate() { segments.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        int bottom = chunk.getBottomY();
        int top = Math.min(maxY.get(), chunk.getTopYInclusive());
        int cx = chunk.getPos().getStartX(), cz = chunk.getPos().getStartZ();
        List<int[]> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int y = bottom + 1; y <= top; y++) {
            // scan rows along X and Z within this chunk
            for (int line = 0; line < 16; line++) {
                scanRun(chunk, cx, cz, y, line, true, found, m);
                scanRun(chunk, cx, cz, y, line, false, found, m);
            }
        }
        if (!found.isEmpty()) segments.put(chunk.getPos().toLong(), found);
    }

    private void scanRun(WorldChunk chunk, int cx, int cz, int y, int line, boolean alongX, List<int[]> found, BlockPos.Mutable m) {
        int run = 0, startPos = 0;
        for (int i = 0; i <= 16; i++) {
            boolean air = false, floor = false;
            if (i < 16) {
                int x = alongX ? cx + i : cx + line;
                int z = alongX ? cz + line : cz + i;
                // width runs across the tunnel, height runs up it
                int side = alongX ? 0 : 1;
                boolean a1 = chunk.getBlockState(m.set(x, y, z)).isAir();
                boolean a2 = chunk.getBlockState(m.set(x, y + 1, z)).isAir();
                boolean roof = !chunk.getBlockState(m.set(x, y + 2, z)).isAir();

                // one and two blocks to the side, for the wider shapes
                int wx = side == 0 ? x : x + 1, wz = side == 0 ? z + 1 : z;
                boolean w1 = chunk.getBlockState(m.set(wx, y, wz)).isAir();
                boolean w2 = chunk.getBlockState(m.set(wx, y + 1, wz)).isAir();
                int w2x = side == 0 ? x : x + 2, w2z = side == 0 ? z + 2 : z;
                boolean t1 = chunk.getBlockState(m.set(w2x, y, w2z)).isAir();
                boolean t3 = chunk.getBlockState(m.set(x, y + 2, z)).isAir();

                boolean is1x1 = size1x1.get() && a1 && !a2;                 // one high, capped
                boolean is1x2 = size1x2.get() && a1 && a2 && roof;          // two high, capped
                boolean is2x2 = size2x2.get() && a1 && a2 && w1 && w2;      // two wide, two high
                boolean is3x3 = size3x3.get() && a1 && a2 && t3 && w1 && t1;

                air = is1x1 || is1x2 || is2x2 || is3x3;
                floor = !chunk.getBlockState(m.set(x, y - 1, z)).isAir();
            }
            if (air && floor) { if (run == 0) startPos = i; run++; }
            else {
                if (run >= minLength.get()) {
                    int x1 = alongX ? cx + startPos : cx + line, z1 = alongX ? cz + line : cz + startPos;
                    int x2 = alongX ? cx + startPos + run - 1 : cx + line, z2 = alongX ? cz + line : cz + startPos + run - 1;
                    found.add(new int[]{x1, y, z1, x2, y, z2});
                }
                run = 0;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || segments.isEmpty()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3d eye = mc.player.getEyePos();
        SettingColor c = lineColor.get();
        for (List<int[]> list : segments.values()) {
            for (int[] s : list) {
                double mx = (s[0] + s[3]) / 2.0, mz = (s[2] + s[5]) / 2.0;
                double dx = mx - eye.x, dz = mz - eye.z;
                if (dx * dx + dz * dz > maxSq) continue;
                event.renderer.line(s[0] + 0.5, s[1] + 0.5, s[2] + 0.5, s[3] + 0.5, s[4] + 0.5, s[5] + 0.5, c);
            }
        }
    }

    @Override
    public String getInfoString() { return segments.size() + " chunks"; }
}
