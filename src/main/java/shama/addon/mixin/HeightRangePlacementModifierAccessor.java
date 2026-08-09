package shama.addon.mixin;

import net.minecraft.world.gen.heightprovider.HeightProvider;
import net.minecraft.world.gen.placementmodifier.HeightRangePlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private height provider on HeightRangePlacementModifier. */
@Mixin(HeightRangePlacementModifier.class)
public interface HeightRangePlacementModifierAccessor {
    @Accessor("height")
    HeightProvider getHeight();
}
