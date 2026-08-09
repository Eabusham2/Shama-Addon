package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import shama.addon.util.Humanize;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * AutoCity++ — "cities" a target: finds the nearest player sitting in a hole and breaks
 * the block on the side of their feet facing you, opening them up for a crystal/attack.
 * Ported from their AutoCity (target-range + break-range, packet break).
 */
public class AutoCity extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder().name("target-range").description("How far away a target can be (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());
    private final Setting<Integer> timingJitter = sg.add(new IntSetting.Builder().name("timing-jitter").description("Randomly space out the block-break so it isn't a perfect fixed rhythm (%).").defaultValue(0).min(0).max(100).sliderRange(0,60).build());
    private final Setting<Double> breakRange = sg.add(new DoubleSetting.Builder().name("break-range").description("How far away you can break (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());

    private int cityCooldown;

    public AutoCity() {
        super(shama.addon.ShamaAddon.COMBAT, "auto-city++", "Breaks the block beside a target in a hole to expose them.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        if (cityCooldown > 0) { cityCooldown--; return; }
        PlayerEntity target = null; double best = targetRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d < best) { best = d; target = p; }
        }
        if (target == null) return;

        BlockPos feet = target.getBlockPos();
        // pick the surrounding foot-block closest to us that's breakable
        BlockPos bestPos = null; double bd = breakRange.get();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos p = feet.offset(dir);
            if (mc.world.getBlockState(p).isAir()) continue;
            double d = mc.player.getEyePos().distanceTo(p.toCenterPos());
            if (d < bd) { bd = d; bestPos = p; }
        }
        if (bestPos == null) return;

        Direction side = Direction.UP;
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, bestPos, side, 0));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, bestPos, side, 0));
        mc.player.swingHand(Hand.MAIN_HAND);
        cityCooldown = Humanize.jitter(1, timingJitter.get());
    }
}
