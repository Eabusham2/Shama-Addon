package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import shama.addon.modules.NoCooldown;

/** Forces attack cooldown progress to full (1.0) when NoCooldown's attack toggle is on. */
@Mixin(PlayerEntity.class)
public class AttackCooldownMixin {
    @Inject(method = "getAttackCooldownProgress", at = @At("HEAD"), cancellable = true, require = 0)
    private void shamaNoAttackCooldown(float baseTime, CallbackInfoReturnable<Float> cir) {
        NoCooldown m = Modules.get().get(NoCooldown.class);
        if (m != null && m.isActive() && m.attack.get()) {
            cir.setReturnValue(1.0f);
        }
    }
}
