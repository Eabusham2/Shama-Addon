package shama.addon.mixin;

import net.minecraft.world.gen.placementmodifier.RarityFilterPlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private rarity chance on RarityFilterPlacementModifier. */
@Mixin(RarityFilterPlacementModifier.class)
public interface RarityFilterPlacementModifierAccessor {
    @Accessor("chance")
    int getChance();
}
