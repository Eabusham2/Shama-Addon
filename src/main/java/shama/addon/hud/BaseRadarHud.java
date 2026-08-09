package shama.addon.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Base Radar HUD — top-down radar plotting nearby players (red) and mobs (yellow), north-up. Base-hunting aid. */
public class BaseRadarHud extends HudElement {
    public static final HudElementInfo<BaseRadarHud> INFO =
        new HudElementInfo<>(shama.addon.ShamaAddon.HUD_GROUP, "shama-base-radar", "Radar of nearby players and mobs.", BaseRadarHud::new);

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> size = sg.add(new IntSetting.Builder().name("size").defaultValue(120).range(60, 300).sliderRange(60, 240).build());
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder().name("range").defaultValue(96).min(16).sliderRange(32, 256).build());
    private final Setting<SettingColor> bg = sg.add(new ColorSetting.Builder().name("background").defaultValue(new SettingColor(0, 0, 0, 120)).build());
    private final Setting<SettingColor> playerColor = sg.add(new ColorSetting.Builder().name("player-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());
    private final Setting<SettingColor> mobColor = sg.add(new ColorSetting.Builder().name("mob-color").defaultValue(new SettingColor(255, 210, 0, 255)).build());

    public BaseRadarHud() { super(INFO); }

    @Override
    public void render(HudRenderer renderer) {
        int s = size.get();
        setSize(s, s);
        double cx = x + s / 2.0, cy = y + s / 2.0, scale = (s / 2.0) / range.get();
        renderer.quad(x, y, s, s, bg.get());
        renderer.quad(cx - 2, cy - 2, 4, 4, new SettingColor(0, 255, 0, 255));
        if (mc.world == null || mc.player == null) return;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            boolean pl = e instanceof PlayerEntity, mob = e instanceof MobEntity;
            if (!pl && !mob) continue;
            double dx = (e.getX() - mc.player.getX()) * scale, dz = (e.getZ() - mc.player.getZ()) * scale;
            if (Math.abs(dx) > s / 2.0 || Math.abs(dz) > s / 2.0) continue;
            renderer.quad(cx + dx - 1.5, cy + dz - 1.5, 3, 3, pl ? playerColor.get() : mobColor.get());
        }
    }
}
