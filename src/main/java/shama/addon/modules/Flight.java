package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.Vec3d;

/**
 * Flight with several modes, each using a different mechanic so they get past
 * different checks. None of these are magic on a strict anti-cheat — they're
 * different trade-offs between smoothness and how obvious they are.
 *
 *  - Creative   : flips the vanilla creative-flight ability. Smoothest. A server
 *                 that tracks the flight ability sees it directly.
 *  - Velocity   : never touches the ability flag; zeroes gravity and sets your
 *                 velocity each tick from input. Looks like normal movement with
 *                 no fall.
 *  - Motion     : like Velocity but only nudges Y to cancel gravity (small
 *                 upward motion each tick) instead of forcing it to zero, so the
 *                 vertical movement reads more like vanilla jitter.
 *  - Glide      : no upward force at all — you slowly sink unless you hold jump.
 *                 The gentlest profile; good for staying near the ground without
 *                 ever triggering a "flying" state.
 */
public class Flight extends Module {
    public enum Mode {
        Creative,
        Velocity,
        Motion,
        Glide,
        Jetpack,
        Bounce,
        Smooth,
        Static,
        Packet,
        Vanilla
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Flight method, each bypassing differently. Creative flips the ability flag; Velocity/Motion/Glide drive velocity; Bounce adds a hover wobble; Smooth eases momentum; Static is a dead-stop hover; Packet reports on-ground; Jetpack thrusts where you look.")
        .defaultValue(Mode.Creative)
        .build()
    );

    private final Setting<Boolean> antiKick = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-kick")
        .description("Periodically send a fake on-ground packet so servers that kick on prolonged airtime (rather than checking velocity) don't flag you. Independent of mode.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> antiKickInterval = sgGeneral.add(new IntSetting.Builder()
        .name("anti-kick-interval")
        .description("Ticks between the fake on-ground reports.")
        .defaultValue(60).min(10).sliderRange(10, 200)
        .visible(antiKick::get)
        .build()
    );

