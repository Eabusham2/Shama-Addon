package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.modules.Freecam;

/**
 * Detaches the camera to the Freecam position/rotation. Injects at the TAIL of
 * Camera#update with a no-argument handler so it doesn't depend on that method's
 * parameter list, which changed in 1.21.11 (first arg BlockView -> World). setPos and
 * setRotation are stable. Lives in the non-required config, so a mapping change just
 * disables Freecam rather than crashing.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void shama$freecam(CallbackInfo ci) {
        Freecam fc = Modules.get().get(Freecam.class);
        if (fc != null && fc.isActive() && fc.hasPos()) {
            // Interpolate the per-tick camera position by the current frame's tick progress
            // so motion is smooth at any FPS (rotation stays direct for responsive mouse-look).
            float td;
            try { td = net.minecraft.client.MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(true); }
            catch (Throwable t) { td = 1f; }
            this.setRotation(fc.getFreecamYaw(), fc.getFreecamPitch());
            this.setPos(fc.getRenderPos(td));
        }
    }
}
