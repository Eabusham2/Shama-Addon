package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Camera Tweaks — safe camera/view overrides applied through the game options (no fragile
 * render-pipeline mixins): a custom FOV and toggles to kill view-bobbing.
 */
public class CameraTweaks extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> customFov = sg.add(new BoolSetting.Builder().name("custom-fov").description("Force a specific FOV while active.").defaultValue(true).build());
    private final Setting<Integer> fov = sg.add(new IntSetting.Builder().name("fov").description("The field-of-view value to force.").defaultValue(90).min(30).max(160).sliderRange(30, 140).visible(customFov::get).build());
    private final Setting<Boolean> noBob = sg.add(new BoolSetting.Builder().name("no-view-bob").description("Disable view bobbing.").defaultValue(false).build());

    private int savedFov;
    private boolean savedBob;
    private boolean saved;

    public CameraTweaks() { super(shama.addon.ShamaAddon.MISC, "camera-tweaks++", "Custom FOV and view-bob overrides."); }

    @Override
    public void onActivate() {
        if (mc.options == null) return;
        savedFov = mc.options.getFov().getValue();
        savedBob = mc.options.getBobView().getValue();
        saved = true;
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null && saved) {
            mc.options.getFov().setValue(savedFov);
            mc.options.getBobView().setValue(savedBob);
        }
        saved = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.options == null) return;
        if (customFov.get()) mc.options.getFov().setValue(fov.get());
        if (noBob.get()) mc.options.getBobView().setValue(false);
    }
}
