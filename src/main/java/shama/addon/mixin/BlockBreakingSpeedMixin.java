package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import shama.addon.modules.InstantMine;

/** Scales block breaking speed by InstantMine's factor when active. */
@Mixin(PlayerEntity.class)
public class BlockBreakingSpeedMixin {
    @Inject(method = "getBlockBreakingSpeed", at = @At("RETURN"), cancellable = true, require = 0)
    private void shamaScaleBreakSpeed(BlockState block, CallbackInfoReturnable<Float> cir) {
        InstantMine m = Modules.get().get(InstantMine.class);
        if (m != null && m.isActive()) {
            float base = cir.getReturnValue();
            cir.setReturnValue((float) (base * m.factor()));
        }
    }
}
