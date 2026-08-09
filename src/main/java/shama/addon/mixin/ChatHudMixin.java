package shama.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import shama.addon.modules.HideChat;

/** Hides the chat feed/history when HideChat is active. No-arg handler so it's render-signature agnostic. Non-required config. */
@Mixin(ChatHud.class)
public class ChatHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void shama$hideChat(CallbackInfo ci) {
        HideChat m = Modules.get().get(HideChat.class);
        if (m != null && m.isActive()) ci.cancel();
    }
}
