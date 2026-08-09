package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;

import shama.addon.mixin.InteractionManagerMixin;

/**
 * No Break Delay — removes the ~5-tick cooldown vanilla adds after you break or
 * punch a block, so you can mine blocks back-to-back with no stutter. Same idea
 * as the No Break Delay / No Mining Cooldown / KeepOnMining mods.
 *
 * This is the standalone version; the same toggle also lives inside Instant Mine++.
 * The cooldown is a client-side gate, so this works in both singleplayer and on
 * servers (servers don't track this particular cooldown).
 */
public class NoBreakDelay extends Module {
    public NoBreakDelay() {
        super(shama.addon.ShamaAddon.PLAYER, "no-break-delay++", "Removes the cooldown between breaking blocks. Mine continuously with no pause.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.interactionManager == null) return;
        zeroCooldown(mc.interactionManager);
    }

    /** Shared helper so Instant Mine++ can call the exact same logic. */
    public static void zeroCooldown(ClientPlayerInteractionManager im) {
        ((InteractionManagerMixin) im).setBlockBreakingCooldown(0);
    }
}
