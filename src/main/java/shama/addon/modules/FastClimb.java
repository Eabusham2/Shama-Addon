package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/** FastClimb++ — climb ladders/vines/scaffolding much faster than vanilla. */
public class FastClimb extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder().name("speed").description("How fast (higher = faster).").defaultValue(0.5).min(0.1).sliderRange(0.2, 1.5).build());

    public FastClimb() { super(shama.addon.ShamaAddon.MOVEMENT, "fast-climb++", "Climb ladders/vines faster."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !mc.player.isClimbing()) return;
        Vec3d v = mc.player.getVelocity();
        double vy = 0;
        if (mc.options.forwardKey.isPressed() || mc.options.jumpKey.isPressed()) vy = speed.get();
        else if (mc.options.sneakKey.isPressed()) vy = -speed.get();
        mc.player.setVelocity(v.x, vy, v.z);
    }
}
