package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block Light ESP++ — colors nearby blocks by light level on a grey-to-yellow gradient:
 * bright yellow where there's lots of light, fading through pale/grey as the light drops,
 * and no highlight at all where light is zero. Great for spotting lit rooms/bases: torches
 * and lava light up an area and this paints it. Uses block light by default; scan is
 * throttled and range-limited so it stays smooth.
 */
public class BlockLightEsp extends Module {
    public enum LightMode { Block, Sky, Total }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgR = settings.createGroup("Render");

    private final Setting<LightMode> lightMode = sg.add(new EnumSetting.Builder<LightMode>()
        .name("light-type").description("Block = placed light sources (torches/lava). Sky = daylight exposure. Total = the higher of the two.").defaultValue(LightMode.Block).build());
    private final Setting<Integer> minLight = sg.add(new IntSetting.Builder()
        .name("min-light").description("Only highlight blocks at/above this light level (0 shows everything with any light).").defaultValue(1).min(0).max(15).sliderRange(0, 15).build());
    private final Setting<Boolean> lightSources = sg.add(new BoolSetting.Builder()
        .name("light-sources").description("Also box blocks that EMIT light (torches, lava, glowstone) for base finding.").defaultValue(false).build());
    private final Setting<Boolean> floorOnly = sg.add(new BoolSetting.Builder()
        .name("floor-only").description("Only highlight lit air on top of a solid block (lit floors) — much cleaner than filling the whole volume.").defaultValue(true).build());
    private final Setting<Integer> hRadius = sg.add(new IntSetting.Builder()
        .name("horizontal-radius").description("How far out sideways to scan (blocks).").defaultValue(24).min(2).max(64).sliderRange(8, 48).build());
    private final Setting<Integer> vRadius = sg.add(new IntSetting.Builder()
        .name("vertical-radius").description("How far up/down to scan (blocks).").defaultValue(8).min(1).max(32).sliderRange(2, 16).build());
    private final Setting<Integer> scanTicks = sg.add(new IntSetting.Builder()
        .name("scan-ticks").description("Ticks between rescans.").defaultValue(20).min(2).max(60).sliderRange(4, 40).build());

