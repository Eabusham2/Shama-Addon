package shama.addon.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private block-break cooldown so we can zero it (no break delay). */
@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerMixin {
    @Accessor("blockBreakingCooldown")
    void setBlockBreakingCooldown(int cooldown);

    @Accessor("blockBreakingCooldown")
    int getBlockBreakingCooldown();
}
