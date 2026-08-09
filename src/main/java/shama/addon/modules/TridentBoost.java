package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

/** TridentBoost++ — launch in your look direction with a riptide trident, without needing water/rain. Hold a trident and press use. */
public class TridentBoost extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> power = sg.add(new DoubleSetting.Builder().name("power").description("Strength of the effect.").defaultValue(2.5).min(0.5).sliderRange(1, 5).build());
    private int cooldown;

    public TridentBoost() { super(shama.addon.ShamaAddon.MOVEMENT, "trident-boost++", "Riptide-launch in any conditions with a trident."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (cooldown > 0) cooldown--;
        if (mc.player == null) return;
        boolean holdingTrident = mc.player.getMainHandStack().isOf(Items.TRIDENT) || mc.player.getOffHandStack().isOf(Items.TRIDENT);
        if (holdingTrident && mc.options.useKey.isPressed() && cooldown == 0) {
            Vec3d look = mc.player.getRotationVec(1f);
            mc.player.setVelocity(look.multiply(power.get()));
            mc.player.velocityDirty = true;
            cooldown = 20;
        }
    }
}