    private int antiKickTimer;

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal flight speed (blocks/tick) for the non-creative modes.")
        .defaultValue(0.5)
        .min(0.0)
        .sliderRange(0.1, 3.0)
        .build()
    );

    private final Setting<Double> vertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Up/down speed for jump/sneak (non-creative modes).")
        .defaultValue(0.5)
        .min(0.0)
        .sliderRange(0.1, 3.0)
        .build()
    );

    private final Setting<Double> flySpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("creative-fly-speed")
        .description("Fly speed for Creative mode (1.0 = vanilla default).")
        .defaultValue(1.0)
        .min(0.0)
        .sliderRange(0.1, 5.0)
        .build()
    );

    private final Setting<Integer> pulldownInterval = sgGeneral.add(new IntSetting.Builder()
        .name("vanilla-pulldown-interval")
        .description("Vanilla mode: every N ticks, drop briefly so the server's \"time in air\" counter resets. This is the NCP-style trick that gets past anticheats that kick for being airborne too long. Lower = safer but jerkier.")
        .defaultValue(8)
        .min(2)
        .sliderRange(2, 40)
        .visible(() -> mode.get() == Mode.Vanilla)
        .build()
    );

    private boolean prevAllowFlying;
    private boolean prevFlying;
    private int animTick;
    private Vec3d smoothVel = Vec3d.ZERO;

    public Flight() {
        super(shama.addon.ShamaAddon.MOVEMENT, "flight++", "Fly freely. How well it holds up depends on the server's anti-cheat.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        prevAllowFlying = mc.player.getAbilities().allowFlying;
        prevFlying = mc.player.getAbilities().flying;
        smoothVel = mc.player.getVelocity();

        if (mode.get() == Mode.Creative) {
            mc.player.getAbilities().allowFlying = true;
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        if (!prevAllowFlying) {
            mc.player.getAbilities().allowFlying = false;
            mc.player.getAbilities().flying = false;
        } else {
            mc.player.getAbilities().allowFlying = prevAllowFlying;
            mc.player.getAbilities().flying = prevFlying;
        }
        mc.player.getAbilities().setFlySpeed(0.05f);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (antiKick.get() && mc.getNetworkHandler() != null) {
            if (++antiKickTimer >= antiKickInterval.get()) {
                antiKickTimer = 0;
                mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
            }
        }

        if (mode.get() == Mode.Creative) {
            // Let vanilla's double-tap-jump start/stop it; just permit + set speed.
            mc.player.getAbilities().allowFlying = true;
            mc.player.getAbilities().setFlySpeed((float) (0.05 * flySpeed.get()));
            return;
        }

        // --- Shared input-driven horizontal movement for the other modes ---
        double mx = 0, mz = 0;
        boolean f = mc.options.forwardKey.isPressed();
        boolean b = mc.options.backKey.isPressed();
        boolean l = mc.options.leftKey.isPressed();
        boolean r = mc.options.rightKey.isPressed();

        if (f || b || l || r) {
            float yaw = (float) Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            double forward = (f ? 1 : 0) - (b ? 1 : 0);
            double strafe = (r ? 1 : 0) - (l ? 1 : 0);
            mx = (forward * -sin + strafe * cos);
            mz = (forward * cos + strafe * sin);
            double len = Math.sqrt(mx * mx + mz * mz);
            if (len > 0) {
                mx = mx / len * speed.get();
                mz = mz / len * speed.get();
            }
        }

        boolean jump = mc.options.jumpKey.isPressed();
        boolean sneak = mc.options.sneakKey.isPressed();
        animTick++;

        switch (mode.get()) {
            case Velocity -> {
                // Hard zero gravity, full manual control.
                mc.player.setNoGravity(true);
                double up = (jump ? vertical.get() : 0) - (sneak ? vertical.get() : 0);
                mc.player.setVelocity(mx, up, mz);
                mc.player.setOnGround(false);
            }
            case Motion -> {
                // Don't force noGravity; instead counter gravity with a small
                // per-tick upward nudge so vertical reads less robotic.
                mc.player.setNoGravity(false);
                double up;
                if (jump) up = vertical.get();
                else if (sneak) up = -vertical.get();
                else up = 0.08; // ~cancels one tick of gravity (0.08/tick)
                mc.player.setVelocity(mx, up, mz);
                mc.player.fallDistance = 0;
            }
            case Glide -> {
                // No upward force unless you hold jump; otherwise gentle sink.
                mc.player.setNoGravity(false);
                double up;
                if (jump) up = vertical.get();
                else if (sneak) up = -vertical.get();
                else up = -0.04; // slow descent
                mc.player.setVelocity(mx, up, mz);
                mc.player.fallDistance = 0;
            }
            case Jetpack -> {
                // Horion-style: thrust straight toward where you're looking
                // (pitch included, so look up to climb, look down to dive).
                // Meant to be bound as hold-to-fly: release the bind to stop.
                mc.player.setNoGravity(true);
                Vec3d look = mc.player.getRotationVector(); // normalized look dir
                double s = speed.get();
                mc.player.setVelocity(look.x * s, look.y * s, look.z * s);
                mc.player.setOnGround(false);
                mc.player.fallDistance = 0;
            }
            case Bounce -> {
                // Like Velocity but the resting vertical isn't a flat line — it
                // rides a small sine wave so checks that flag "perfectly constant
                // Y while airborne" have less to grab onto.
                mc.player.setNoGravity(true);
                double up;
                if (jump) up = vertical.get();
                else if (sneak) up = -vertical.get();
                else up = 0.045 * Math.sin(animTick * 0.5); // gentle hover wobble
                mc.player.setVelocity(mx, up, mz);
                mc.player.setOnGround(false);
                mc.player.fallDistance = 0;
            }
            case Smooth -> {
                // Eases velocity toward the target instead of snapping, so starts
                // and stops aren't instant (reads more like real momentum).
                mc.player.setNoGravity(true);
                double up = (jump ? vertical.get() : 0) - (sneak ? vertical.get() : 0);
                Vec3d target = new Vec3d(mx, up, mz);
                smoothVel = smoothVel.add(target.subtract(smoothVel).multiply(0.25)); // lerp 25%/tick
                mc.player.setVelocity(smoothVel);
                mc.player.setOnGround(false);
                mc.player.fallDistance = 0;
            }
            case Static -> {
                // Dead hover: no drift at all. Only moves while you hold a key,
                // otherwise pinned exactly in place. Good for AFK / building.
                mc.player.setNoGravity(true);
                double up = (jump ? vertical.get() : 0) - (sneak ? vertical.get() : 0);
                mc.player.setVelocity(mx, up, mz);
                if (mx == 0 && mz == 0 && up == 0) mc.player.setVelocity(0, 0, 0);
                mc.player.setOnGround(false);
                mc.player.fallDistance = 0;
            }
            case Packet -> {
                // Manual flight like Velocity, but reports onGround=true. Simple
                // "are you airborne and moving up" checks see a grounded player;
                // strict anti-cheats that reconcile position won't be fooled.
                mc.player.setNoGravity(true);
                double up = (jump ? vertical.get() : 0) - (sneak ? vertical.get() : 0);
                mc.player.setVelocity(mx, up, mz);
                mc.player.setOnGround(true);
                mc.player.fallDistance = 0;
            }
            case Vanilla -> {
                // The classic NCP-style bypass: fly at a near-vanilla speed and,
                // every pulldown-interval ticks, briefly drop so the server's
                // "ticks airborne" counter resets and never trips the "flying too
                // long" kick. The mode most likely to survive on NCP-based servers
                // (like 2b2t) — but speed has to stay low or it still flags.
                mc.player.setNoGravity(true);
                double up;
                if (jump) up = vertical.get();
                else if (sneak) up = -vertical.get();
                else if (animTick % Math.max(2, pulldownInterval.get()) == 0) up = -0.062; // reset air-ticks
                else up = 0.0;
                // Cap horizontal near vanilla sprint-fly so it doesn't trip speed checks.
                double cap = Math.min(speed.get(), 0.34);
                double len = Math.sqrt(mx * mx + mz * mz);
                if (len > cap && len > 0) { mx = mx / len * cap; mz = mz / len * cap; }
                mc.player.setVelocity(mx, up, mz);
                mc.player.fallDistance = 0;
            }
            default -> {}
        }
    }

    @Override
    public String getInfoString() {
        return mode.get().name().toLowerCase();
    }
}
