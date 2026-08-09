package shama.addon.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes HandledScreen#focusedSlot (the slot under the cursor) so NbtAdder can
 * read it on a middle-click. This is an @Accessor, not a method @Inject: it just
 * generates a getter for an existing field, so there's no handler signature to
 * mismatch — it stays valid across the 1.21.11 mouseClicked(Click, boolean) refactor
 * that broke the old injection approach.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("focusedSlot")
    Slot shama$focusedSlot();
}
