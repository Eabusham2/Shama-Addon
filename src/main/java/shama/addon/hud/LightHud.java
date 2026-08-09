package shama.addon.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.LightType;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Light HUD — shows block/sky light at your position (base-finding readout). */
public class LightHud extends HudElement {
    public static final HudElementInfo<LightHud> INFO =
        new HudElementInfo<>(shama.addon.ShamaAddon.HUD_GROUP, "shama-light", "Block/sky light at your position.", LightHud::new);

    private final Setting<SettingColor> color = settings.getDefaultGroup().add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 255, 255, 255)).build());

    public LightHud() { super(INFO); }

    @Override
    public void render(HudRenderer renderer) {
        String text;
        if (mc.world == null || mc.player == null) text = "Light: -";
        else {
            var p = mc.player.getBlockPos();
            text = "Light  B:" + mc.world.getLightLevel(LightType.BLOCK, p) + "  S:" + mc.world.getLightLevel(LightType.SKY, p);
        }
        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, color.get(), true);
    }
}
