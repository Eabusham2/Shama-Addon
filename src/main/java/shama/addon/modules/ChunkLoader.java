package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Chunk Loader — keeps the server sending you chunks instead of letting them settle.
 *
 * The methods split into two kinds. The polite ones ask the server for data using things a normal
 * client does anyway, so they carry no real risk. The ones marked (risky) tell the server you are
 * somewhere you aren't, which loads far more ground but can read as movement cheating — some
 * anti-cheats will rubber-band or kick for it, occasionally on a false positive.
 *
 * Every method is its own tickbox and they are meant to run together. Servers act on different
 * packets, so the combination is what makes this work in more places than any single one.
 */
public class ChunkLoader extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> rateTicks = sg.add(new IntSetting.Builder()
        .name("rate")
        .description("Ticks between each round of requests. Faster keeps more chunks alive but sends more packets; slower is quieter.")
        .defaultValue(10).min(1).max(100).sliderRange(1, 20).build());

    private final Setting<Boolean> onlyWhenStill = sg.add(new BoolSetting.Builder()
        .name("only-when-still")
        .description("Pause while you're actually walking. Moving already makes the server stream chunks, so this saves traffic when it isn't needed.")
        .defaultValue(false).build());

    // ------------------------------------------------------------ safe: normal client behaviour
    private final SettingGroup sgSafe = settings.createGroup("Safe Methods");




    private final Setting<Boolean> requestChunks = sgSafe.add(new BoolSetting.Builder()
        .name("request-chunks")
        .description("Tell the server a low view distance and then immediately the real one again. It answers by re-sending the true contents of everything that came back into range, which is how you get past a server that fills distant chunks with fake deepslate. Your own render distance never changes — the value is restored in the same tick, so nothing you see is affected and no frames are lost.")
        .defaultValue(false).build());
    private final Setting<Integer> requestEvery = sgSafe.add(new IntSetting.Builder()
        .name("request-every")
        .description("Rounds between each re-request. Every reload costs a burst of traffic, so keep this comfortably above the rate.")
        .defaultValue(60).min(2).max(600).sliderRange(5, 120)
        .visible(requestChunks::get).build());
    private final Setting<Integer> requestLow = sgSafe.add(new IntSetting.Builder()
        .name("request-distance")
        .description("The low value reported to the server during a request. Lower means more chunks get re-sent when the real value is reported again a moment later.")
        .defaultValue(2).min(2).max(12).sliderRange(2, 8)
        .visible(requestChunks::get).build());

    private final Setting<Boolean> viewRefresh = sgSafe.add(new BoolSetting.Builder()
        .name("view-refresh")
        .description("Ask the client to rebuild its terrain, which makes it request anything the server sent but never drew. Purely local — nothing is sent to the server at all, so this is the safest option here.")
        .defaultValue(false).build());
    private final Setting<Integer> refreshEvery = sgSafe.add(new IntSetting.Builder()
        .name("refresh-every")
        .description("Rounds between each rebuild. Rebuilding is not free, so keep this well above the rate.")
        .defaultValue(40).min(5).max(600).sliderRange(10, 200)
        .visible(viewRefresh::get).build());


    // ------------------------------------------------------------ risky: position spoofing
    private final SettingGroup sgRisky = settings.createGroup("Risky Methods");
    private final Setting<Boolean> enableRisky = sgRisky.add(new BoolSetting.Builder()
        .name("enable-risky")
        .description("Show the methods that claim a position you are not at. They load far more ground than the safe ones, and they are the only things here an anti-cheat has any reason to object to — a rubber-band or a kick is possible, occasionally on a false positive. The options stay hidden until you turn this on.")
        .defaultValue(false).build());

    private final Setting<Boolean> lookAround = sgRisky.add(new BoolSetting.Builder()
        .name("look-around (risky)")
        .description("Sweep where you're looking. Servers that send chunks in the direction you face will keep feeding you the whole circle instead of one arc. This is just a normal look packet, so it's completely safe.")
        .defaultValue(false).visible(enableRisky::get).build());
    private final Setting<Boolean> keepAlive = sgRisky.add(new BoolSetting.Builder()
        .name("stay-active (risky)")
        .description("Send an ordinary position update every round even when you haven't moved. Some servers stop streaming to players they think have gone idle; this keeps you counted as active.")
        .defaultValue(false).visible(enableRisky::get).build());
    private final Setting<Boolean> groundFlip = sgRisky.add(new BoolSetting.Builder()
        .name("ground-flip (risky)")
        .description("Alternate the on-ground flag between rounds. Standing and falling run through different checks on the server, so flipping it makes both re-examine your position. Vanilla sends both states constantly.")
        .defaultValue(false).visible(enableRisky::get).build());
    private final Setting<Boolean> microMove = sgRisky.add(new BoolSetting.Builder()
        .name("micro-move (risky)")
        .description("Add a hair of movement to each position so packets reporting no change aren't discarded. The distance is far too small to look like cheating.")
        .defaultValue(false).visible(enableRisky::get).build());

    private final Setting<Boolean> heightPing = sgRisky.add(new BoolSetting.Builder()
        .name("height-ping (risky)")
        .description("Tell the server you're high above the terrain, then correct straight back. From up there nothing blocks its view so it streams chunks to your full distance. This is the strongest method by far — and it's a position you aren't really at, which anti-cheats can read as flying. Expect the odd rubber-band, and a kick on strict servers.")
        .defaultValue(false).visible(enableRisky::get).build());

    private final Setting<Integer> aboveY = sgRisky.add(new IntSetting.Builder()
        .name("above-y")
        .description("The height to claim. It needs to clear the terrain around you to be worth anything.")
        .defaultValue(320).min(64).max(1024).sliderRange(128, 512)
        .visible(() -> enableRisky.get() && heightPing.get()).build());




    private int timer, step;
    private double[] pendingCorrection;
    private boolean flip;
    private int refreshTick, requestTick;
    private double lastX, lastZ;

    public ChunkLoader() {
        super(shama.addon.ShamaAddon.HUNT, "chunk-loader++",
            "Keeps the server sending you chunks instead of letting them settle. Safe methods ask politely; the risky ones claim a position you aren't at and load far more ground.");
    }

    @Override
    public void onActivate() {
        timer = 0; step = 0; flip = false; refreshTick = 0; requestTick = 0; pendingCorrection = null;
        if (mc.player != null) { lastX = mc.player.getX(); lastZ = mc.player.getZ(); }
    }

    /**
     * Report a low view distance, then the real one, both within this tick.
     *
     * The server re-sends the true contents of every chunk that comes back into range, which is the
     * same effect as cycling the render distance by hand — except the option is put back before the
     * frame is drawn, so your view never actually changes and nothing re-renders.
     *
     * The settings packet is sent through the method the game already uses for it, found by name so
     * a mapping change disables this cleanly instead of breaking the build.
     */
    private void requestChunkResend() {
        if (mc.options == null) return;
        try {
            var vd = mc.options.getViewDistance();
            int real = vd.getValue();
            if (real <= requestLow.get()) return;               // nothing to gain

            java.lang.reflect.Method send = settingsSender();
            if (send == null) return;                            // not available: do nothing rather than guess

            vd.setValue(requestLow.get());
            send.invoke(mc.options);                             // "I only want a few chunks"
            vd.setValue(real);
            send.invoke(mc.options);                             // "actually, send them all again"
        } catch (Throwable ignored) {}
    }

    private static java.lang.reflect.Method cachedSender;
    private static boolean senderChecked;

    private java.lang.reflect.Method settingsSender() {
        if (!senderChecked) {
            senderChecked = true;
            for (var m : mc.options.getClass().getMethods()) {
                // the no-argument method that pushes client settings to the server
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.equals("sendClientSettings") || n.equals("method_1626")) {
                    m.setAccessible(true); cachedSender = m; break;
                }
            }
        }
        return cachedSender;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (onlyWhenStill.get()) {
            boolean moving = Math.abs(mc.player.getX() - lastX) > 0.05 || Math.abs(mc.player.getZ() - lastZ) > 0.05;
            lastX = mc.player.getX(); lastZ = mc.player.getZ();
            if (moving) return;
        }

        // put the truth back first, on a tick of its own
        if (pendingCorrection != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                pendingCorrection[0], pendingCorrection[1], pendingCorrection[2], mc.player.isOnGround(), false));
            pendingCorrection = null;
            return;
        }

        if (++timer < Math.max(1, rateTicks.get())) return;
        timer = 0;

        var net = mc.getNetworkHandler();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        boolean ground = groundFlip.get() ? (flip = !flip) : mc.player.isOnGround();

        // ---- safe: ask the server for the real chunk data again ----
        if (requestChunks.get() && ++requestTick >= requestEvery.get()) {
            requestTick = 0;
            requestChunkResend();
        }

        // ---- safe: make the client redraw so anything it received but never built shows up ----
        if (viewRefresh.get() && ++refreshTick >= refreshEvery.get()) {
            refreshTick = 0;
            if (mc.worldRenderer != null) mc.worldRenderer.reload();
        }

        // ---- risky: claim a position elsewhere ----
        if (heightPing.get()) {
            double tx = px, tz = pz;
            if (microMove.get()) {
                tx += (Math.random() - 0.5) * 0.02;
                tz += (Math.random() - 0.5) * 0.02;
            }
            double sendY = aboveY.get();

            if (lookAround.get()) {
                float yaw = (float) Math.toDegrees(Math.atan2(tz - pz, tx - px)) - 90f;
                net.sendPacket(new PlayerMoveC2SPacket.Full(tx, sendY, tz, yaw, mc.player.getPitch(), ground, false));
            } else {
                net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(tx, sendY, tz, ground, false));
            }
            // The correction goes out on the NEXT tick. Two movements in one tick is exactly what
            // the server rejects as an invalid sequence.
            pendingCorrection = new double[]{px, py, pz};
            return;
        }

        // ---- safe only: never claims a position you aren't at ----
        //
        // Exactly ONE movement packet per cycle. Sending a look and then a position in the same tick
        // is two movements for one tick, which the server reads as packets out of order — that is the
        // "Invalid sequence" disconnect. Vanilla sends one combined update per tick, so this does the
        // same and folds every enabled method into it.
        double jx = px, jz = pz;
        if (microMove.get()) {
            jx += (Math.random() - 0.5) * 0.02;
            jz += (Math.random() - 0.5) * 0.02;
        }

        float yaw = mc.player.getYaw();
        if (lookAround.get()) {
            // turn a few degrees at a time instead of snapping an eighth of a circle per cycle;
            // a large jump between updates is itself grounds for rejection
            int pts = 8;
            step = (step + 1) % pts;
            yaw = mc.player.getYaw() + (step - pts / 2f) * 4f;
        }

        if (keepAlive.get() || lookAround.get() || microMove.get()) {
            net.sendPacket(new PlayerMoveC2SPacket.Full(jx, py, jz, yaw, mc.player.getPitch(), ground, false));
        } else if (groundFlip.get()) {
            net.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(ground, false));
        }
    }

    @Override
    public String getInfoString() {
        if (heightPing.get()) return "risky";
        return "safe";
    }
}
