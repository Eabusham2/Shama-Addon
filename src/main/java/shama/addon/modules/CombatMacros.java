package shama.addon.modules;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Combat Macros++ — merged AnchorMacro + WindPearlMacro, fired on a keybind (one action
 * per activation). Anchor: place -> charge -> break a respawn anchor at your crosshair.
 * WindPearl: throw an ender pearl then a wind charge to launch after it.
 */
public class CombatMacros extends Module {
    public enum Which { Anchor, WindPearl }
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Which> which = sg.add(new EnumSetting.Builder<Which>().name("macro").description("The command/text the macro runs.").defaultValue(Which.Anchor).build());
    private int step;

    public CombatMacros() { super(shama.addon.ShamaAddon.COMBAT, "combat-macros++", "Anchor / wind-pearl combo macros on a keybind."); }

    @Override public void onActivate() { step = 0; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (which.get() == Which.WindPearl) {
            // throw pearl, then wind charge
            if (use(Items.ENDER_PEARL)) { /* next tick wind charge */ }
            use(Items.WIND_CHARGE);
            toggle();
            return;
        }
        // Anchor macro at crosshair
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) { toggle(); return; }
        switch (step) {
            case 0 -> { if (swap(Items.RESPAWN_ANCHOR)) { mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); step = 1; } else toggle(); }
            case 1 -> { if (swap(Items.GLOWSTONE)) { mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); step = 2; } }
            default -> { mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); toggle(); }
        }
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean swap(net.minecraft.item.Item item) {
        int s = InvUtils.findInHotbar(item).slot();
        if (s < 0) return false;
        InvUtils.swap(s, false);
        return true;
    }
    private boolean use(net.minecraft.item.Item item) {
        if (!swap(item)) return false;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        return true;
    }
}
