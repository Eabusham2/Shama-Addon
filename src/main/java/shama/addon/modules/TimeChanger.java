package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/** Time Changer — sets a custom client-side world time. */
public class TimeChanger extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> time = sg.add(new IntSetting.Builder().name("time").description("Time of day to force (0 = dawn, 6000 = noon, 18000 = midnight).").defaultValue(6000).min(0).max(24000).sliderRange(0, 24000).build());

    public TimeChanger() { super(shama.addon.ShamaAddon.MISC, "time-changer++", "Force a custom client-side time of day."); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        if (mc.world.getLevelProperties() instanceof net.minecraft.client.world.ClientWorld.Properties props)
            props.setTimeOfDay(time.get().longValue());
    }
}
