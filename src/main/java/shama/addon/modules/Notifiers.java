package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/** Notifiers++ — merged RainNoti + low-durability alert. Chats when rain starts/stops, and when a held item's durability drops below a threshold. */
public class Notifiers extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> rain = sg.add(new BoolSetting.Builder().name("rain").description("Notify when it starts raining.").defaultValue(true).build());
    private final Setting<Boolean> durability = sg.add(new BoolSetting.Builder().name("low-durability").description("Warn when a tool is nearly broken.").defaultValue(true).build());
    private final Setting<Integer> durabilityThreshold = sg.add(new IntSetting.Builder().name("durability-threshold").description("Warn once durability drops below this.").defaultValue(20).range(1, 200).sliderRange(5, 100).visible(durability::get).build());

    private boolean wasRaining;
    private int cd;

    public Notifiers() { super(shama.addon.ShamaAddon.MISC, "notifiers++", "Rain + low-durability chat alerts."); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (rain.get()) {
            boolean r = mc.world.isRaining();
            if (r != wasRaining) { shama.addon.util.Chat.info("[Notify] rain %s", r ? "started" : "stopped"); wasRaining = r; }
        }
        if (durability.get() && cd-- <= 0) {
            var st = mc.player.getMainHandStack();
            if (st.isDamageable() && (st.getMaxDamage() - st.getDamage()) <= durabilityThreshold.get()) {
                shama.addon.util.Chat.warning("[Notify] held item low durability (%d left)", st.getMaxDamage() - st.getDamage());
                cd = 100;
            }
        }
    }
}
