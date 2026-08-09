package shama.addon.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read/write LivingEntity#itemUseTimeLeft (the draw/use countdown). FastBow uses this
 * on the integrated server's player in singleplayer to force a bow to full charge
 * instantly. itemUseTimeLeft is a plain (non-final) int field in 1.21.11, so a simple
 * accessor works and there's no signature to mismatch.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("itemUseTimeLeft")
    int shama$getItemUseTimeLeft();

    @Accessor("itemUseTimeLeft")
    void shama$setItemUseTimeLeft(int value);
}
