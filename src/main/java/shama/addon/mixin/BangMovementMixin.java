package shama.addon.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import shama.addon.commands.BangCommand;

@Mixin(ClientPlayerEntity.class)
public abstract class BangMovementMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3d shama$bangMovement(Vec3d original, @Local(argsOnly = true) MovementType type) {
        return type == MovementType.SELF ? BangCommand.INSTANCE.lockedMovement(original) : original;
    }
}