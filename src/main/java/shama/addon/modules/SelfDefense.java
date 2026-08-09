package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Self Defense++ — merged SelfWeb / SelfAnvil / SelfTrap. Tickboxes:
 *   web  : place a cobweb on yourself (and your upper hitbox with double-place).
 *   trap : surround yourself with obsidian (hole-trap).
 *   anvil: place an anvil two blocks above you (blocks players dropping into your hole).
 */
public class SelfDefense extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> web = sg.add(new BoolSetting.Builder().name("web").description("Trap attackers in cobwebs.").defaultValue(false).build());
    private final Setting<Boolean> doublePlace = sg.add(new BoolSetting.Builder().name("web-double").description("Also web your upper hitbox.").defaultValue(false).visible(web::get).build());
    private final Setting<Boolean> trap = sg.add(new BoolSetting.Builder().name("trap").description("Obsidian around you.").defaultValue(false).build());
    private final Setting<Boolean> anvil = sg.add(new BoolSetting.Builder().name("anvil").description("Anvil two blocks above you.").defaultValue(false).build());

    public SelfDefense() {
        super(shama.addon.ShamaAddon.COMBAT, "self-defense++", "Self web / trap / anvil in one module.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        BlockPos feet = mc.player.getBlockPos();

        if (web.get()) {
            placeAt(feet, Items.COBWEB);
            if (doublePlace.get()) placeAt(feet.up(), Items.COBWEB);
        }
        if (trap.get()) {
            for (Direction d : Direction.Type.HORIZONTAL) placeAt(feet.offset(d), Items.OBSIDIAN);
            placeAt(feet.up(2), Items.OBSIDIAN);
        }
        if (anvil.get()) placeAt(feet.up(2), Items.ANVIL);
    }

    private void placeAt(BlockPos pos, net.minecraft.item.Item item) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return;
        int slot = InvUtils.findInHotbar(item).slot();
        if (slot < 0) return;
        int prev = mc.player.getInventory().getSelectedSlot();
        InvUtils.swap(slot, true);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swap(prev, true);
    }
}
