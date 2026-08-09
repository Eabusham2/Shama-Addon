package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import shama.addon.modules.SwingSpeed;

/** Overrides the local player's hand-swing duration from the SwingSpeed module (vanilla value read at RETURN). */
@Mixin(LivingEntity.class)
public class HandSwingDurationMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
    private void shama$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this != MinecraftClient.getInstance().player) return;
        SwingSpeed m = Modules.get().get(SwingSpeed.class);
        if (m != null && m.isActive()) cir.setReturnValue(m.getSwingDuration(cir.getReturnValue()));
    }
}
