package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Instant Mine++ — several distinct ways to break faster.
 *
 *   Multiplier - scale client break speed via BlockBreakingSpeedMixin (uses the
 *                speed slider). SP / lenient servers.
 *   Packet     - raw START+STOP destroy packets on the crosshair block (sequence 0).
 *   Abort      - raw START+ABORT instead (some servers finalize on abort).
 *   Vanilla    - no speed change; only removes the delay between breaks.
 *   Sequenced  - break the crosshair block through the client's OWN sequenced
 *                breakBlock() pipeline, i.e. with correct sequence numbers and
 *                block prediction — different from Packet's raw sequence-0 spam, so
 *                servers that validate the sequence field accept it where Packet is
 *                dropped. Reverts on strict servers if the block is genuinely too hard.
 *   Nuker      - AREA break: every solid block within radius AND reach, each tick,
 *                not just the one under your crosshair. Completely different shape of
 *                attack. High radius = lots of packets; a server may kick for spam.
 *
 * Honest scope: full effect in singleplayer; on servers the server re-validates, so
 * instant/area work only on lenient ones. Strict anti-cheats cap break rate regardless.
 */
public class InstantMine extends Module {
    public enum Mode { Multiplier, Packet, Abort, Vanilla, Sequenced, Nuker, SpeedMine }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Multiplier scales break speed. Packet/Abort fire raw destroy packets. Vanilla only removes break delay. Sequenced uses the game's own sequenced break (accepted where raw packets are dropped). Nuker breaks everything in radius+reach at once. SpeedMine ramps break speed up the longer you hold attack on the same block (merged module).")
        .defaultValue(Mode.Multiplier)
        .build()
    );

    public final Setting<Integer> speedPercent = sgGeneral.add(new IntSetting.Builder()
        .name("speed-percent")
        .description("100 = instant (default), 0 = normal speed, -500 = 5x slower.")
        .defaultValue(100)
        .range(-500, 100)
        .sliderRange(-500, 100)
        .visible(() -> mode.get() == Mode.Multiplier)
        .build()
    );

    public final Setting<Integer> nukerRadius = sgGeneral.add(new IntSetting.Builder()
        .name("nuker-radius")
        .description("Nuker: block radius around you to break each tick. Kept within reach. Higher = far more packets (kick risk).")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(() -> mode.get() == Mode.Nuker)
        .build()
    );

    public final Setting<Boolean> noBreakDelay = sgGeneral.add(new BoolSetting.Builder()
        .name("no-break-delay")
        .description("Remove the cooldown between block breaks so mining is continuous. Works in singleplayer and on servers.")
        .defaultValue(true)
        .build()
    );

    private net.minecraft.util.math.BlockPos speedMineTarget;
    private int speedMineHoldTicks;

    public InstantMine() {
        super(shama.addon.ShamaAddon.PLAYER, "instant-mine++", "Break blocks far faster, with several methods to choose from (speed multiplier, packets, abort, sequenced, or an area nuker). Strength depends on the server.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mode.get() == Mode.SpeedMine) {
            boolean looking = mc.crosshairTarget instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK;
            net.minecraft.util.math.BlockPos cur = looking ? ((BlockHitResult) mc.crosshairTarget).getBlockPos() : null;
            if (cur != null && cur.equals(speedMineTarget) && mc.options.attackKey.isPressed()) speedMineHoldTicks++;
            else { speedMineHoldTicks = 0; speedMineTarget = cur; }
        }
        if (noBreakDelay.get() && mc.interactionManager != null) {
            NoBreakDelay.zeroCooldown(mc.interactionManager);
        }
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.interactionManager == null) return;

        Mode m = mode.get();
        boolean attacking = mc.options.attackKey.isPressed();
        boolean lookingAtBlock = mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;

        if ((m == Mode.Packet || m == Mode.Abort) && attacking && lookingAtBlock) {
            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            sendBreak(hit.getBlockPos(), hit.getSide(), m == Mode.Abort);
        } else if (m == Mode.Sequenced && attacking && lookingAtBlock) {
            // Game's own sequenced instant break (correct sequence + prediction).
            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            mc.interactionManager.breakBlock(hit.getBlockPos());
            mc.player.swingHand(Hand.MAIN_HAND);
        } else if (m == Mode.Nuker) {
            nuke();
        }
    }

    /** Raw start + stop/abort destroy packets for one block (sequence 0). */
    private void sendBreak(BlockPos pos, Direction side, boolean abort) {
        var nh = mc.getNetworkHandler();
        nh.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side, 0));
        nh.sendPacket(new PlayerActionC2SPacket(
            abort ? PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK : PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos, side, 0));
    }

    /** Break every solid block within radius and reach this tick. */
    private void nuke() {
        int r = nukerRadius.get();
        double reachSq = 5.0 * 5.0;
        Vec3d eye = mc.player.getEyePos();
        BlockPos center = mc.player.getBlockPos();
        boolean swung = false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > reachSq) continue;
                    if (mc.world.getBlockState(pos).isAir()) continue;
                    sendBreak(pos, Direction.UP, false);
                    if (!swung) { mc.player.swingHand(Hand.MAIN_HAND); swung = true; }
                }
            }
        }
    }

    /**
     * Multiplier applied to vanilla block-breaking speed (Multiplier mode only).
     *   p = 100 -> instant; 0 < p < 100 -> 1/(1 - p/100); p = 0 -> 1.0; p < 0 -> slower.
     */
    public double factor() {
        if (mode.get() == Mode.SpeedMine) return 1.0 + Math.min(speedMineHoldTicks, 100) * 0.15; // ramps up the longer you hold
        if (mode.get() != Mode.Multiplier) return 1.0;
        int p = speedPercent.get();
        if (p >= 100) return 100000.0;
        if (p > 0)   return 1.0 / (1.0 - p / 100.0);
        if (p == 0)  return 1.0;
        return 1.0 / (1.0 + (-p) / 125.0);
    }

    @Override
    public String getInfoString() {
        return mode.get().name().toLowerCase();
    }
}
