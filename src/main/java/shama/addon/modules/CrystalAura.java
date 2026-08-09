package shama.addon.modules;

import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import shama.addon.util.Humanize;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Crystal Aura++ — auto end-crystal combat, ported from their crystal-macro method with
 * the Horion-style extras you asked for:
 *   spam         : place + break as fast as the delays allow (no strict 1-per-cycle).
 *   multi-crystal: place on several valid bases around the target in one tick.
 *   walk-through : place/break without needing line of sight (ignores raycast occlusion).
 *
 * Flow (their method): pick nearest player target -> find obsidian/bedrock bases next to
 * them with air above -> place crystal -> attack any crystal in range to detonate. Damage
 * gates keep you from blowing yourself up.
 */
public class CrystalAura extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder().name("target-range").description("How far away a target can be (blocks).").defaultValue(8).min(1).sliderRange(2, 12).build());
    private final Setting<Double> placeRange = sg.add(new DoubleSetting.Builder().name("place-range").description("How far away you can place (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());
    private final Setting<Double> breakRange = sg.add(new DoubleSetting.Builder().name("break-range").description("How far away you can break (blocks).").defaultValue(5).min(1).sliderRange(2, 8).build());
    // ===== crystal-optim helpers, kept here as redundancy so the aura works standalone =====
    private final SettingGroup sgOptim = settings.createGroup("Helpers");
    private final Setting<Boolean> desyncFix = sgOptim.add(new BoolSetting.Builder()
        .name("desync-fix").description("Remove crystals you attack on your client straight away, so you can hit the next one without waiting for the server. Also available as the standalone crystal-optim++ module; running both is harmless.").defaultValue(true).build());
    private final Setting<Double> removeRange = sgOptim.add(new DoubleSetting.Builder()
        .name("remove-range").description("Range to client-side remove crystals within (blocks).").defaultValue(6).min(1).sliderRange(2, 8).visible(desyncFix::get).build());
    private final Setting<Boolean> showPlacement = sgOptim.add(new BoolSetting.Builder()
        .name("show-placement").description("Highlight the single best obsidian/bedrock base to place a crystal on against the nearest target.").defaultValue(false).build());
    private final Setting<SettingColor> optimColor = sgOptim.add(new ColorSetting.Builder()
        .name("placement-color").description("Colour of the best-placement highlight.").defaultValue(new SettingColor(0, 255, 120, 200)).visible(showPlacement::get).build());

    private net.minecraft.util.math.BlockPos bestSpot;

    private final SettingGroup sgBypass = settings.createGroup("Bypass");
    private final Setting<Integer> missChance = sgBypass.add(new IntSetting.Builder()
        .name("miss-chance").description("Chance (%) to skip an attack this tick, like a human misclicking. 0 = never miss (fastest but most robotic).").defaultValue(0).min(0).max(100).sliderRange(0, 50).build());
    private final Setting<Integer> timingJitter = sgBypass.add(new IntSetting.Builder()
        .name("timing-jitter").description("Randomly vary the delays by +/- this % so they aren't a perfect fixed rhythm.").defaultValue(0).min(0).max(100).sliderRange(0, 60).build());

    private final Setting<Boolean> spam = sg.add(new BoolSetting.Builder().name("max-speed").description("Place and break as fast as the delays allow.").defaultValue(true).build());
    private final Setting<Boolean> multi = sg.add(new BoolSetting.Builder().name("multi-crystal").description("Place on several bases around the target each tick.").defaultValue(false).build());
    private final Setting<Integer> multiMax = sg.add(new IntSetting.Builder().name("multi-max").description("Most crystals to place at once.").defaultValue(3).range(1, 10).sliderRange(1, 6).visible(multi::get).build());
    private final Setting<Boolean> walkThrough = sg.add(new BoolSetting.Builder().name("walk-through").description("Place/break without line of sight.").defaultValue(true).build());
    private final Setting<Boolean> autoPlace = sg.add(new BoolSetting.Builder().name("place").description("Place crystals automatically.").defaultValue(true).build());
    private final Setting<Boolean> autoBreak = sg.add(new BoolSetting.Builder().name("break").description("Break crystals automatically.").defaultValue(true).build());
    private final Setting<Integer> placeDelay = sg.add(new IntSetting.Builder().name("place-delay").description("Ticks between placements.").defaultValue(0).range(0, 20).sliderRange(0, 10).build());
    private final Setting<Integer> breakDelay = sg.add(new IntSetting.Builder().name("break-delay").description("Ticks between breaks.").defaultValue(0).range(0, 20).sliderRange(0, 10).visible(autoBreak::get).build());
    private final Setting<Double> maxSelfDamage = sg.add(new DoubleSetting.Builder().name("max-self-damage").description("Don't act if it would deal more than this much damage to you.").defaultValue(8).min(0).sliderRange(0, 20).build());

    private int placeTimer, breakTimer;

    public CrystalAura() {
        super(shama.addon.ShamaAddon.COMBAT, "crystal-aura++", "Places and blows end crystals for you. Also carries the helpers: crystals you hit vanish on your client straight away so you can chain into the next one, and the best spot to place is highlighted. Turn place and break off to use it as helpers only.");
    }

    @Override public void onActivate() { placeTimer = 0; breakTimer = 0; }

    @EventHandler
    private void onOptimTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (desyncFix.get() && mc.options.attackKey.isPressed()) {
            double r = removeRange.get();
            for (net.minecraft.entity.Entity e : mc.world.getEntities())
                if (e instanceof EndCrystalEntity && mc.player.distanceTo(e) <= r) e.discard();
        }
        bestSpot = null;
        if (!showPlacement.get()) return;
        net.minecraft.entity.player.PlayerEntity target = null;
        double bd = targetRange.get();
        for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d < bd) { bd = d; target = p; }
        }
        if (target == null) return;
        double bestDmg = -1;
        net.minecraft.util.math.BlockPos tb = target.getBlockPos();
        for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            net.minecraft.util.math.BlockPos base = tb.add(dx, dy, dz);
            var b = mc.world.getBlockState(base).getBlock();
            if (b != net.minecraft.block.Blocks.OBSIDIAN && b != net.minecraft.block.Blocks.BEDROCK) continue;
            net.minecraft.util.math.BlockPos on = base.up();
            if (!mc.world.getBlockState(on).isAir()) continue;
            net.minecraft.util.math.Vec3d c = new net.minecraft.util.math.Vec3d(on.getX() + 0.5, on.getY() + 0.5, on.getZ() + 0.5);
            if (optimDmg(mc.player, c) > maxSelfDamage.get()) continue;
            double td = optimDmg(target, c);
            if (td > bestDmg) { bestDmg = td; bestSpot = on; }
        }
    }

    private double optimDmg(net.minecraft.entity.player.PlayerEntity e, net.minecraft.util.math.Vec3d centre) {
        double dist = Math.sqrt(e.squaredDistanceTo(centre));
        return Math.max(0, (1.0 - dist / 12.0) * 42.0);
    }

    @EventHandler
    private void onOptimRender(meteordevelopment.meteorclient.events.render.Render3DEvent event) {
        if (bestSpot == null || !showPlacement.get()) return;
        var c = optimColor.get();
        event.renderer.box(bestSpot.getX(), bestSpot.getY(), bestSpot.getZ(),
            bestSpot.getX() + 1, bestSpot.getY() + 1, bestSpot.getZ() + 1,
            c, new Color(c.r, c.g, c.b, 255), meteordevelopment.meteorclient.renderer.ShapeMode.Both, 0);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;

        // 1) break existing crystals in range (detonate)
        if (autoBreak.get() && breakTimer == 0) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof EndCrystalEntity)) continue;
                if (mc.player.distanceTo(e) > breakRange.get()) continue;
                if (Humanize.shouldMiss(missChance.get())) { breakTimer = Humanize.jitter(breakDelay.get(), timingJitter.get()); break; }
                mc.interactionManager.attackEntity(mc.player, e);
                mc.player.swingHand(Hand.MAIN_HAND);
                breakTimer = Humanize.jitter(breakDelay.get(), timingJitter.get());
                if (!spam.get()) break;
            }
        }

        // 2) find target
        PlayerEntity target = null; double best = targetRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d < best) { best = d; target = p; }
        }
        if (target == null) return;

        // 3) place crystal(s) on valid bases around target
        if (autoPlace.get() && placeTimer == 0) {
            int slot = meteordevelopment.meteorclient.utils.player.InvUtils.findInHotbar(Items.END_CRYSTAL).slot();
            if (slot < 0) return;
            int placed = 0, want = multi.get() ? multiMax.get() : 1;
            BlockPos tb = target.getBlockPos();
            for (int dx = -2; dx <= 2 && placed < want; dx++)
                for (int dz = -2; dz <= 2 && placed < want; dz++) {
                    BlockPos base = tb.add(dx, -1, dz);
                    if (!isBase(base)) continue;
                    BlockPos on = base.up();
                    if (!mc.world.getBlockState(on).isAir()) continue;
                    if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(base)) > placeRange.get()) continue;
                    if (crystalDamage(mc.player, Vec3d.ofCenter(on)) > maxSelfDamage.get()) continue;
                    if (!walkThrough.get() && !hasLineOfSight(base)) continue;   // walk-through OFF = must actually see the spot
                    meteordevelopment.meteorclient.utils.player.InvUtils.swap(slot, true);
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(base), Direction.UP, base, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    placed++;
                    if (!spam.get() && !multi.get()) break;
                }
            if (placed > 0) placeTimer = Humanize.jitter(placeDelay.get(), timingJitter.get());
        }
    }

    /** True if nothing solid is between your eyes and this block. Only consulted when walk-through is off. */
    private boolean hasLineOfSight(BlockPos pos) {
        if (mc.player == null || mc.world == null) return false;
        Vec3d eye = mc.player.getEyePos();
        HitResult res = mc.world.raycast(new RaycastContext(eye, Vec3d.ofCenter(pos),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (res == null || res.getType() == HitResult.Type.MISS) return true;
        return res instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos);
    }

    private boolean isBase(BlockPos p) {
        var b = mc.world.getBlockState(p).getBlock();
        return b == Blocks.OBSIDIAN || b == Blocks.BEDROCK;
    }

    private double crystalDamage(PlayerEntity e, Vec3d center) {
        double dist = e.getEyePos().distanceTo(center);
        return Math.max(0, (1.0 - dist / 12.0) * 42.0); // end crystal ~6 power falloff approximation
    }

    @Override public String getInfoString() { return spam.get() ? "spam" : multi.get() ? "multi" : "on"; }
}
