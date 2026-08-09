package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/** Trail — draws a fading line trail behind you as you move. */
public class Trail extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> length = sg.add(new IntSetting.Builder().name("length").description("How many points to keep.").defaultValue(80).min(2).max(400).sliderRange(10, 200).build());
    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder().name("color").description("Highlight colour.").defaultValue(new SettingColor(120, 200, 255, 200)).build());
    private final Setting<Boolean> fade = sg.add(new BoolSetting.Builder().name("fade").description("Fade the tail out.").defaultValue(true).build());

    private final Deque<Vec3d> points = new ArrayDeque<>();

    public Trail() { super(shama.addon.ShamaAddon.MISC, "trail++", "A fading trail behind you."); }

    @Override public void onActivate() { points.clear(); }
    @Override public void onDeactivate() { points.clear(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        points.addLast(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        while (points.size() > length.get()) points.removeFirst();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (points.size() < 2) return;
        Color base = color.get();
        Vec3d prev = null;
        int i = 0, n = points.size();
        for (Vec3d p : points) {
            if (prev != null) {
                int a = fade.get() ? (int) (base.a * (i / (double) n)) : base.a;
                Color c = new Color(base.r, base.g, base.b, a);
                event.renderer.line(prev.x, prev.y + 0.1, prev.z, p.x, p.y + 0.1, p.z, c);
            }
            prev = p; i++;
        }
    }
}
