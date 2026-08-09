package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.modules.Freecam;

/**
 * Routes the scroll wheel to Freecam's fly speed while it's active (like Meteor's freecam),
 * cancelling the hotbar scroll. Non-required so a mapping change just disables the feature.
 */
@Mixin(Mouse.class)
public class MouseScrollMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void shama$freecamScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        Freecam fc = Modules.get().get(Freecam.class);
        if (fc != null && fc.isActive() && fc.consumeScroll(vertical)) ci.cancel();
    }
}
