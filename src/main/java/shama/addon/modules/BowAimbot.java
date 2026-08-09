package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import shama.addon.util.Humanize;
import net.minecraft.util.math.Vec3d;

/** BowAimbot++ — while you're drawing a bow/crossbow, auto-aims your view at the nearest target (with a small arc for drop). Ported from their BowAimbot. */
public class BowAimbot extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> range = sg.add(new DoubleSetting.Builder().name("range").description("How far out (in blocks) this reaches.").defaultValue(40).min(4).sliderRange(8, 80).build());
    private final Setting<Double> aimNoise = sg.add(new DoubleSetting.Builder().name("aim-noise").description("Add a tiny random wobble (degrees) to the auto-aim so it doesn't lock on with robotic precision.").defaultValue(0.0).min(0).max(5).sliderRange(0,3).build());

    public BowAimbot() { super(shama.addon.ShamaAddon.COMBAT, "bow-aimbot++", "Auto-aims your bow at the nearest target while drawing."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        boolean drawing = mc.player.isUsingItem() && (mc.player.getActiveItem().isOf(Items.BOW) || mc.player.getActiveItem().isOf(Items.CROSSBOW));
        if (!drawing) return;

        PlayerEntity target = null; double best = range.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d < best) { best = d; target = p; }
        }
        if (target == null) return;

        Vec3d eye = mc.player.getEyePos();
        double tx = target.getX() - eye.x;
        double ty = (target.getY() + target.getStandingEyeHeight() * 0.9) - eye.y;
        double tz = target.getZ() - eye.z;
        double dist = Math.sqrt(tx * tx + tz * tz);
        // simple projectile arc compensation
        double arc = dist * 0.05;
        float yaw = (float) (MathHelper.atan2(tz, tx) * (180 / Math.PI)) - 90f;
        float pitch = (float) -(MathHelper.atan2(ty + arc, dist) * (180 / Math.PI));
        float n = (float) (double) aimNoise.get();
        mc.player.setYaw(yaw + Humanize.rotationNoise(n));
        mc.player.setPitch(MathHelper.clamp(pitch + Humanize.rotationNoise(n), -90f, 90f));
    }
}
