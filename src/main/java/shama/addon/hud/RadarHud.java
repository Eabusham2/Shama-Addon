package shama.addon.hud;

import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Radar HUD — a top-down mini radar drawn on the HUD (built with Meteor's HudElement
 * API, same as their region-map HUD). Plots nearby players as dots relative to you
 * (north-up), so you can see who's around without turning. Add it from the HUD tab.
 */
public class RadarHud extends HudElement {
    public static final HudElementInfo<RadarHud> INFO =
        new HudElementInfo<>(shama.addon.ShamaAddon.HUD_GROUP, "shama-radar", "Top-down radar of nearby players.", RadarHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> size = sg.add(new IntSetting.Builder().name("size").defaultValue(120).range(60, 300).sliderRange(60, 240).build());
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder().name("range").description("Blocks mapped to the radar edge.").defaultValue(128).min(16).sliderRange(32, 512).build());
    private final Setting<SettingColor> bg = sg.add(new ColorSetting.Builder().name("background").defaultValue(new SettingColor(0, 0, 0, 120)).build());
    private final Setting<SettingColor> selfColor = sg.add(new ColorSetting.Builder().name("self-color").defaultValue(new SettingColor(0, 255, 0, 255)).build());
    private final Setting<SettingColor> playerColor = sg.add(new ColorSetting.Builder().name("player-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());

    public RadarHud() { super(INFO); }

    @Override
    public void render(HudRenderer renderer) {
        int s = size.get();
        setSize(s, s);
        double x = this.x, y = this.y;

        renderer.quad(x, y, s, s, bg.get());

        double cx = x + s / 2.0, cy = y + s / 2.0;
        double scale = (s / 2.0) / range.get();

        // self dot in the centre
        renderer.quad(cx - 2, cy - 2, 4, 4, selfColor.get());

        MinecraftClient m = mc;
        if (m.world == null || m.player == null) return;
        Color pc = playerColor.get();
        for (PlayerEntity p : m.world.getPlayers()) {
            if (p == m.player) continue;
            double dx = (p.getX() - m.player.getX()) * scale;
            double dz = (p.getZ() - m.player.getZ()) * scale;
            if (Math.abs(dx) > s / 2.0 || Math.abs(dz) > s / 2.0) continue; // off radar
            renderer.quad(cx + dx - 1.5, cy + dz - 1.5, 3, 3, pc);
        }
    }
}
