package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * World Extras++ — merged EChestFarmer / LiquidFiller / Flamethrower / Ambience tickboxes.
 *   flamethrower : rapidly flint-and-steel the block you look at (fire spam).
 *   liquid-fill  : place your held bucket liquid at the crosshair repeatedly.
 *   flint-only   : (ambience) keeps fire lit around you visually.
 */
public class WorldExtras extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> flamethrower = sg.add(new BoolSetting.Builder().name("flamethrower").description("Flint-and-steel the block you look at rapidly.").defaultValue(false).build());
    private final Setting<Boolean> liquidFill = sg.add(new BoolSetting.Builder().name("liquid-fill").description("Place your held bucket liquid at the crosshair.").defaultValue(false).build());

    public WorldExtras() { super(shama.addon.ShamaAddon.PLAYER, "world-extras++", "Flamethrower / liquid-fill world helpers."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        if (flamethrower.get()) {
            int s = InvUtils.findInHotbar(Items.FLINT_AND_STEEL).slot();
            if (s >= 0) { InvUtils.swap(s, true); mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); mc.player.swingHand(Hand.MAIN_HAND); }
        }
        if (liquidFill.get() && (mc.player.getMainHandStack().isOf(Items.WATER_BUCKET) || mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET))) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        }
    }
}
