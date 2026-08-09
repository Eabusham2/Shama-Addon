package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;

/** Anti-AFK — performs periodic actions (jump / swing / sneak / strafe / spin) to avoid AFK kicks. */
public class AntiAfk extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> jump = sg.add(new BoolSetting.Builder().name("jump").description("Jump periodically.").defaultValue(true).build());
    private final Setting<Boolean> swing = sg.add(new BoolSetting.Builder().name("swing").description("Swing your hand.").defaultValue(false).build());
    private final Setting<Boolean> sneak = sg.add(new BoolSetting.Builder().name("sneak").description("Sneak/unsneak quickly.").defaultValue(false).build());
    private final Setting<Boolean> strafe = sg.add(new BoolSetting.Builder().name("strafe").description("Alternate left/right steps.").defaultValue(false).build());
    private final Setting<Boolean> spin = sg.add(new BoolSetting.Builder().name("spin").description("Continuously rotate your view.").defaultValue(false).build());
    private final Setting<Integer> spinSpeed = sg.add(new IntSetting.Builder().name("spin-speed").description("How fast to spin while AFK.").defaultValue(10).min(1).max(45).sliderRange(1, 30).visible(spin::get).build());
    private final Setting<Integer> delay = sg.add(new IntSetting.Builder().name("delay").description("Ticks between actions.").defaultValue(20).min(1).max(200).sliderRange(1, 100).build());

    private int tick;
    private boolean strafeSide;

    public AntiAfk() { super(shama.addon.ShamaAddon.MISC, "anti-afk++", "Periodic actions to prevent AFK kicks."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (spin.get()) mc.player.setYaw(mc.player.getYaw() + spinSpeed.get());

        if (tick++ % Math.max(1, delay.get()) != 0) return;

        if (jump.get() && mc.player.isOnGround()) mc.options.jumpKey.setPressed(true);
        else mc.options.jumpKey.setPressed(false);

        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);

        if (sneak.get()) mc.options.sneakKey.setPressed(!mc.options.sneakKey.isPressed());

        if (strafe.get()) {
            strafeSide = !strafeSide;
            mc.options.leftKey.setPressed(strafeSide);
            mc.options.rightKey.setPressed(!strafeSide);
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
        }
    }
}
