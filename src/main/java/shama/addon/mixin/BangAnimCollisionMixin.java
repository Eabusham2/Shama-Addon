package shama.addon.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.commands.BangAnimCommand;

/**
 * Working `noclip` for `.banganim noclip`: `noClip` only skips block collision; players
 * still push you via `Entity.pushAwayFrom` + `PlayerEntity.onPlayerCollision`. Cancel those.
 */
@Mixin(Entity.class)
public abstract class BangAnimCollisionMixin {
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void shama$noPush(Entity other, CallbackInfo ci) {
        if (shama$isLocalAndNoClip()) ci.cancel();
    }

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void shama$noPlayerCollide(PlayerEntity player, CallbackInfo ci) {
        if (shama$isLocalAndNoClip()) ci.cancel();
    }

    private boolean shama$isLocalAndNoClip() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && (Object) this == mc.player && BangAnimCommand.INSTANCE.isNoClip();
    }
}