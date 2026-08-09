package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * Jumps++ — merged jump modules with tickboxes:
 *   high-jump : jump higher than vanilla.
 *   long-jump : launch forward with your movement direction when you jump.
 *   air-jump  : allow one extra jump while airborne.
 *   auto-jump : auto-jump whenever you're holding forward on the ground.
 */
public class Jumps extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> highJump = sg.add(new BoolSetting.Builder().name("high-jump").description("Jump higher than normal.").defaultValue(false).build());
    private final Setting<Double> jumpHeight = sg.add(new DoubleSetting.Builder().name("jump-power").description("How high the high-jump goes.").defaultValue(0.7).min(0.42).sliderRange(0.42, 2).visible(highJump::get).build());
    private final Setting<Boolean> longJump = sg.add(new BoolSetting.Builder().name("long-jump").description("Jump further forward.").defaultValue(false).build());
    private final Setting<Double> longPower = sg.add(new DoubleSetting.Builder().name("long-power").description("How far the long-jump goes.").defaultValue(1.2).min(0.3).sliderRange(0.3, 3).visible(longJump::get).build());
    private final Setting<Boolean> airJump = sg.add(new BoolSetting.Builder().name("air-jump").description("Allow jumping again in mid-air.").defaultValue(false).build());
    private final Setting<Boolean> autoJump = sg.add(new BoolSetting.Builder().name("auto-jump").description("Jump automatically whenever possible.").defaultValue(false).build());

    private boolean prevJump, usedAir, prevOnGround;

    public Jumps() {
        super(shama.addon.ShamaAddon.MOVEMENT, "jumps++", "High / long / air / auto jump in one module.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        boolean onGround = mc.player.isOnGround();
        boolean jumpPressed = mc.options.jumpKey.isPressed();
        if (onGround) usedAir = false;

        if (autoJump.get() && onGround && mc.options.forwardKey.isPressed()) jumpPressed = true;

        // rising edge of jump
        boolean edge = jumpPressed && !prevJump;

        if (edge && onGround) {
            Vec3d v = mc.player.getVelocity();
            double vy = highJump.get() ? jumpHeight.get() : v.y;
            double vx = v.x, vz = v.z;
            if (longJump.get()) {
                double rad = Math.toRadians(mc.player.getYaw());
                vx = -Math.sin(rad) * longPower.get();
                vz = Math.cos(rad) * longPower.get();
                if (!highJump.get()) vy = 0.42;
            }
            if (highJump.get() || longJump.get()) mc.player.setVelocity(vx, vy, vz);
        } else if (edge && !onGround && airJump.get() && !usedAir) {
            usedAir = true;
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, highJump.get() ? jumpHeight.get() : 0.42, v.z);
        }
        if (autoJump.get() && onGround && mc.options.forwardKey.isPressed()) mc.options.jumpKey.setPressed(true);
        prevJump = jumpPressed;
        prevOnGround = onGround;
    }

    @Override public String getInfoString() {
        StringBuilder b = new StringBuilder();
        if (highJump.get()) b.append("high ");
        if (longJump.get()) b.append("long ");
        if (airJump.get()) b.append("air ");
        if (autoJump.get()) b.append("auto");
        return b.length() == 0 ? "off" : b.toString().trim();
    }
}
