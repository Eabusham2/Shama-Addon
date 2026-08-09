package shama.addon.mixin;

import net.minecraft.util.math.intprovider.IntProvider;
import net.minecraft.world.gen.placementmodifier.CountPlacementModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private vein-count provider on CountPlacementModifier. */
@Mixin(CountPlacementModifier.class)
public interface CountPlacementModifierAccessor {
    @Accessor("count")
    IntProvider getCount();
}
