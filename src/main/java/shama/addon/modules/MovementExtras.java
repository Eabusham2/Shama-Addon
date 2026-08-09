package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Movement Extras++ — merged minor movement tweaks with tickboxes:
 *   gui-move       : keep walking with WASD while a screen (chest/inventory) is open.
 *   entity-control : steer the entity you're riding (pig/boat/horse) with WASD + look.
 *   slippy         : reduce ground friction so you slide like on ice.
 *   reverse-step    : step DOWN small ledges smoothly instead of walking off and falling.
 */
public class MovementExtras extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> guiMove = sg.add(new BoolSetting.Builder().name("gui-move").description("Keep moving while a screen is open.").defaultValue(false).build());
    private final Setting<Boolean> entityControl = sg.add(new BoolSetting.Builder().name("entity-control").description("Steer the entity you're riding.").defaultValue(false).build());
    private final Setting<Boolean> slippy = sg.add(new BoolSetting.Builder().name("slippy").description("Make movement slippery like ice.").defaultValue(false).build());
    private final Setting<Double> slipAmount = sg.add(new DoubleSetting.Builder().name("slip-amount").description("How slippery movement is.").defaultValue(1.5).min(1).sliderRange(1, 4).visible(slippy::get).build());
    private final Setting<Boolean> reverseStep = sg.add(new BoolSetting.Builder().name("reverse-step").description("Step down off edges smoothly instead of falling.").defaultValue(false).build());

    public MovementExtras() {
        super(shama.addon.ShamaAddon.MOVEMENT, "movement-extras++", "GUI-move / entity-control / slippy / reverse-step in one module.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // gui-move: force the movement keybinds from physical key state while a screen is open
        if (guiMove.get() && mc.currentScreen != null) {
            force(mc.options.forwardKey);
            force(mc.options.backKey);
            force(mc.options.leftKey);
            force(mc.options.rightKey);
            force(mc.options.jumpKey);
            force(mc.options.sneakKey);
        }

        // entity-control: drive the ridden entity toward your look with WASD
        if (entityControl.get() && mc.player.hasVehicle()) {
            Entity v = mc.player.getVehicle();
            double mx = 0, mz = 0, rad = Math.toRadians(mc.player.getYaw());
            Vec3d f = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
            Vec3d r = new Vec3d(Math.cos(rad), 0, Math.sin(rad));
            if (mc.options.forwardKey.isPressed()) { mx += f.x; mz += f.z; }
            if (mc.options.backKey.isPressed()) { mx -= f.x; mz -= f.z; }
            if (mc.options.rightKey.isPressed()) { mx += r.x; mz += r.z; }
            if (mc.options.leftKey.isPressed()) { mx -= r.x; mz -= r.z; }
            double vy = mc.options.jumpKey.isPressed() ? 0.42 : v.getVelocity().y;
            if (mx != 0 || mz != 0) v.setVelocity(mx * 0.4, vy, mz * 0.4);
            v.setYaw(mc.player.getYaw());
        }

        // slippy: amplify horizontal velocity on ground
        if (slippy.get() && mc.player.isOnGround()) {
            Vec3d vel = mc.player.getVelocity();
            mc.player.setVelocity(vel.x * slipAmount.get(), vel.y, vel.z * slipAmount.get());
        }

        // reverse-step: if walking off a 1-block ledge, drop straight down instead of arcing
        if (reverseStep.get() && mc.player.isOnGround() && !mc.player.isSneaking()) {
            Vec3d vel = mc.player.getVelocity();
            if ((vel.x != 0 || vel.z != 0)) {
                var below = mc.player.getBlockPos().down();
                if (mc.world != null && mc.world.getBlockState(below).getCollisionShape(mc.world, below).isEmpty()) {
                    // small assisted descend
                    mc.player.setVelocity(vel.x, -0.3, vel.z);
                }
            }
        }
    }

    private void force(KeyBinding kb) {
        try {
            int code = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey()).getCode();
            kb.setPressed(org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getHandle(), code) == org.lwjgl.glfw.GLFW.GLFW_PRESS);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDeactivate() {
        if (mc.options == null) return;
        for (KeyBinding k : new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey, mc.options.jumpKey, mc.options.sneakKey})
            k.setPressed(false);
    }

    @Override public String getInfoString() {
        StringBuilder b = new StringBuilder();
        if (guiMove.get()) b.append("gui ");
        if (entityControl.get()) b.append("control ");
        if (slippy.get()) b.append("slip ");
        if (reverseStep.get()) b.append("rstep");
        return b.length() == 0 ? "off" : b.toString().trim();
    }
}
