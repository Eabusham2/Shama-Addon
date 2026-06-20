package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * LightEsp
 *
 * While you are ABOVE the Y threshold (default Y=0), scans every loaded chunk
 * within render distance. Below Y=0 vanilla terrain is solid deepslate with zero
 * block light, so if ANY block in a chunk below the threshold has block light,
 * that chunk holds a carved, lit space (a base) and gets a flat square outline.
 *
 * This only reads chunk data the client already has; it cannot recover data the
 * server never sent. If a server scrubs light data below Y=0, nothing shows.
 */
public class LightEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Integer> yThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("scan-below-y")
        .description("Only scans blocks below this Y, and only while you are above it.")
        .defaultValue(0)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("How faint a light counts. 1 = catch everything (torches, glow, anything). Raise it to only flag brighter sources and cut false positives.")
        .defaultValue(1)
        .range(1, 15)
        .sliderRange(1, 15)
        .build()
    );

    private final Setting<Integer> scanStep = sgGeneral.add(new IntSetting.Builder()
        .name("scan-detail")
        .description("Sampling step. 1 = check every block (slowest, most thorough). Higher = faster but may miss tiny rooms.")
        .defaultValue(2)
        .range(1, 4)
        .sliderRange(1, 4)
        .build()
    );

    private final Setting<Integer> rescanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-ticks")
        .description("Ticks between scans. Lower = more responsive, heavier on FPS.")
        .defaultValue(20)
        .range(5, 200)
        .sliderRange(5, 100)
        .build()
    );

    // Render
    private final Setting<Integer> outlineY = sgRender.add(new IntSetting.Builder()
        .name("outline-y")
        .description("The Y level the square chunk outline is drawn at.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(0, 128)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color of the chunk square.")
        .defaultValue(new SettingColor(255, 200, 0, 25))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color of the chunk square.")
        .defaultValue(new SettingColor(255, 200, 0, 255))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the square is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    // Flagged chunk coordinates (packed long keys -> chunk x/z).
    private final List<long[]> hits = new ArrayList<>();
    private int tickCounter;

    public LightEsp() {
        super(Categories.Render, "light-esp", "Flags chunks with artificial light below a Y level while you're above it.");
    }

    @Override
    public void onActivate() {
        hits.clear();
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Only active while above the threshold.
        if (mc.player.getY() < yThreshold.get()) {
            hits.clear();
            return;
        }

        if (tickCounter++ % rescanInterval.get() != 0) return;

        scan();
    }

    private void scan() {
        hits.clear();

        int threshold = yThreshold.get();
        int bottomY = mc.world.getBottomY();
        int scanTop = Math.min(threshold - 1, mc.world.getTopYInclusive());
        if (scanTop < bottomY) return;

        int step = scanStep.get();
        int light = sensitivity.get();

        int radius = mc.options.getViewDistance().getValue();
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                Chunk chunk = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                if (chunkHasLight(pos, baseX, baseZ, bottomY, scanTop, step, light)) {
                    hits.add(new long[]{cx, cz});
                }
            }
        }
    }

    // Returns true as soon as one block in the chunk (below the threshold) is lit.
    private boolean chunkHasLight(BlockPos.Mutable pos, int baseX, int baseZ, int bottomY, int scanTop, int step, int light) {
        for (int y = bottomY; y <= scanTop; y += step) {
            for (int dx = 0; dx < 16; dx += step) {
                for (int dz = 0; dz < 16; dz += step) {
                    pos.set(baseX + dx, y, baseZ + dz);
                    if (mc.world.getLightLevel(LightType.BLOCK, pos) >= light) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (hits.isEmpty()) return;

        double y = outlineY.get();

        for (long[] hit : hits) {
            double x1 = hit[0] << 4;
            double z1 = hit[1] << 4;
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            // Flat square (zero height) drawn at outline-y: a chunk footprint outline.
            Box square = new Box(x1, y, z1, x2, y, z2);
            event.renderer.box(square, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(hits.size());
    }
}
