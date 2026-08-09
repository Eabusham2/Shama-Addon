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
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Break Indicators — draws a shrinking box on blocks being broken (yours and other players').
 * Reads the server's block-breaking-progress packets directly, so it needs no mixin into WorldRenderer
 * (whose internal field for this moved in 1.21.11).
 */
public class BreakIndicators extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<SettingColor> startColor = sg.add(new ColorSetting.Builder().name("color-at-start").description("Colour at the start (0% progress).").defaultValue(new SettingColor(255, 0, 0, 30)).build());
    private final Setting<SettingColor> endColor = sg.add(new ColorSetting.Builder().name("color-when-done").description("Colour at the end (100% progress).").defaultValue(new SettingColor(0, 255, 0, 60)).build());
    private final Setting<SettingColor> lineColor = sg.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(255, 255, 255, 200)).build());
    private final Setting<ShapeMode> shapeMode = sg.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());
    private final Setting<Integer> timeout = sg.add(new IntSetting.Builder().name("timeout-ticks").description("Forget a break if no update arrives for this many ticks (in case the finish/cancel packet is missed).").defaultValue(100).min(20).max(400).sliderRange(40, 200).build());

    // pos -> [stage 0..10, ticksSinceUpdate]
    private final Map<Long, int[]> breaking = new ConcurrentHashMap<>();

    public BreakIndicators() { super(shama.addon.ShamaAddon.HUNT, "break-indicators++", "Boxes blocks being broken, shrinking with progress."); }

    @Override public void onDeactivate() { breaking.clear(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof BlockBreakingProgressS2CPacket p)) return;
        int stage = p.getProgress();
        long key = p.getPos().asLong();
        if (stage < 0 || stage > 9) breaking.remove(key);        // finished or cancelled
        else breaking.put(key, new int[]{stage, 0});
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (breaking.isEmpty()) return;
        int limit = timeout.get();
        breaking.values().removeIf(v -> (v[1] += 1) > limit);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || breaking.isEmpty()) return;
        Color start = startColor.get(), end = endColor.get(), line = lineColor.get();
        for (Map.Entry<Long, int[]> e : breaking.entrySet()) {
            BlockPos pos = BlockPos.fromLong(e.getKey());
            float t = e.getValue()[0] / 9f;
            BlockState state = mc.world.getBlockState(pos);
            VoxelShape shape = state.getOutlineShape(mc.world, pos);
            Box b = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();
            double shrink = 0.5 * t * (b.maxX - b.minX);
            double x1 = pos.getX() + b.minX + shrink, y1 = pos.getY() + b.minY + shrink, z1 = pos.getZ() + b.minZ + shrink;
            double x2 = pos.getX() + b.maxX - shrink, y2 = pos.getY() + b.maxY - shrink, z2 = pos.getZ() + b.maxZ - shrink;
            Color side = new Color(
                (int) (start.r + (end.r - start.r) * t), (int) (start.g + (end.g - start.g) * t),
                (int) (start.b + (end.b - start.b) * t), (int) (start.a + (end.a - start.a) * t));
            event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, shapeMode.get(), 0);
        }
    }
}