    private final Setting<Integer> alpha = sgR.add(new IntSetting.Builder().name("fill-alpha").description("Highlight transparency.").defaultValue(70).min(0).max(255).sliderRange(0, 160).build());
    private final Setting<ShapeMode> shapeMode = sgR.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());

    // ---- merged LightOverlay: spawn-spot crosses ----
    private final SettingGroup sgSpawn = settings.createGroup("Spawn Overlay");
    private final Setting<Boolean> spawnOverlay = sgSpawn.add(new BoolSetting.Builder().name("spawn-overlay").description("Draw a cross on every block a mob could spawn on. Red = spawns any time (no sky light), yellow = only at night.").defaultValue(false).build());
    private final Setting<Integer> spawnRadius = sgSpawn.add(new IntSetting.Builder().name("spawn-radius").description("How far around you to check for spawn spots.").defaultValue(12).range(2, 32).sliderRange(4, 24).visible(spawnOverlay::get).build());
    private final Setting<Integer> spawnMaxLight = sgSpawn.add(new IntSetting.Builder().name("spawn-max-light").description("A block counts as spawnable if its light level is at or below this. 0 = only fully dark blocks (vanilla spawning rule).").defaultValue(0).min(0).max(15).visible(spawnOverlay::get).build());
    private final Setting<Boolean> spawnBoxes = sgSpawn.add(new BoolSetting.Builder().name("spawn-boxes").description("Draw a flat box on each spawnable block instead of a cross (easier to see when spawn-proofing).").defaultValue(false).visible(spawnOverlay::get).build());
    private final Setting<SettingColor> alwaysColor = sgSpawn.add(new ColorSetting.Builder().name("always-color").description("Colour for spots that spawn mobs any time of day.").defaultValue(new SettingColor(255, 50, 50, 200)).visible(spawnOverlay::get).build());
    private final Setting<SettingColor> nightColor = sgSpawn.add(new ColorSetting.Builder().name("night-color").description("Colour for spots that only spawn mobs at night.").defaultValue(new SettingColor(255, 220, 60, 200)).visible(spawnOverlay::get).build());

    // ---- merged LightEsp: dark-chunk (base) finder ----
    private final SettingGroup sgDark = settings.createGroup("Dark Chunks");
    private final Setting<Boolean> darkChunks = sgDark.add(new BoolSetting.Builder().name("dark-chunks").description("While you're above the Y line, flag whole chunks below it that contain ANY block light — a carved, lit space underground usually means a base.").defaultValue(false).build());
    private final Setting<Integer> darkBelowY = sgDark.add(new IntSetting.Builder().name("scan-below-y").description("Only look at blocks below this Y for hidden light.").defaultValue(0).range(-64, 128).sliderRange(-64, 64).visible(darkChunks::get).build());
    private final Setting<Integer> darkSensitivity = sgDark.add(new IntSetting.Builder().name("dark-sensitivity").description("How faint a light counts. 1 = catch everything, higher = only brighter sources.").defaultValue(1).range(1, 15).sliderRange(1, 15).visible(darkChunks::get).build());
    private final Setting<SettingColor> darkColor = sgDark.add(new ColorSetting.Builder().name("dark-color").description("Colour used for flagged dark chunks.").defaultValue(new SettingColor(255, 160, 0, 200)).visible(darkChunks::get).build());

    private final List<long[]> crosses = new ArrayList<>();
    private final Set<Long> darkFlagged = ConcurrentHashMap.newKeySet();
    private int auxTick;

    // packed pos -> light level
    private final Map<Long, Integer> lit = new ConcurrentHashMap<>();
    private int tick;

    public BlockLightEsp() { super(shama.addon.ShamaAddon.HUNT, "light-debug++", "Everything to do with light in one place: light sources, where mobs can spawn, and pockets of darkness that shouldn't be there."); }

    @Override public void onActivate() { lit.clear(); crosses.clear(); darkFlagged.clear(); }
    @Override public void onDeactivate() { lit.clear(); crosses.clear(); darkFlagged.clear(); }

    private int lightAt(BlockPos pos) {
        return switch (lightMode.get()) {
            case Block -> mc.world.getLightLevel(LightType.BLOCK, pos);
            case Sky -> mc.world.getLightLevel(LightType.SKY, pos);
            case Total -> Math.max(mc.world.getLightLevel(LightType.BLOCK, pos), mc.world.getLightLevel(LightType.SKY, pos));
        };
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (tick++ % Math.max(1, scanTicks.get()) != 0) return;
        lit.clear();
        BlockPos c = mc.player.getBlockPos();
        int hr = hRadius.get(), vr = vRadius.get();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -hr; x <= hr; x++) for (int z = -hr; z <= hr; z++) for (int y = -vr; y <= vr; y++) {
            m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
            if (floorOnly.get()) {
                if (!mc.world.getBlockState(m).isAir()) continue;                              // must be air
                if (mc.world.getBlockState(m.move(net.minecraft.util.math.Direction.DOWN)).isAir()) { m.move(net.minecraft.util.math.Direction.UP); continue; } // solid below
                m.move(net.minecraft.util.math.Direction.UP);
            }
            if (lightSources.get() && mc.world.getBlockState(m).getLuminance() > 0) { lit.put(m.asLong(), 15); if (lit.size() > 12000) return; continue; }
            int l = lightAt(m);
            if (l < Math.max(1, minLight.get())) continue;                                      // no light -> no highlight
            lit.put(m.asLong(), l);
            if (lit.size() > 12000) return;                                                     // safety cap
        }
    }

    @EventHandler
    private void onAuxTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (auxTick++ % Math.max(1, scanTicks.get()) != 0) return;
        // spawn overlay
        if (spawnOverlay.get()) {
            crosses.clear();
            BlockPos c = mc.player.getBlockPos(); int r = spawnRadius.get();
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) for (int y = -r; y <= r; y++) {
                m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                if (!mc.world.getBlockState(m).isSideSolidFullSquare(mc.world, m, Direction.UP)) continue; // solid top
                BlockPos.Mutable up = m.move(Direction.UP).mutableCopy();
                if (!mc.world.getBlockState(up).isAir()) continue;                       // air above
                if (mc.world.getLightLevel(LightType.BLOCK, up) > spawnMaxLight.get()) continue;   // dark enough to spawn mobs
                boolean nightOnly = mc.world.getLightLevel(LightType.SKY, up) != 0;
                crosses.add(new long[]{up.asLong(), nightOnly ? 1 : 0});
                if (crosses.size() > 4000) break;
            }
        } else crosses.clear();
        // dark chunks
        if (darkChunks.get() && mc.player.getY() > darkBelowY.get()) {
            darkFlagged.clear();
            ChunkPos pc = mc.player.getChunkPos(); int cr = mc.options.getViewDistance().getValue();
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int cx = pc.x - cr; cx <= pc.x + cr; cx++) for (int cz = pc.z - cr; cz <= pc.z + cr; cz++) {
                var ch = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(ch instanceof WorldChunk)) continue;
                boolean lit2 = false;
                for (int x = 0; x < 16 && !lit2; x += 2) for (int z = 0; z < 16 && !lit2; z += 2)
                    for (int y = mc.world.getBottomY(); y <= darkBelowY.get(); y += 2)
                        if (mc.world.getLightLevel(LightType.BLOCK, m.set((cx << 4) + x, y, (cz << 4) + z)) >= darkSensitivity.get()) { lit2 = true; break; }
                if (lit2) darkFlagged.add(ChunkPos.toLong(cx, cz));
            }
        } else darkFlagged.clear();
    }

    @EventHandler
    private void onRenderAux(Render3DEvent event) {
        // spawn crosses
        if (spawnOverlay.get() && !crosses.isEmpty()) {
            for (long[] cr : crosses) {
                BlockPos p = BlockPos.fromLong(cr[0]);
                var col = cr[1] == 0 ? alwaysColor.get() : nightColor.get();
                double y = p.getY() + 0.02;
                if (spawnBoxes.get()) {
                    event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.05, p.getZ() + 1,
                        new Color(col.r, col.g, col.b, 60), col, ShapeMode.Both, 0);
                } else {
                    event.renderer.line(p.getX(), y, p.getZ(), p.getX() + 1, y, p.getZ() + 1, col);
                    event.renderer.line(p.getX() + 1, y, p.getZ(), p.getX(), y, p.getZ() + 1, col);
                }
            }
        }
        // dark chunk outlines
        if (darkChunks.get() && !darkFlagged.isEmpty() && mc.world != null) {
            var col = darkColor.get(); double y = darkBelowY.get();
            for (long k : darkFlagged) {
                ChunkPos cp = new ChunkPos(k); double x0 = cp.getStartX(), z0 = cp.getStartZ();
                event.renderer.box(x0, y, z0, x0 + 16, y + 0.3, z0 + 16, new Color(col.r, col.g, col.b, 40), col, ShapeMode.Both, 0);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (lit.isEmpty()) return;
        int a = alpha.get();
        ShapeMode mode = shapeMode.get();
        for (Map.Entry<Long, Integer> e : lit.entrySet()) {
            float t = MathHelper.clamp(e.getValue() / 15f, 0f, 1f);      // 0..1 by light level
            // grey (low) -> bright yellow (high)
            int r = (int) MathHelper.lerp(t, 120, 255);
            int g = (int) MathHelper.lerp(t, 120, 255);
            int b = (int) MathHelper.lerp(t, 120, 0);
            Color side = new Color(r, g, b, a);
            Color line = new Color(r, g, b, Math.min(255, a + 90));
            BlockPos p = BlockPos.fromLong(e.getKey());
            event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, side, line, mode, 0);
        }
    }

    @Override public String getInfoString() { return lit.isEmpty() ? null : lit.size() + " lit"; }
}
