package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;

import java.util.ArrayDeque;

/**
 * Ping Spoofer / Fake Lag.
 *
 * Default: delays only outgoing KeepAlive packets, which raises your measured
 * ping (the number the server's tab list shows) without affecting gameplay.
 *
 * Fake-lag mode: holds ALL outgoing packets and releases them after the delay,
 * in order — this is the classic "blink"-style desync (your actions reach the
 * server in a burst after the delay). Verified for 1.21.11.
 */
public class PingSpoofer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fakeLag = sgGeneral.add(new BoolSetting.Builder()
        .name("fake-lag")
        .description("Delay ALL outgoing packets (not just KeepAlive) to simulate real lag. Off = only spoof measured ping.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How long (ms) to hold packets. Keep well under ~15s or the server may time you out.")
        .defaultValue(250)
        .min(1)
        .sliderRange(0, 2000)
        .build()
    );

    // Held packet + the time it was queued (ms). One ordered queue preserves
    // send order, which matters when holding all packets in fake-lag mode.
    private record Held(Packet<?> packet, long time) {}

    private final ArrayDeque<Held> held = new ArrayDeque<>();

    // Guard: while we release held packets, sendPacket re-fires PacketEvent.Send;
    // without this flag we'd just re-queue them forever.
    private boolean releasing;

    public PingSpoofer() {
        super(Categories.Misc, "ping-spoofer",
            "Raises your measured ping by delaying KeepAlive packets, or delays all packets for fake lag.");
    }

    @Override
    public void onActivate() {
        held.clear();
        releasing = false;
    }

    @Override
    public void onDeactivate() {
        flushAll();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (releasing) return; // don't re-hold the packets we're releasing

        Packet<?> packet = event.packet;

        boolean hold = fakeLag.get() || packet instanceof KeepAliveC2SPacket;
        if (!hold) return;

        held.addLast(new Held(packet, System.currentTimeMillis()));
        event.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        flushDue();
    }

    private void flushDue() {
        if (mc.getNetworkHandler() == null) return;

        long now = System.currentTimeMillis();
        long delay = delayMs.get();

        releasing = true;
        try {
            // FIFO: entries are time-ordered, so once the oldest isn't due, stop.
            while (!held.isEmpty()) {
                Held h = held.peekFirst();
                if (h.time() + delay > now) break;
                held.pollFirst();
                mc.getNetworkHandler().sendPacket(h.packet());
            }
        } finally {
            releasing = false;
        }
    }

    private void flushAll() {
        if (mc.getNetworkHandler() == null) {
            held.clear();
            return;
        }
        releasing = true;
        try {
            while (!held.isEmpty()) {
                mc.getNetworkHandler().sendPacket(held.pollFirst().packet());
            }
        } finally {
            releasing = false;
        }
    }

    @Override
    public String getInfoString() {
        return held.size() + " held";
    }
}
