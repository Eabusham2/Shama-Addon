package shama.addon.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.modules.Bang;
import shama.addon.util.MeteorClientAccessor;
import shama.addon.commands.BangCommand;

@Mixin(BipedEntityModel.class)
public abstract class BangPoseMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftLeg;
    @Shadow @Final public ModelPart rightLeg;

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/BipedEntityRenderState;)V", at = @At("TAIL"))
    private void shama$bangPose(BipedEntityRenderState state, CallbackInfo ci) {
        if (!(state instanceof PlayerEntityRenderState player)) return;

        Bang module = MeteorClientAccessor.getBangModule();
        if (module == null || !module.isActive() || module.isWalkingPhase()) return;
        if (module.mode.get() == Bang.Mode.Legit) return; // Only Normal mode gets the custom pose

        float stroke = module.extension() > 0 ? (float) module.extension() : 0;
        if (stroke <= 0) {
            // Get from command as fallback
            stroke = BangCommand.INSTANCE.renderExtension(player);
        }
        if (stroke < 0) return;

        // Counter the root's short slide so the head and shoulders stay pinned. Player models
        // are drawn at 15/16 scale, with 16 model pixels to the block before that scale.
        float shoulderZ = -(1 - stroke) * BangCommand.TRAVEL * 16 / 0.9375f;
        head.originZ += shoulderZ;
        body.originZ += shoulderZ;
        leftArm.originZ += shoulderZ;
        rightArm.originZ += shoulderZ;
        body.pitch = (float) Math.toRadians(MathHelper.lerp(stroke, 14f, -31f));

        // The legs are siblings of the torso. Move both hip joints to the rotated waist
        // and give them the same angle: they swing as one and finish parallel to the target.
        float hipZ = shoulderZ + 12 * (float) Math.sin(body.pitch);
        float hipY = 12 * ((float) Math.cos(body.pitch) - 1);
        leftLeg.originZ += hipZ;
        rightLeg.originZ += hipZ;
        leftLeg.originY += hipY;
        rightLeg.originY += hipY;
        leftLeg.pitch = rightLeg.pitch = (float) Math.toRadians(20 * (1 - stroke));
    }
}