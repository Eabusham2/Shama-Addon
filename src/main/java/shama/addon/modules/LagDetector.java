package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

/**
 * Low TPS Alert — estimates server TPS from WorldTimeUpdate packet timing (vanilla sends one
 * every 20 ticks, so the real gap reveals the tick rate). On a regionized server like Folia
 * each 32x32-chunk region ticks on its own thread, so a low reading means YOUR CURRENT REGION
 * is lagging — i.e. there's a heavy base/farm in it. When TPS stays below your threshold long
 * enough it fires a title popup, a chat line and a sound, and boxes the exact 32x32 region
 * you're standing in, in blue that darkens the lower the TPS gets.
 */
public class LagDetector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgR = settings.createGroup("Region Highlight");

    private final Setting<Double> threshold = sg.add(new DoubleSetting.Builder()
        .name("tps-threshold").description("Alert when estimated server TPS drops below this.").defaultValue(15.0).min(1).max(20).sliderRange(5, 20).decimalPlaces(1).build());
    private final Setting<Integer> durationSeconds = sg.add(new IntSetting.Builder()
        .name("trigger-seconds").description("How many seconds TPS must stay low before alerting.").defaultValue(5).min(1).max(60).sliderRange(1, 30).build());
    private final Setting<Integer> cooldownSeconds = sg.add(new IntSetting.Builder()
        .name("re-alert-cooldown").description("Minimum seconds between repeat alerts.").defaultValue(200).min(1).max(300).sliderRange(5, 120).build());

    private final Setting<Boolean> popup = sg.add(new BoolSetting.Builder().name("popup").description("Show an on-screen title popup.").defaultValue(true).build());
    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder().name("chat").description("Send a chat message.").defaultValue(true).build());
    private final Setting<Boolean> sound = sg.add(new BoolSetting.Builder().name("sound").description("Play an alert sound.").defaultValue(true).build());

    private final Setting<Boolean> watchTps = sg.add(new BoolSetting.Builder()
        .name("server-tps")
        .description("Watch how fast the server is ticking. A sustained drop is the clearest sign something nearby is working it hard — usually a farm.")
        .defaultValue(true).build());

    private final Setting<Boolean> watchFps = sg.add(new BoolSetting.Builder()
        .name("your-fps")
        .description("Watch your own framerate as well. Some farms flood you with entities and particles rather than costing the server much, so your frames tank while the tick rate looks fine.")
        .defaultValue(false).build());
    private final Setting<Integer> fpsThreshold = sg.add(new IntSetting.Builder()
        .name("fps-below")
        .description("Framerate that counts as struggling. Compare it against what you normally get, not a fixed idea of good.")
        .defaultValue(40).min(5).max(240).sliderRange(15, 120).visible(watchFps::get).build());

    private final Setting<Boolean> watchOnePercent = sg.add(new BoolSetting.Builder()
        .name("worst-frames")
        .description("Watch the worst one percent of frames rather than the average. Entity-heavy farms cause hitching that a steady average hides completely — this is what catches those.")
        .defaultValue(false).build());
    private final Setting<Integer> onePercentDrop = sg.add(new IntSetting.Builder()
        .name("worst-frames-below")
        .description("How far below your normal framerate the worst frames must fall, as a percent. 50 means a stutter down to half your usual rate counts.")
        .defaultValue(50).min(10).max(95).sliderRange(20, 80).visible(watchOnePercent::get).build());

    private final SettingGroup sgAdvanced = settings.createGroup("Already Loaded");
    private final Setting<Boolean> alreadyLoaded = sgAdvanced.add(new BoolSetting.Builder()
        .name("already-loaded")
        .description("Flag areas the server already had in memory before you got there. A chunk it has to read from disk or generate takes real time to send; one somebody is keeping alive comes back almost instantly. A burst of instant arrivals means a chunk loader, a farm, or someone living there — and it works even when nothing is visible.")
        .defaultValue(false).build());
    private final Setting<Integer> instantMs = sgAdvanced.add(new IntSetting.Builder()
        .name("instant-threshold")
        .description("A chunk arriving within this many milliseconds of the one before it counts as already loaded. Lower is stricter.")
        .defaultValue(3).min(1).max(50).sliderRange(1, 20).visible(alreadyLoaded::get).build());
    private final Setting<Integer> instantRun = sgAdvanced.add(new IntSetting.Builder()
        .name("instant-run")
        .description("How many instant arrivals in a row before it's reported. A couple happen naturally; a long run does not.")
        .defaultValue(24).min(4).max(200).sliderRange(8, 80).visible(alreadyLoaded::get).build());

    private final Setting<Boolean> watchAnywhere = sg.add(new BoolSetting.Builder()
        .name("check-anywhere")
        .description("Keep checking wherever you are, not just while chunks are streaming in. Leave this on if you want to find farms by standing near them rather than only noticing lag as you fly past.")
        .defaultValue(true).build());

    private final Setting<Double> settleSeconds = sg.add(new DoubleSetting.Builder()
        .name("settle-time")
        .description("Extra wait after the last chunk lands, on top of the loaded check above. The client keeps building chunk meshes for a moment after the data arrives, and that costs frames — this rides it out so the counter starts on a settled world.")
        .defaultValue(1.0).min(0).max(30).sliderRange(0, 10).decimalPlaces(1).build());

    private final Setting<Double> chunkDelay = sg.add(new DoubleSetting.Builder()
        .name("chunk-delay")
        .description("Hold chunks you are flying towards for this long before letting them load. Flying forward loads the next chunks constantly, and every arrival restarts the settle timer, so a reading never lasts long enough to report. Delaying them lets the timer run out first, then they all load at once. 0 lets everything through immediately.")
        .defaultValue(0.0).min(0).max(10).sliderRange(0, 3).decimalPlaces(1).build());

    private final Setting<Integer> readyRadius = sg.add(new IntSetting.Builder()
        .name("ready-radius")
        .description("How many chunks around you must be loaded before readings count. This is what tells the module the world has actually finished arriving, rather than assuming a fixed loading time.")
        .defaultValue(3).min(1).max(8).sliderRange(1, 6).build());

    private final Setting<Integer> minSeconds = sg.add(new IntSetting.Builder()
        .name("min-duration")
        .description("How long the problem has to last before it's reported. Short spikes happen constantly and mean nothing; something sustained means something is actually there.")
        .defaultValue(8).min(1).max(300).sliderRange(3, 60).build());

    private final Setting<Boolean> ignoreClientLag = sg.add(new BoolSetting.Builder()
        .name("ignore-client-lag").description("Throw away TPS samples taken while your own game was stuttering. Without this, your own framerate dips look exactly like server lag and cause false alerts.")
        .defaultValue(true).build());
    private final Setting<Double> smoothing = sg.add(new DoubleSetting.Builder()
        .name("smoothing").description("How heavily to average the TPS estimate. Higher is steadier but slower to react; low values react fast but flicker.")
        .defaultValue(0.3).min(0.05).max(1.0).sliderRange(0.1, 0.8).decimalPlaces(2).build());

    private final Setting<Boolean> highlight = sgR.add(new BoolSetting.Builder()
        .name("highlight-region").description("While TPS is low, box the 32x32 chunk region you're standing in (the lagging region).").defaultValue(true).build());
    private final Setting<Integer> boxSeconds = sgR.add(new IntSetting.Builder()
        .name("box-after-seconds").description("Only draw the region box once TPS has stayed low for this long. Stops the box flashing on brief dips.")
        .defaultValue(10).min(1).max(120).sliderRange(3, 60).visible(highlight::get).build());
    private final Setting<Integer> regionChunks = sgR.add(new IntSetting.Builder()
        .name("region-size").description("Region size in chunks (Folia default here is 32).").defaultValue(32).min(1).max(64).sliderRange(8, 32).build());

    private double tps = -1;
    private long lastChunkArrived;      // chunks still streaming means readings can't be trusted
    private String reason = "";
    private long lastClientTick;          // used to tell a client freeze apart from real server lag
    private int clientStall;              // ticks where OUR frame time was bad, so samples are untrustworthy
    private long lastTimePacket, lowSince, lastAlert;

    public LagDetector() { super(shama.addon.ShamaAddon.MISC, "lag-detector++", "Finds places the server is struggling — a farm, a stash full of hoppers, or anything else eating server time. Watches server tick rate, your own framerate, and how bad the worst frames get."); }

    /** Latest smoothed TPS estimate, or -1 before enough samples. Read by rtp-finder++. */
    public double currentTps() { return tps; }

    @Override public void onActivate() { tps = -1; lastTimePacket = lowSince = lastAlert = lastClientTick = 0; clientStall = 0;
        frameFps.clear(); baselineFps = -1; fpsLowSince = worstLowSince = 0; reason = "";
        frames = 0; fpsWindowStart = 0; measuredFps = -1; lastChunkArrived = 0; heldChunks.clear(); applyingChunks = false; prevChunkAt = 0; instantStreak = 0; }

    /** A chunk packet we are sitting on until the delay is up. */
    private record HeldChunk(net.minecraft.network.packet.Packet<?> packet, long arrived) {}

    private final java.util.ArrayDeque<HeldChunk> heldChunks = new java.util.ArrayDeque<>();
    private boolean applyingChunks;

    private long prevChunkAt;
    private int instantStreak;

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        long now = System.currentTimeMillis();
        if (alreadyLoaded.get() && prevChunkAt > 0) {
            // chunks the server had in memory come back one after another with almost no gap;
            // anything it must read from disk or generate cannot keep that pace up
            if (now - prevChunkAt <= instantMs.get()) {
                if (++instantStreak == instantRun.get()) {
                    reason = instantStreak + " chunks came back instantly — this area was already loaded";
                    if (System.currentTimeMillis() - lastAlert >= cooldownSeconds.get() * 1000L) {
                        fireAlert();
                        lastAlert = System.currentTimeMillis();
                    }
                }
            } else instantStreak = 0;
        }
        prevChunkAt = now;
        lastChunkArrived = now;
    }

    /**
     * Hold incoming chunk data back so the settle timer gets a clean run.
     *
     * Flying forward means the next chunks arrive constantly and each one restarts the timer, so a
     * measurement never survives long enough to report. Sitting on them lets the timer finish, then
     * they are all applied together.
     */
    @EventHandler
    private void onChunkPacket(PacketEvent.Receive event) {
        if (applyingChunks || chunkDelay.get() <= 0) return;
        if (!(event.packet instanceof net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket)) return;
        heldChunks.addLast(new HeldChunk(event.packet, System.currentTimeMillis()));
        event.cancel();
    }

    /** Let through any chunks that have waited long enough. Runs on the main thread. */
    private void flushHeldChunks() {
        if (mc.getNetworkHandler() == null) { heldChunks.clear(); return; }
        long now = System.currentTimeMillis();
        long wait = (long) (chunkDelay.get() * 1000);
        applyingChunks = true;
        try {
            while (!heldChunks.isEmpty()) {
                HeldChunk h = heldChunks.peekFirst();
                if (now - h.arrived() < wait) break;      // oldest first, so nothing behind is ready either
                heldChunks.pollFirst();
                try { ((net.minecraft.network.packet.Packet) h.packet()).apply(mc.getNetworkHandler()); } catch (Throwable ignored) {}
            }
        } finally { applyingChunks = false; }
    }

    /**
     * True while the world around you is still filling in.
     *
     * Two things have to be satisfied. First the ring of chunks around you must actually be loaded —
     * that is the real "done" signal, not a guess at how long loading takes. Then the settle timer
     * runs from the last chunk to arrive, because the client is still building meshes for a moment
     * after the data lands and that costs frames too.
     *
     * The counter only starts once both are true, so what gets measured is the place itself rather
     * than the cost of getting there.
     */
    private boolean stillLoading() {
        if (mc.world == null || mc.player == null) return true;

        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        int r = Math.max(1, Math.min(readyRadius.get(), 8));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) return true;   // still gaps
            }
        }

        if (lastChunkArrived == 0) return false;                 // nothing has arrived at all yet
        return System.currentTimeMillis() - lastChunkArrived < (long) (settleSeconds.get() * 1000);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof WorldTimeUpdateS2CPacket)) return;
        long now = System.currentTimeMillis();
        // If our own client stalled, the packet looks late even though the server was fine.
        // Skipping those samples is what stops framerate dips reading as server lag.
        if (ignoreClientLag.get() && clientStall > 0) { lastTimePacket = now; return; }
        // when check-anywhere is off, only bother measuring around freshly loaded ground
        if (!watchAnywhere.get() && lastChunkArrived == 0) return;
        if (stillLoading()) { lastTimePacket = now; return; }   // mid-load samples say nothing about the place
        if (lastTimePacket > 0) {
            long dt = now - lastTimePacket;
            if (dt > 0) {
                double instant = Math.min(20.0, 20000.0 / dt);   // 20 ticks per packet
                double a = smoothing.get();
                tps = tps < 0 ? instant : tps * (1.0 - a) + instant * a;
            }
        }
        lastTimePacket = now;
    }

    /** Rolling frame times, newest last, used for the average and the worst-one-percent figure. */
    private final java.util.ArrayDeque<Double> frameFps = new java.util.ArrayDeque<>();
    private double baselineFps = -1;
    private long fpsLowSince, worstLowSince;

    /**
     * Framerate measured by counting our own render calls. Reading it from the client would mean
     * relying on a method name that may not survive a mapping change, and this is exact anyway.
     */
    private int frames;
    private long fpsWindowStart;
    private int measuredFps = -1;

    @EventHandler
    private void onFrame(meteordevelopment.meteorclient.events.render.Render3DEvent event) {
        frames++;
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) { fpsWindowStart = now; return; }
        if (now - fpsWindowStart >= 500) {                       // twice a second is plenty
            measuredFps = (int) (frames * 1000L / (now - fpsWindowStart));
            frames = 0; fpsWindowStart = now;
        }
    }

    private void sampleFps() {
        int fps = measuredFps;
        if (fps <= 0) return;
        frameFps.addLast((double) fps);
        while (frameFps.size() > 600) frameFps.pollFirst();     // about half a minute
        // baseline drifts slowly upward so a long stint near a farm doesn't become "normal"
        baselineFps = baselineFps < 0 ? fps : Math.max(baselineFps * 0.999, fps * 0.02 + baselineFps * 0.98);
    }

    /** The worst one percent of recent frames — what you feel as stutter. */
    private double worstOnePercent() {
        if (frameFps.size() < 60) return -1;
        java.util.List<Double> sorted = new java.util.ArrayList<>(frameFps);
        java.util.Collections.sort(sorted);
        int n = Math.max(1, sorted.size() / 100);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += sorted.get(i);
        return sum / n;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        flushHeldChunks();
        sampleFps();
        // A client tick should land every ~50ms. If ours took far longer, WE were the lag source,
        // so distrust TPS samples for a moment afterwards.
        long tnow = System.currentTimeMillis();
        if (lastClientTick > 0 && tnow - lastClientTick > 150) clientStall = 20;
        else if (clientStall > 0) clientStall--;
        lastClientTick = tnow;
        if (mc.world == null || mc.player == null) return;
        // when check-anywhere is off, only bother measuring around freshly loaded ground
        if (!watchAnywhere.get() && lastChunkArrived == 0) return;
        if (stillLoading()) {
            // chunks are still coming in; the lag is the loading, not the place
            lowSince = 0; fpsLowSince = 0; worstLowSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        // your framerate, steady drop
        if (watchFps.get()) {
            int fps = measuredFps;
            if (fps > 0 && fps < fpsThreshold.get()) {
                if (fpsLowSince == 0) fpsLowSince = now;
                if (now - fpsLowSince >= minSeconds.get() * 1000L && now - lastAlert >= cooldownSeconds.get() * 1000L) {
                    reason = String.format("your framerate is %d", fps);
                    fireAlert(); lastAlert = now;
                }
            } else fpsLowSince = 0;
        }

        // worst frames: stutter that an average hides
        if (watchOnePercent.get() && baselineFps > 0) {
            double worst = worstOnePercent();
            double floor = baselineFps * (1.0 - onePercentDrop.get() / 100.0);
            if (worst > 0 && worst < floor) {
                if (worstLowSince == 0) worstLowSince = now;
                if (now - worstLowSince >= minSeconds.get() * 1000L && now - lastAlert >= cooldownSeconds.get() * 1000L) {
                    reason = String.format("stuttering — worst frames at %.0f against a normal %.0f", worst, baselineFps);
                    fireAlert(); lastAlert = now;
                }
            } else worstLowSince = 0;
        }

        if (watchTps.get() && tps >= 0 && tps < threshold.get()) {
            if (lowSince == 0) lowSince = now;
            if (now - lowSince >= Math.max(durationSeconds.get(), minSeconds.get()) * 1000L && now - lastAlert >= cooldownSeconds.get() * 1000L) {
                reason = String.format("server tick rate is %.1f", tps);
                fireAlert();
                lastAlert = now;
            }
        } else {
            lowSince = 0;
        }
    }

    private void fireAlert() {
        if (chat.get()) shama.addon.util.Chat.warning("[LagDetector] %s — something here is loading the server", reason);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 30, 8);
            mc.inGameHud.setTitle(Text.literal("Lag here").formatted(Formatting.RED));
            mc.inGameHud.setSubtitle(Text.literal(reason).formatted(Formatting.YELLOW));
        }
        if (sound.get() && mc.getSoundManager() != null) {
            try {
                if (mc.player != null) mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.6f);
            } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!highlight.get() || lowSince == 0 || System.currentTimeMillis() - lowSince < boxSeconds.get() * 1000L || mc.player == null || mc.world == null) return;
        if (tps < 0 || tps >= threshold.get()) return; // only while this region is lagging

        int size = regionChunks.get();
        int rcx = Math.floorDiv(mc.player.getChunkPos().x, size); // region index containing the player
        int rcz = Math.floorDiv(mc.player.getChunkPos().z, size);
        double x0 = rcx * size * 16.0, z0 = rcz * size * 16.0;
        double x1 = x0 + size * 16.0, z1 = z0 + size * 16.0;
        double bottom = mc.world.getBottomY(), top = mc.world.getTopYInclusive() + 1;

        double sev = MathHelper.clamp((threshold.get() - tps) / threshold.get(), 0, 1); // 0 at threshold -> 1 at 0 TPS
        Color line = new Color(40, (int) (140 - 110 * sev), (int) (255 - 120 * sev), 255); // darker/deeper blue the lower the TPS
        event.renderer.box(x0, bottom, z0, x1, top, z1, new Color(0, 0, 0, 0), line, ShapeMode.Lines, 0);
    }

    @Override
    public String getInfoString() { return tps < 0 ? "--" : String.format("%.1f TPS", tps); }
}
