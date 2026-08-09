package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * Combat Extras++ — merged BowSpam / AutoEXP / AutoDTap tickboxes.
 *   bow-spam : instantly release + redraw a bow for rapid fire.
 *   auto-exp : throw XP bottles while held for fast mending repair.
 *   d-tap    : right-click with a sword auto-places an obsidian, then swaps back (hole defense).
 */
public class CombatExtras extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> bowSpam = sg.add(new BoolSetting.Builder().name("bow-spam").description("Rapidly fire the bow.").defaultValue(false).build());
    private final Setting<Boolean> autoExp = sg.add(new BoolSetting.Builder().name("auto-exp").description("Automatically throw XP bottles.").defaultValue(false).build());
    private final Setting<Boolean> dTap = sg.add(new BoolSetting.Builder().name("d-tap").description("Double-tap to trigger.").defaultValue(false).build());

    public CombatExtras() { super(shama.addon.ShamaAddon.COMBAT, "combat-extras++", "Bow-spam / auto-exp / d-tap."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (bowSpam.get() && mc.player.getMainHandStack().isOf(Items.BOW) && mc.options.useKey.isPressed()) {
            mc.interactionManager.stopUsingItem(mc.player);          // release
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); // redraw
        }
        if (autoExp.get()) {
            int s = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE).slot();
            if (s >= 0) { int sel = mc.player.getInventory().getSelectedSlot(); InvUtils.swap(s, false); mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); InvUtils.swap(sel, false); }
        }
        if (dTap.get() && mc.player.getMainHandStack().getItem().toString().contains("sword") && mc.options.useKey.isPressed()) {
            int s = InvUtils.findInHotbar(Items.OBSIDIAN).slot();
            if (s >= 0 && mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit) {
                int sel = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(s, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                InvUtils.swap(sel, false);
            }
        }
    }
}
