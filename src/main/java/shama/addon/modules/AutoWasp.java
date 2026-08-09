package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * AutoWasp++ — ported from their AutoWasp: auto-fly toward the nearest target and hold a
 * set offset above/around them (elytra "wasp" pursuit), with separate horizontal/vertical
 * speed and an option to avoid landing on the ground. Movement-assist for elytra combat.
 */
public class AutoWasp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder().name("target-range").description("How far away a target can be (blocks).").defaultValue(64).min(4).sliderRange(8, 128).build());
    private final Setting<Double> horizontalSpeed = sg.add(new DoubleSetting.Builder().name("horizontal-speed").description("Horizontal speed (blocks/tick).").defaultValue(1.5).min(0).sliderRange(0.2, 4).build());
    private final Setting<Double> verticalSpeed = sg.add(new DoubleSetting.Builder().name("vertical-speed").description("Vertical speed (blocks/tick).").defaultValue(0.8).min(0).sliderRange(0, 3).build());
    private final Setting<Double> offsetY = sg.add(new DoubleSetting.Builder().name("offset-y").description("Height to hold above the target.").defaultValue(3).sliderRange(-5, 10).build());
    private final Setting<Boolean> avoidLanding = sg.add(new BoolSetting.Builder().name("avoid-landing").description("Don't dive into the ground.").defaultValue(true).build());

    public AutoWasp() {
        super(shama.addon.ShamaAddon.MOVEMENT, "auto-wasp++", "Auto-fly pursuit that holds an offset over the nearest target.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        PlayerEntity target = null; double best = targetRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d < best) { best = d; target = p; }
        }
        if (target == null) return;

        Vec3d goal = new net.minecraft.util.math.Vec3d(target.getX(), target.getY(), target.getZ()).add(0, offsetY.get(), 0);
        Vec3d delta = goal.subtract(new net.minecraft.util.math.Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        Vec3d horiz = new Vec3d(delta.x, 0, delta.z);
        Vec3d dir = horiz.lengthSquared() > 0.01 ? horiz.normalize() : Vec3d.ZERO;

        double vy = Math.max(-verticalSpeed.get(), Math.min(verticalSpeed.get(), delta.y));
        if (avoidLanding.get() && mc.player.getY() < target.getY() && mc.player.isOnGround()) vy = verticalSpeed.get();

        mc.player.setVelocity(dir.x * horizontalSpeed.get(), vy, dir.z * horizontalSpeed.get());
        mc.player.velocityDirty = true;
    }
}
