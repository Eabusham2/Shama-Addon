package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

/**
 * Timer++ — Horion-style timer. Client-clock modes scale the tick loop via
 * RenderTickCounterMixin (which reads multiplier() + clientClock()).
 *
 * Modes:
 *   Constant - steady client-clock multiplier.
 *   Pulse    - alternate multiplier / normal on a cycle (bursty signature).
 *   Ramp     - ease from normal up to the multiplier (no instant jump).
 *   Direct   - scale the clock AND flush extra move packets each tick (resends of your
 *              current position: raises packet RATE, loudest anti-cheat signal).
 *   Packets  - extra move packets only, no clock scaling (no visual speed-up).
 *   Advance  - like Direct, but the extra packets step FORWARD along your velocity
 *              instead of resending the same spot, so the server sees you actually
 *              moving ahead (more teleport-like reach; even louder to anti-cheat).
 *   Smart    - like Direct, but only spams extra packets while you're actually doing
 *              something (attacking, using/eating, mining). When idle it sends the
 *              normal rate and doesn't scale the clock, so there's far less to kick for.
 *
 * Honest scope: full effect in singleplayer; on a server you out-pace real time briefly
 * but the server runs 20 TPS and strict anti-cheats flag the speed/packet rate.
 */
public class Timer extends Module {
    public enum Mode { Constant, Pulse, Ramp, Direct, Packets, Advance, Smart }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Constant/Pulse/Ramp scale the client clock. Direct adds packet spam. Packets is spam-only. Advance sends forward-stepped positions. Smart only spams while you're acting.")
        .defaultValue(Mode.Constant)
        .build()
    );

    private final Setting<Double> multiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("multiplier")
        .description("Game-speed multiplier for the client-clock modes. >1 faster, <1 slower, 1 = normal.")
        .defaultValue(2.0)
        .min(0.1)
        .sliderRange(0.1, 10.0)
        .visible(() -> mode.get() != Mode.Packets)
        .build()
    );

    private final Setting<Integer> extraPackets = sgGeneral.add(new IntSetting.Builder()
        .name("extra-packets")
        .description("Direct/Packets/Advance/Smart: extra movement packets per tick (per acting-tick for Smart). Higher = more aggressive, more kick risk.")
        .defaultValue(2).min(1).sliderRange(1, 20)
        .visible(() -> { Mode m = mode.get(); return m == Mode.Direct || m == Mode.Packets || m == Mode.Advance || m == Mode.Smart; })
        .build()
    );

    private final Setting<Integer> pulseOn = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-on").description("Pulse: ticks at the multiplier each cycle.")
        .defaultValue(10).min(1).sliderRange(1, 40).visible(() -> mode.get() == Mode.Pulse).build());

    private final Setting<Integer> pulseOff = sgGeneral.add(new IntSetting.Builder()
        .name("pulse-off").description("Pulse: ticks at normal speed each cycle.")
        .defaultValue(10).min(1).sliderRange(1, 40).visible(() -> mode.get() == Mode.Pulse).build());

    private final Setting<Integer> rampTicks = sgGeneral.add(new IntSetting.Builder()
        .name("ramp-ticks").description("Ramp: ticks to ease from normal up to the full multiplier.")
        .defaultValue(20).min(1).sliderRange(1, 100).visible(() -> mode.get() == Mode.Ramp).build());

    private int age; // ticks since activation, drives Pulse/Ramp

    public Timer() {
        super(shama.addon.ShamaAddon.PLAYER, "timer++", "Speeds up or slows down your game clock, with several styles and fine control.");
    }

    @Override
    public void onActivate() {
        age = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        age++;
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        Mode m = mode.get();
        boolean sendExtra = m == Mode.Direct || m == Mode.Packets || m == Mode.Advance
            || (m == Mode.Smart && doingThings());
        if (!sendExtra) return;

        int n = extraPackets.get();
        double bx = mc.player.getX(), by = mc.player.getY(), bz = mc.player.getZ();
        Vec3d vel = mc.player.getVelocity();
        boolean advance = m == Mode.Advance;

        for (int i = 1; i <= n; i++) {
            double x = advance ? bx + vel.x * i : bx;
            double y = advance ? by + vel.y * i : by;
            double z = advance ? bz + vel.z * i : bz;
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                x, y, z, mc.player.getYaw(), mc.player.getPitch(),
                mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    /** Actively attacking, using/eating, or mining — the moments Smart is allowed to spam. */
    private boolean doingThings() {
        if (mc.player == null) return false;
        return mc.options.attackKey.isPressed()
            || mc.options.useKey.isPressed()
            || mc.player.isUsingItem();
    }

    /** True when this mode should scale the render/tick clock. */
    public boolean clientClock() {
        Mode m = mode.get();
        if (m == Mode.Packets) return false;      // spam-only, no visual speed-up
        if (m == Mode.Smart) return doingThings(); // only speed the clock while acting
        return true;                               // Constant, Pulse, Ramp, Direct, Advance
    }

    /** Read by RenderTickCounterMixin every frame; may vary with time by mode. */
    public float multiplier() {
        float base = multiplier.get().floatValue();
        return switch (mode.get()) {
            case Constant, Direct, Packets, Advance, Smart -> base;
            case Pulse -> {
                int cycle = Math.max(1, pulseOn.get() + pulseOff.get());
                yield (age % cycle) < pulseOn.get() ? base : 1.0f;
            }
            case Ramp -> {
                int r = Math.max(1, rampTicks.get());
                float t = Math.min(1f, age / (float) r);
                yield 1.0f + (base - 1.0f) * t;
            }
        };
    }

    @Override
    public String getInfoString() {
        Mode m = mode.get();
        if (m == Mode.Packets) return "packets+" + extraPackets.get();
        if (m == Mode.Smart) return "smart" + (doingThings() ? "*" : "");
        return m.name().toLowerCase() + " x" + String.format("%.2f", multiplier());
    }
}
