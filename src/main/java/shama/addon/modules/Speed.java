package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * Speed++ — scales your horizontal velocity up to a target speed in whatever
 * direction you're already moving. This is real movement (no fake AC bypass): the
 * server validates movement, so it works in singleplayer / lenient servers and
 * gets flagged by strict anti-cheats. Vanilla reference: walk ~0.13, sprint ~0.28
 * blocks/tick.
 */
public class Speed extends Module {
    public enum Mode {
        Simple,   // scale current velocity up to target (works on ground or air)
        Bhop,     // auto-jump while moving to chain sprint-jumps for ground speed
        Strafe,   // accelerate by aligning velocity to your input each tick
        Sprint,   // force-sprint + scale; mimics legit sprint movement
        YPort,    // hop a tiny amount each tick so ground-friction speed checks don't apply
        OnGround, // scale speed but report on-ground, to fool simple ground-speed checks
        Hop       // continuous micro-jumps that keep you technically airborne with speed
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How speed is applied, each defeating different checks: Simple (scale velocity), Bhop (auto-jump), Strafe (steer to keys), Sprint (force-sprint + scale), YPort (tiny per-tick hop so ground-friction checks don't apply), OnGround (scale but report grounded), Hop (continuous micro-jumps). Which survives depends on the server's anticheat — try a few.")
        .defaultValue(Mode.Simple)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Target horizontal speed in blocks/tick.")
        .defaultValue(0.4)
        .min(0.0)
        .sliderRange(0.15, 1.5)
        .build()
    );

    private final Setting<Boolean> groundOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("ground-only")
        .description("Only boost while on the ground (less obvious, fewer anti-cheat flags than air-speed).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sprintOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("sprint-only")
        .description("Only boost while you're sprinting.")
        .defaultValue(false)
        .build()
    );

    public Speed() {
        super(shama.addon.ShamaAddon.MOVEMENT, "speed++", "Move faster than normal. How much you can get away with depends on the server's anti-cheat.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (groundOnly.get() && !mc.player.isOnGround()) return;
        if (sprintOnly.get() && !mc.player.isSprinting()) return;

        switch (mode.get()) {
            case Simple -> applyScale();
            case Bhop -> {
                // Auto-hop: the instant you're grounded and moving, jump again, so
                // you chain sprint-jumps. Then scale the horizontal like Simple.
                if (mc.player.isOnGround() && isMoving()) {
                    Vec3d v = mc.player.getVelocity();
                    mc.player.setVelocity(v.x, 0.42, v.z); // vanilla jump impulse
                }
                applyScale();
            }
            case Strafe -> {
                // Steer velocity toward where you're trying to go and push it up to
                // the target speed — gains speed in the air like classic strafing.
                double mx = 0, mz = 0;
                boolean f = mc.options.forwardKey.isPressed();
                boolean b = mc.options.backKey.isPressed();
                boolean l = mc.options.leftKey.isPressed();
                boolean r = mc.options.rightKey.isPressed();
                if (!(f || b || l || r)) return;
                float yaw = (float) Math.toRadians(mc.player.getYaw());
                double sin = Math.sin(yaw), cos = Math.cos(yaw);
                double forward = (f ? 1 : 0) - (b ? 1 : 0);
                double strafe = (r ? 1 : 0) - (l ? 1 : 0);
                mx = forward * -sin + strafe * cos;
                mz = forward * cos + strafe * sin;
                double len = Math.sqrt(mx * mx + mz * mz);
                if (len == 0) return;
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(mx / len * speed.get(), v.y, mz / len * speed.get());
            }
            case Sprint -> {
                // Force the sprint flag on (so the server sees legit sprint state)
                // and scale to target. Reads more like real movement than a raw
                // velocity multiply with no sprint.
                if (isMoving()) mc.player.setSprinting(true);
                applyScale();
            }
            case YPort -> {
                // Bob a hair up/down each tick. While the server thinks you're
                // airborne it often doesn't apply the on-ground friction speed cap,
                // so the horizontal boost passes checks that only gate ground speed.
                if (isMoving()) {
                    Vec3d v = mc.player.getVelocity();
                    double y = mc.player.isOnGround() ? 0.07 : v.y; // little hop off the floor
                    mc.player.setVelocity(v.x, y, v.z);
                }
                applyScale();
            }
            case OnGround -> {
                // Scale, then report grounded. Fools simple checks that only measure
                // speed while you're flagged as in the air.
                applyScale();
                mc.player.setOnGround(true);
            }
            case Hop -> {
                // Continuous low hops: smaller than Bhop so you skim along, staying
                // briefly airborne each cycle while keeping the horizontal boost.
                if (mc.player.isOnGround() && isMoving()) {
                    Vec3d v = mc.player.getVelocity();
                    mc.player.setVelocity(v.x, 0.30, v.z);
                }
                applyScale();
            }
        }
    }

    /** Scale current horizontal momentum up to the target speed (never down). */
    private void applyScale() {
        Vec3d v = mc.player.getVelocity();
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        if (h < 0.06) return;                 // basically standing still
        double scale = speed.get() / h;
        if (scale <= 1.0) return;             // never slow you down
        mc.player.setVelocity(v.x * scale, v.y, v.z * scale);
    }

    private boolean isMoving() {
        return mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
            || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
    }

    @Override
    public String getInfoString() {
        return mode.get().name().toLowerCase() + " " + String.format("%.2f", speed.get());
    }
}
