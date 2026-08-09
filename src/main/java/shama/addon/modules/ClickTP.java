package shama.addon.modules;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/** ClickTP++ — teleport forward along your look by sending stepped position packets. Bind a key; press to blink. Server-validated, so distance is capped on strict servers. */
public class ClickTP extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> distance = sg.add(new DoubleSetting.Builder().name("distance").description("Maximum distance in blocks.").defaultValue(6).min(1).sliderRange(1, 20).build());
    private final Setting<Integer> steps = sg.add(new IntSetting.Builder().name("steps").description("Packets to split the jump into (more = smoother/safer).").defaultValue(4).range(1, 20).build());

    public ClickTP() { super(shama.addon.ShamaAddon.MOVEMENT, "click-tp++", "Blink forward along your look. Bind a key and tap it."); }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.getNetworkHandler() == null) { toggle(); return; }
        Vec3d look = mc.player.getRotationVec(1f).multiply(1, 0, 1).normalize();
        double per = distance.get() / steps.get();
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        for (int i = 0; i < steps.get(); i++) {
            x += look.x * per; z += look.z * per;
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, mc.player.horizontalCollision));
        }
        mc.player.setPosition(x, y, z);
        toggle(); // one-shot
    }
}
