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

import java.util.concurrent.ThreadLocalRandom;

/**
 * Y-Level Spoofer — sends position packets with a fake Y while your real position
 * stays put. Most anti-cheats flag any of this; it's for lenient/vanilla servers
 * and testing. Two independent axes:
 *
 *   timing  (Burst / Constant) — flicker once, or keep spoofing every tick.
 *   pattern (how the fake Y is chosen each packet):
 *     Fixed     - exactly y-level (the classic single-value spoof).
 *     Oscillate - sine-wave around y-level by +/- spread, so it's never a static
 *                 value a check can lock onto.
 *     Jitter    - y-level plus a random offset within +/- spread each packet.
 *     Descend   - start at your real Y and walk downward by spread each tick,
 *                 so the reported position sinks smoothly instead of teleporting.
 */
public class YLevelSpoofer extends Module {
    public enum Mode { Burst, Constant }
    public enum Pattern { Fixed, Oscillate, Jitter, Descend }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("timing")
        .description("Burst = flicker once then turn off. Constant = keep spoofing until you toggle it off.")
        .defaultValue(Mode.Burst)
        .build()
    );

    private final Setting<Pattern> pattern = sgGeneral.add(new EnumSetting.Builder<Pattern>()
        .name("pattern")
        .description("How the fake Y is chosen: a fixed value, an oscillating wave, random jitter, or a smooth descent.")
        .defaultValue(Pattern.Fixed)
        .build()
    );

    private final Setting<Double> y = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-level")
        .description("Target Y level (Fixed/Oscillate/Jitter). Goes to the world floor and below.")
        .defaultValue(-10)
        .min(-512)
        .sliderRange(-64, 320)
        .visible(() -> pattern.get() != Pattern.Descend)
        .build()
    );

    private final Setting<Double> spread = sgGeneral.add(new DoubleSetting.Builder()
        .name("spread")
        .description("Oscillate: wave amplitude. Jitter: max random offset. Descend: blocks dropped per tick.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 32)
        .visible(() -> pattern.get() != Pattern.Fixed)
        .build()
    );

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets")
        .description("Burst mode: how many spoofed packets to send before disabling.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> mode.get() == Mode.Burst)
        .build()
    );

    private int sent;
    private double phase;      // Oscillate wave position
    private double descendY;   // Descend running Y

    public YLevelSpoofer() {
        super(shama.addon.ShamaAddon.PLAYER, "y-level-spoof++", "RISKY: Tries to fake your height to the server without moving you. Most anti-cheats will catch this.");
    }

    @Override
    public void onActivate() {
        sent = 0;
        phase = 0;
        descendY = mc.player != null ? mc.player.getY() : y.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        double spoofY = switch (pattern.get()) {
            case Fixed -> y.get();
            case Oscillate -> {
                phase += 0.4;
                yield y.get() + Math.sin(phase) * spread.get();
            }
            case Jitter -> y.get() + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * spread.get();
            case Descend -> {
                descendY -= spread.get();
                yield descendY;
            }
        };

        // Real X/Z, spoofed Y. Doesn't move the actual player.
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            mc.player.getX(), spoofY, mc.player.getZ(),
            mc.player.getYaw(), mc.player.getPitch(),
            mc.player.isOnGround(), mc.player.horizontalCollision
        ));

        sent++;
        if (mode.get() == Mode.Burst && sent >= packets.get()) toggle();
    }

    @Override
    public String getInfoString() {
        String p = pattern.get().name().toLowerCase();
        if (mode.get() == Mode.Constant) return p;
        return p + " " + sent + "/" + packets.get();
    }
}
