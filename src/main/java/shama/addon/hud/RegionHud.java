package shama.addon.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Region HUD — shows the .mca region file (32x32 chunks) you're standing in, for stash hunting. */
public class RegionHud extends HudElement {
    public static final HudElementInfo<RegionHud> INFO =
        new HudElementInfo<>(shama.addon.ShamaAddon.HUD_GROUP, "shama-region", "Current region file (r.X.Z).", RegionHud::new);

    private final Setting<SettingColor> color = settings.getDefaultGroup().add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 255, 255, 255)).build());

    public RegionHud() { super(INFO); }

    @Override
    public void render(HudRenderer renderer) {
        String text;
        if (mc.player == null) text = "Region: -";
        else {
            int rx = mc.player.getBlockPos().getX() >> 9;
            int rz = mc.player.getBlockPos().getZ() >> 9;
            text = "Region r." + rx + "." + rz;
        }
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, color.get(), true);
    }
}
