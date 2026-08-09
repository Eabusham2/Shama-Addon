package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import shama.addon.modules.NoCooldown;

/** Reports items as never cooling down when NoCooldown's item toggle is on. */
@Mixin(ItemCooldownManager.class)
public class ItemCooldownMixin {
    @Inject(method = "isCoolingDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void shamaNoItemCooldown(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        NoCooldown m = Modules.get().get(NoCooldown.class);
        if (m != null && m.isActive() && m.items.get()) {
            cir.setReturnValue(false);
        }
    }
}
