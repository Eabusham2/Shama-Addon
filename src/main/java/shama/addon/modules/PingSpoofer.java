package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
        .name("fake-lag (risky)")
        .description("Lag EVERYTHING — every packet in both directions is held, not just KeepAlive. This is full blink-style desync: the world freezes and your actions land in a burst when it releases. Off = only spoof the ping number.")
        .defaultValue(false)
        .build()
    );

    private final SettingGroup sgTicks = settings.createGroup("Ticks");
    private final Setting<Boolean> delayOutgoing = sgTicks.add(new BoolSetting.Builder()
        .name("delay-outgoing (risky)").description("Hold packets you send. This is what actually raises your measured ping.")
        .defaultValue(false).build());
    private final Setting<Integer> outgoingTicks = sgTicks.add(new IntSetting.Builder()
        .name("outgoing-ticks").description("How many ticks to hold outgoing packets (20 ticks = 1 second). Used instead of the ms delay when ticks are on.")
        .defaultValue(5).min(0).max(200).sliderRange(0, 60).visible(delayOutgoing::get).build());
    private final Setting<Boolean> delayInbound = sgTicks.add(new BoolSetting.Builder()
        .name("delay-inbound (risky)").description("Also hold packets you receive, so the world updates late too. Makes the lag look symmetrical rather than one-sided.")
        .defaultValue(false).build());
    private final Setting<Integer> inboundTicks = sgTicks.add(new IntSetting.Builder()
        .name("inbound-ticks").description("How many ticks to hold incoming packets before applying them.")
        .defaultValue(5).min(0).max(200).sliderRange(0, 60).visible(delayInbound::get).build());
    private final Setting<Boolean> useTicks = sgTicks.add(new BoolSetting.Builder()
        .name("use-ticks").description("Use the tick counts above instead of the millisecond delay below.")
        .defaultValue(true).build());

    private final Setting<Integer> delayMs = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Baseline hold time (ms). Realistic fluctuation moves around this value. Keep well under ~15s or the server may time you out.")
        .defaultValue(250)
        .min(1)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<Boolean> realistic = sgGeneral.add(new BoolSetting.Builder()
        .name("realistic-fluctuation")
        .description("Make the spoofed ping wander like a real connection instead of sitting on a flat number.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minPercent = sgGeneral.add(new IntSetting.Builder()
        .name("min-percent").description("Lowest the delay may drift to, as a percent of the baseline.")
        .defaultValue(80).min(10).max(100).sliderRange(50, 100).visible(realistic::get).build());
    private final Setting<Integer> maxPercent = sgGeneral.add(new IntSetting.Builder()
        .name("max-percent").description("Highest the delay may drift to, as a percent of the baseline.")
        .defaultValue(120).min(100).max(300).sliderRange(100, 200).visible(realistic::get).build());

    private final Setting<Integer> changePerTick = sgGeneral.add(new IntSetting.Builder()
        .name("change-per-tick")
        .description("How far the delay can randomly move each tick, as a percent of the min-max range. Lower = smoother drift.")
        .defaultValue(20)
        .min(0).max(80)
        .sliderRange(0, 80)
        .visible(realistic::get)
        .build()
    );

    private final java.util.Random rng = new java.util.Random();
    private double currentDelay = -1;

    // Held packet + the time it was queued (ms). One ordered queue preserves
    // send order, which matters when holding all packets in fake-lag mode.
    private record Held(Packet<?> packet, long time) {}

    private final ArrayDeque<Held> held = new ArrayDeque<>();
    private final ArrayDeque<Held> heldIn = new ArrayDeque<>();   // inbound packets waiting to be applied
    private boolean applyingIn;

    // Guard: while we release held packets, sendPacket re-fires PacketEvent.Send;
    // without this flag we'd just re-queue them forever.
    private boolean releasing;

    public PingSpoofer() {
        super(shama.addon.ShamaAddon.MISC, "ping-spoofer++",
            "Raises your measured ping by delaying KeepAlive packets, or delays all packets for fake lag.");
    }

    @Override
    public void onActivate() {
        held.clear();
        heldIn.clear();
        releasing = false;
        currentDelay = -1;
    }

    @Override
    public void onDeactivate() {
        flushAll();
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (releasing) return; // don't re-hold the packets we're releasing

        Packet<?> packet = event.packet;

        boolean hold = (fakeLag.get() && delayOutgoing.get())
            || (delayOutgoing.get() && packet instanceof KeepAliveC2SPacket);
        if (!hold) return;

        held.addLast(new Held(packet, System.currentTimeMillis()));
        event.cancel();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (applyingIn || !delayInbound.get() || !fakeLag.get()) return;   // inbound holding is part of full fake lag
        heldIn.addLast(new Held(event.packet, System.currentTimeMillis()));
        event.cancel();
    }

    /** Apply inbound packets that have waited long enough. Runs on the tick (main) thread. */
    private void flushInbound() {
        if (mc.getNetworkHandler() == null) { heldIn.clear(); return; }
        long now = System.currentTimeMillis();
        long delay = useTicks.get() ? inboundTicks.get() * 50L : (long) effectiveDelay();
        applyingIn = true;
        try {
            while (!heldIn.isEmpty()) {
                Held h = heldIn.peekFirst();
                if (h.time() + delay > now) break;
                heldIn.pollFirst();
                try { ((Packet) h.packet()).apply(mc.getNetworkHandler()); } catch (Throwable ignored) {}
            }
        } finally { applyingIn = false; }
    }

    private double effectiveDelay() {
        return realistic.get() && currentDelay >= 0 ? currentDelay : delayMs.get();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        updateDelay();
        flushDue();
        flushInbound();
    }

    /** Random-walk the effective delay within [min%, max%] of the baseline, moving up to change-per-tick% of the range each tick. */
    private void updateDelay() {
        double base = delayMs.get();
        if (!realistic.get()) { currentDelay = base; return; }
        double lo = base * minPercent.get() / 100.0;
        double hi = base * maxPercent.get() / 100.0;
        if (hi < lo) { double t = lo; lo = hi; hi = t; }
        if (currentDelay < 0) currentDelay = Math.min(Math.max(base, lo), hi); // seed at baseline
        double step = (hi - lo) * changePerTick.get() / 100.0;
        currentDelay += (rng.nextDouble() * 2.0 - 1.0) * step; // randomly add or subtract, up to one step
        if (currentDelay < lo) currentDelay = lo;
        if (currentDelay > hi) currentDelay = hi;
    }

    private void flushDue() {
        if (mc.getNetworkHandler() == null) return;

        long now = System.currentTimeMillis();
        long delay = useTicks.get() ? outgoingTicks.get() * 50L : (long) effectiveDelay();

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
            heldIn.clear();
            return;
        }
        applyingIn = true;
        try { while (!heldIn.isEmpty()) { try { ((Packet) heldIn.pollFirst().packet()).apply(mc.getNetworkHandler()); } catch (Throwable ignored) {} } }
        finally { applyingIn = false; }
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
        long d = useTicks.get() ? outgoingTicks.get() * 50L : (long) effectiveDelay();
        return d + "ms";
    }
}
