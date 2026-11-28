package shama.addon.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import shama.addon.commands.BangCommand;

@Mixin(KeyboardInput.class)
public abstract class BangInputMixin extends Input {
    @ModifyExpressionValue(method = "tick", at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/util/PlayerInput;"))
    private PlayerInput shama$bangInput(PlayerInput keyboard) {
        return BangCommand.INSTANCE.applyInput(this, keyboard);
    }
}