package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import shama.addon.modules.Timer;

/**
 * Drives Timer++ by scaling RenderTickCounter.Dynamic#tickTime (the ms-per-tick,
 * normally 50). Smaller tickTime => more game ticks per real second => faster game.
 *
 * Verified against Yarn 1.21.11: tickTime is `private final float` (so @Mutable is
 * required to write it), and the per-frame entry point is the two-arg
 * beginRenderTick(long, boolean) — descriptor (JZ)I. Lives in the non-required
 * mixin config, so a future mapping change just disables Timer instead of crashing.
 */
@Mixin(RenderTickCounter.Dynamic.class)
public class RenderTickCounterMixin {
    @Shadow
    @Final
    @Mutable
    private float tickTime;

    @Unique
    private boolean shama$wasActive;

    @Inject(method = "beginRenderTick(JZ)I", at = @At("HEAD"), require = 0)
    private void shama$applyTimer(long timeMillis, boolean tick, CallbackInfoReturnable<Integer> cir) {
        Timer timer = Modules.get().get(Timer.class);
        boolean scale = timer != null && timer.isActive() && timer.clientClock();
        if (scale) {
            this.tickTime = 50.0f / timer.multiplier(); // 50 ms = 1 vanilla tick
            shama$wasActive = true;
        } else if (shama$wasActive) {
            this.tickTime = 50.0f; // restore exactly once when it stops scaling
            shama$wasActive = false;
        }
    }
}
