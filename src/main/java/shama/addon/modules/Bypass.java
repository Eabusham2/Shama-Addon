package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import shama.addon.util.Humanize;

/**
 * Bypass++ — merged anti-detection tweaks (AntiCheatBypass + SusBypass + AmethystBypass).
 *   rotation-smoothing : eases large view snaps so aim-assist looks human.
 *   rotation-noise     : adds a tiny natural wobble to aim so it never looks robotic.
 * All client-side; nothing is sent that you didn't already send.
 */
public class Bypass extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> rotationSmoothing = sg.add(new BoolSetting.Builder().name("rotation-smoothing").description("How smoothly rotations are applied.").defaultValue(true).build());
    private final Setting<Double> maxDelta = sg.add(new DoubleSetting.Builder().name("max-rotation-step").description("Max degrees the view may snap per tick.").defaultValue(35).min(5).sliderRange(10, 90).visible(rotationSmoothing::get).build());
    private final Setting<Boolean> rotationNoise = sg.add(new BoolSetting.Builder().name("rotation-noise (risky)").description("Add a tiny, natural wobble to your aim so it never sits perfectly still or moves in perfectly straight lines — the kind of thing that flags aim-assist.").defaultValue(false).build());
    private final Setting<Double> noiseAmount = sg.add(new DoubleSetting.Builder().name("noise-amount").description("How much wobble to add, in degrees (keep it small — 0.5 to 2 feels natural).").defaultValue(1.0).min(0.1).max(5).sliderRange(0.2, 3).visible(rotationNoise::get).build());

    private float lastYaw, lastPitch;

    public Bypass() { super(shama.addon.ShamaAddon.MISC, "bypass++", "Small client-side tweaks that make other modules look more legitimate to anti-cheats."); }

    @Override public void onActivate() { if (mc.player != null) { lastYaw = mc.player.getYaw(); lastPitch = mc.player.getPitch(); } }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || !rotationSmoothing.get()) return;
        float m = (float) (double) maxDelta.get();
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float dy = net.minecraft.util.math.MathHelper.clamp(yaw - lastYaw, -m, m);
        float dp = net.minecraft.util.math.MathHelper.clamp(pitch - lastPitch, -m, m);
        mc.player.setYaw(lastYaw + dy);
        mc.player.setPitch(lastPitch + dp);
        lastYaw = mc.player.getYaw(); lastPitch = mc.player.getPitch();
    }

    @EventHandler
    private void onNoiseTick(TickEvent.Post event) {
        if (mc.player == null || !rotationNoise.get()) return;
        float amt = (float) (double) noiseAmount.get();
        mc.player.setYaw(mc.player.getYaw() + Humanize.rotationNoise(amt));
        mc.player.setPitch(net.minecraft.util.math.MathHelper.clamp(mc.player.getPitch() + Humanize.rotationNoise(amt), -90f, 90f));
    }

}
