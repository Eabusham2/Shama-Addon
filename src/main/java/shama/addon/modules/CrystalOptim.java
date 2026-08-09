package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Crystal Optim++ — two supports, both toggleable:
 *   desync-fix   : client-side removes crystals you attack immediately (their CrystalOptim:
 *                  "client-side crystal removal for faster crystals"), so a ghost crystal
 *                  never blocks your next place/hit.
 *   show-placement: highlights the single best obsidian/bedrock base around the nearest
 *                  target (max target damage under your self-damage cap). Placement helper,
 *                  not an auto-hack — it only shows, you act.
 */
public class CrystalOptim extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> desyncFix = sg.add(new BoolSetting.Builder().name("desync-fix").description("Client-side remove crystals you attack (faster crystals).").defaultValue(true).build());
    private final Setting<Double> removeRange = sg.add(new DoubleSetting.Builder().name("remove-range").description("How close a crystal must be for the desync fix to remove it on your client (blocks).").defaultValue(6).min(1).sliderRange(2, 8).visible(desyncFix::get).build());
    private final Setting<Boolean> showPlacement = sg.add(new BoolSetting.Builder().name("show-placement").description("Highlight the optimal crystal base around the nearest target.").defaultValue(false).build());
    private final Setting<Double> targetRange = sg.add(new DoubleSetting.Builder().name("target-range").description("How far away a player can be and still be targeted (blocks).").defaultValue(8).min(1).sliderRange(2, 12).visible(showPlacement::get).build());
    private final Setting<Double> maxSelfDamage = sg.add(new DoubleSetting.Builder().name("max-self-damage").description("Never suggest a placement that would deal more than this much damage to you.").defaultValue(8).min(0).sliderRange(0, 20).visible(showPlacement::get).build());
    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder().name("color").description("Colour of the best-placement highlight.").defaultValue(new SettingColor(0, 255, 0, 90)).visible(showPlacement::get).build());

    private BlockPos best;

    public CrystalOptim() {
        super(shama.addon.ShamaAddon.COMBAT, "crystal-optim++", "Makes crystals you hit vanish on your client straight away so you can chain into the next one, and highlights the best spot to place.");
    }

    @Override public void onActivate() { best = null; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (desyncFix.get() && mc.options.attackKey.isPressed()) {
            double r = removeRange.get();
            for (Entity e : mc.world.getEntities())
                if (e instanceof EndCrystalEntity && mc.player.distanceTo(e) <= r) e.discard();
        }

        best = null;
        if (showPlacement.get()) {
            PlayerEntity target = null; double bd = targetRange.get();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || !p.isAlive()) continue;
                double d = mc.player.distanceTo(p);
                if (d < bd) { bd = d; target = p; }
            }
            if (target == null) return;
            double bestDmg = -1;
            BlockPos tb = target.getBlockPos();
            for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
                BlockPos base = tb.add(dx, dy, dz);
                var b = mc.world.getBlockState(base).getBlock();
                if (b != Blocks.OBSIDIAN && b != Blocks.BEDROCK) continue;
                BlockPos on = base.up();
                if (!mc.world.getBlockState(on).isAir()) continue;
                Vec3d c = new Vec3d(on.getX() + 0.5, on.getY() + 0.5, on.getZ() + 0.5);
                if (dmg(mc.player, c) > maxSelfDamage.get()) continue;
                double td = dmg(target, c);
                if (td > bestDmg) { bestDmg = td; best = on; }
            }
        }
    }

    private double dmg(PlayerEntity e, Vec3d center) {
        double dist = Math.sqrt(e.squaredDistanceTo(center));
        return Math.max(0, (1.0 - dist / 12.0) * 42.0);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (best == null) return;
        Color c = color.get();
        event.renderer.box(best.getX(), best.getY(), best.getZ(), best.getX() + 1, best.getY() + 1, best.getZ() + 1,
            c, new Color(c.r, c.g, c.b, 255), ShapeMode.Both, 0);
    }

    @Override public String getInfoString() { return best != null ? "spot" : (desyncFix.get() ? "desync" : "on"); }
}
