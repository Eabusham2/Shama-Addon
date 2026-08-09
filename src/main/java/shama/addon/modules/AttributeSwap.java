package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;

/**
 * AttributeSwap++ — ported from their AttributeSwap: briefly swaps your held hotbar slot
 * away and back when you attack, which refreshes the item's attack attributes / cooldown
 * so consecutive hits land at full damage faster.
 */
public class AttributeSwap extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> swapSlot = sg.add(new IntSetting.Builder().name("swap-slot").description("Hotbar slot to bounce through (1-9).").defaultValue(9).range(1, 9).build());
    private boolean prevAttack;

    public AttributeSwap() { super(shama.addon.ShamaAddon.COMBAT, "attribute-swap++", "Swaps slots on attack to refresh weapon attributes/cooldown."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        boolean attack = mc.options.attackKey.isPressed();
        if (attack && !prevAttack) {
            int sel = mc.player.getInventory().getSelectedSlot();
            int other = swapSlot.get() - 1;
            if (other != sel) { InvUtils.swap(other, false); InvUtils.swap(sel, false); }
        }
        prevAttack = attack;
    }
}
