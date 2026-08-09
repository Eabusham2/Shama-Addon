package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.modules.Freecam;

/**
 * While Freecam is active, the mouse steers the camera instead of the body: we send
 * the look delta to Freecam and cancel the player's own rotation change, so your
 * character keeps facing where it was (which is what the character-look mining uses).
 */
@Mixin(Entity.class)
public class EntityLookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true, require = 0)
    private void shama$freecamLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if ((Object) this != MinecraftClient.getInstance().player) return;
        Freecam fc = Modules.get().get(Freecam.class);
        if (fc != null && fc.isActive()) {
            fc.applyLook(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }
}
