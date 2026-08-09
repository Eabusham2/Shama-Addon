package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Reverse Step — drop down small ledges quickly instead of the slow vanilla fall arc. */
public class ReverseStep extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> fallSpeed = sg.add(new DoubleSetting.Builder().name("fall-speed").description("Downward speed applied when dropping (blocks/tick).").defaultValue(3.0).min(0.1).sliderRange(0.5, 6).build());
    private final Setting<Double> fallDistance = sg.add(new DoubleSetting.Builder().name("fall-distance").description("Only trigger when ground is within this many blocks below.").defaultValue(3.0).min(0.5).sliderRange(1, 8).build());
    private final Setting<Boolean> vehicles = sg.add(new BoolSetting.Builder().name("vehicles").description("Also affect vehicles you're riding.").defaultValue(false).build());

    public ReverseStep() { super(shama.addon.ShamaAddon.MOVEMENT, "reverse-step++", "Fall down small ledges instantly."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.hasVehicle() && !vehicles.get()) return;
        if (mc.player.isOnGround()) return;
        Vec3d v = mc.player.getVelocity();
        if (v.y > 0.0) return; // only while descending

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean groundBelow = false;
        for (double d = 0.5; d <= fallDistance.get() + 1; d += 0.5) {
            if (!mc.world.getBlockState(BlockPos.ofFloored(x, y - d, z)).isAir()) { groundBelow = true; break; }
        }
        if (groundBelow) mc.player.setVelocity(v.x, -fallSpeed.get(), v.z);
    }
}
