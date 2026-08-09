package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import shama.addon.util.Humanize;

/**
 * Anchor Aura++ — auto respawn-anchor combat, ported from their AnchorAura/DoubleAnchor/
 * BedAura flow (merged): pick the best nearby player target, find a placement next to
 * them, then run the place -> charge -> detonate cycle with a per-step delay. Includes
 * the bed variant as a tickbox (place+break beds in Nether/End for the same effect).
 *
 * Their exact step order is preserved: swap+place anchor, swap+charge with glowstone,
 * then break to explode; a delay counter gates each action. Server-validated, so it
 * lands on lenient servers / where anchors are allowed.
 */
public class AnchorAura extends Module {
    public enum Mode { Anchor, Bed }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>().name("mode").description("Anchor (any dim) or Bed (Nether/End).").defaultValue(Mode.Anchor).build());
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder().name("target-range").description("How far away a target can be (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());
    private final Setting<Double> placeRange = sg.add(new DoubleSetting.Builder().name("place-range").description("How far away you can place (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());
    private final Setting<Integer> stepDelay = sg.add(new IntSetting.Builder().name("step-delay").description("Ticks between place/charge/detonate.").defaultValue(2).range(0, 20).sliderRange(0, 10).build());
    private final Setting<Double> minDamage = sg.add(new DoubleSetting.Builder().name("min-damage").description("Only detonate if it would deal at least this to the target.").defaultValue(6).min(0).sliderRange(0, 20).build());
    private final Setting<Double> maxSelfDamage = sg.add(new DoubleSetting.Builder().name("max-self-damage").description("Never detonate if it would deal more than this to you.").defaultValue(8).min(0).sliderRange(0, 20).build());
    private final SettingGroup sgBypass = settings.createGroup("Bypass");
    private final Setting<Integer> missChance = sgBypass.add(new IntSetting.Builder().name("miss-chance").description("Chance (%) to skip a step this cycle, like a human hesitating. 0 = never (fastest but most robotic).").defaultValue(0).min(0).max(100).sliderRange(0, 40).build());
    private final Setting<Integer> timingJitter = sgBypass.add(new IntSetting.Builder().name("timing-jitter").description("Randomly vary the step delay by +/- this % so it isn't a perfect fixed rhythm.").defaultValue(0).min(0).max(100).sliderRange(0, 60).build());

    private int delayCounter;
    private int phase; // 0 place, 1 charge, 2 detonate
    private BlockPos placePos;
    private PlayerEntity target;

    public AnchorAura() {
        super(shama.addon.ShamaAddon.COMBAT, "bombaura++", "Auto anchor/bed aura (place, charge, detonate on nearby enemies).");
    }

    @Override public void onActivate() { delayCounter = 0; phase = 0; placePos = null; target = null; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        // pick / validate target (their TargetUtils.getPlayerTarget)
        if (target == null || !target.isAlive() || mc.player.distanceTo(target) > targetRange.get()) {
            target = null;
            double best = targetRange.get();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || !p.isAlive()) continue;
                double d = mc.player.distanceTo(p);
                if (d < best) { best = d; target = p; }
            }
            if (target == null) return;
        }

        if (delayCounter < Humanize.jitter(stepDelay.get(), timingJitter.get())) { delayCounter++; return; }
        delayCounter = 0;
        if (Humanize.shouldMiss(missChance.get())) return;   // human hesitation: skip this step, try next tick

        if (placePos == null) placePos = findPlace(target);
        if (placePos == null) return;

        boolean bed = mode.get() == Mode.Bed;
        // self/target damage gate (their DamageUtils.anchorDamage / bedDamage)
        double dmgTarget = explosionDamage(target, Vec3d.ofCenter(placePos));
        double dmgSelf = explosionDamage(mc.player, Vec3d.ofCenter(placePos));
        if (dmgTarget < minDamage.get() || dmgSelf > maxSelfDamage.get()) { placePos = null; phase = 0; return; }

        switch (phase) {
            case 0 -> { // place anchor/bed
                if (swapTo(bed ? Items.RED_BED : Items.RESPAWN_ANCHOR)) {
                    place(placePos);
                    phase = bed ? 2 : 1; // beds explode on the follow-up interact; anchors need charge
                }
            }
            case 1 -> { // charge anchor with glowstone
                if (swapTo(Items.GLOWSTONE)) { interact(placePos); phase = 2; }
            }
            case 2 -> { // detonate: interact anchor (empty hand) / interact bed
                interact(placePos);
                placePos = null; phase = 0;
            }
        }
    }

    private BlockPos findPlace(PlayerEntity t) {
        // scan around the target for an air spot within place-range (their findPlace)
        BlockPos base = t.getBlockPos();
        for (int dy = 0; dy <= 2; dy++) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos p = base.up(dy).offset(d);
                if (mc.world.getBlockState(p).isAir() && mc.player.getEyePos().distanceTo(Vec3d.ofCenter(p)) <= placeRange.get())
                    return p;
            }
        }
        return null;
    }

    private boolean swapTo(net.minecraft.item.Item item) {
        int slot = InvUtils.findInHotbar(item).slot();
        if (slot < 0) return false;
        InvUtils.swap(slot, false);
        return true;
    }

    private void place(BlockPos pos) {
        Direction side = Direction.UP;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void interact(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Rough explosion damage falloff by distance (stand-in for their DamageUtils). */
    private double explosionDamage(PlayerEntity e, Vec3d center) {
        double dist = e.getEyePos().distanceTo(center);
        double power = mode.get() == Mode.Bed ? 5.0 : 5.0; // both ~5 power
        double raw = Math.max(0, (1.0 - dist / (power * 2)) * (power * 7));
        return raw;
    }

    @Override public String getInfoString() { return target != null ? "target" : "idle"; }
}
