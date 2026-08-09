package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

/**
 * Render Method++ — all the render-distance tricks in one, each a separate tick:
 *   • manual        — force a low render distance the whole time the module is on (old sand-debug)
 *   • y-drop        — snap render distance down when you go below a Y line, restore it when you come back up (old render-distance-y)
 *   • auto-refresh  — pulse the render distance down-then-up below a Y line to force chunks to reload (old flow-render)
 *   • krypton-light-finder — flip Krypton's light-finder on while active
 */
public class RenderDistanceBypass extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> manualMode = sg.add(new BoolSetting.Builder().name("manual").description("While this module is on, hold render distance at 'low-distance'. Turn the module off to restore.").defaultValue(false).build());
    private final Setting<Boolean> yDrop = sg.add(new BoolSetting.Builder().name("y-drop").description("Snap render distance down to 'low-distance' when you go below 'trigger-y', and restore your original when you come back above it.").defaultValue(false).build());
    private final Setting<Boolean> autoRefresh = sg.add(new BoolSetting.Builder().name("auto-refresh").description("Each time you cross below 'trigger-y', briefly drop then restore render distance to force nearby chunks to reload.").defaultValue(true).build());
    private final Setting<Boolean> kryptonLightFinder = sg.add(new BoolSetting.Builder().name("krypton-light-finder").description("Turn Krypton's light-finder on while active (only works if the Krypton mod is installed).").defaultValue(false).build());

    private final Setting<Boolean> secondLine = sg.add(new BoolSetting.Builder()
        .name("second-line")
        .description("Refresh again at a deeper height. One pass near the surface and another further down catches things the first crossing was too high to reach.")
        .defaultValue(true).build());
    private final Setting<Integer> secondY = sg.add(new IntSetting.Builder()
        .name("second-y")
        .description("The deeper height. Same rule: it fires on the way down only.")
        .defaultValue(-45).min(-64).max(320).sliderRange(-64, 64)
        .visible(secondLine::get).build());
    private final Setting<Boolean> pearlTrigger = sg.add(new BoolSetting.Builder()
        .name("catch-teleports")
        .description("Also fire when you arrive below a line without having walked through it — an ender pearl, or the server moving you. Those skip the crossing entirely, so without this a pearl straight down never refreshes.")
        .defaultValue(true).build());

    private final Setting<Integer> triggerY = sg.add(new IntSetting.Builder().name("trigger-y").description("The height that triggers a refresh. It fires the moment you cross it going down — you do not have to stay there, and it will fire again next time you come back down through it.").defaultValue(-4).min(-64).max(64).build());
    private final Setting<Integer> lowDistance = sg.add(new IntSetting.Builder().name("low-distance").description("The reduced render distance (in chunks) used by the methods above.").defaultValue(2).min(2).max(12).build());
    private final Setting<Integer> refreshTicks = sg.add(new IntSetting.Builder().name("refresh-ticks").description("How many ticks auto-refresh stays collapsed before restoring.").defaultValue(20).min(1).max(100).visible(autoRefresh::get).build());
    private final Setting<Integer> expandDelay = sg.add(new IntSetting.Builder().name("expand-delay").description("Ticks y-drop waits before restoring after you go back above trigger-y.").defaultValue(0).min(0).max(100).visible(yDrop::get).build());

    private boolean wasBelow, wasBelow2;
    private double lastY = Double.MAX_VALUE;
    private int sequence;      // auto-refresh countdown
    private int restore = -1;  // captured distance to restore
    private int expandTimer;

    private final SettingGroup sgView = settings.createGroup("Visualiser");
    private final Setting<Boolean> visualise = sgView.add(new BoolSetting.Builder()
        .name("visualise")
        .description("Shade the ground this module has forced through, anchored to the spot where it last ran rather than following you. Lets you see exactly what you've covered. Off by default.")
        .defaultValue(false).build());
    private final Setting<Integer> areaChunks = sgView.add(new IntSetting.Builder()
        .name("area-chunks")
        .description("How far the shading reaches from that spot, in chunks. Match it to your render distance to see the real coverage.")
        .defaultValue(8).min(1).max(32).sliderRange(2, 16).visible(visualise::get).build());
    private final Setting<Double> shadeY = sgView.add(new DoubleSetting.Builder()
        .name("shade-y")
        .description("Height to draw the shading at. Sea level by default so it sits where you can see it.")
        .defaultValue(63).min(-64).max(320).sliderRange(-64, 200).visible(visualise::get).build());
    private final Setting<Boolean> showGrid = sgView.add(new BoolSetting.Builder()
        .name("chunk-grid")
        .description("Draw the individual chunk squares instead of one solid block of shading.")
        .defaultValue(false).visible(visualise::get).build());
    private final Setting<Boolean> markCentre = sgView.add(new BoolSetting.Builder()
        .name("mark-centre")
        .description("Mark the chunk the coverage is measured from.")
        .defaultValue(true).visible(visualise::get).build());
    private final Setting<meteordevelopment.meteorclient.utils.render.color.SettingColor> areaColor = sgView.add(new ColorSetting.Builder()
        .name("area-color").description("Colour of the shaded area.")
        .defaultValue(new meteordevelopment.meteorclient.utils.render.color.SettingColor(0, 200, 255, 35))
        .visible(visualise::get).build());
    private final Setting<meteordevelopment.meteorclient.utils.render.color.SettingColor> centreColor = sgView.add(new ColorSetting.Builder()
        .name("centre-color").description("Colour of the centre marker.")
        .defaultValue(new meteordevelopment.meteorclient.utils.render.color.SettingColor(255, 255, 0, 220))
        .visible(markCentre::get).build());

    /** Where the last forced reload happened — the shading is measured from here, not from you. */
    private net.minecraft.util.math.ChunkPos anchor;

    @EventHandler
    private void onRenderArea(meteordevelopment.meteorclient.events.render.Render3DEvent event) {
        if (!visualise.get() || mc.player == null) return;
        net.minecraft.util.math.ChunkPos c = anchor != null ? anchor : mc.player.getChunkPos();
        int r = areaChunks.get();
        double y = shadeY.get();
        var ac = areaColor.get();
        var fill = new meteordevelopment.meteorclient.utils.render.color.Color(ac.r, ac.g, ac.b, ac.a);
        var line = new meteordevelopment.meteorclient.utils.render.color.Color(ac.r, ac.g, ac.b, Math.min(255, ac.a + 120));

        if (showGrid.get()) {
            for (int cx = c.x - r; cx <= c.x + r; cx++)
                for (int cz = c.z - r; cz <= c.z + r; cz++) {
                    double gx = cx * 16.0, gz = cz * 16.0;
                    event.renderer.box(gx, y, gz, gx + 16, y + 0.1, gz + 16, fill, line,
                        meteordevelopment.meteorclient.renderer.ShapeMode.Both, 0);
                }
        } else {
            double x0 = (c.x - r) * 16.0, z0 = (c.z - r) * 16.0;
            double x1 = (c.x + r + 1) * 16.0, z1 = (c.z + r + 1) * 16.0;
            event.renderer.box(x0, y, z0, x1, y + 0.1, z1, fill, line,
                meteordevelopment.meteorclient.renderer.ShapeMode.Both, 0);
        }

        if (markCentre.get()) {
            var cc = centreColor.get();
            double mx = c.x * 16.0, mz = c.z * 16.0;
            event.renderer.box(mx, y, mz, mx + 16, y + 0.6, mz + 16,
                new meteordevelopment.meteorclient.utils.render.color.Color(cc.r, cc.g, cc.b, 60), cc,
                meteordevelopment.meteorclient.renderer.ShapeMode.Both, 0);
        }
    }

    public RenderDistanceBypass() { super(shama.addon.ShamaAddon.PLAYER, "render-method++", "Forces chunks to reload so terrain the server sent but your client never drew shows up."); }

    private int getVD() { return mc.options.getViewDistance().getValue(); }
    private void setVD(int v) { mc.options.getViewDistance().setValue(v); }

    @Override public void onActivate() {
        if (kryptonLightFinder.get()) setKryptonLightFinder(true);
        if (manualMode.get() && mc.options != null) { restore = getVD(); setVD(lowDistance.get()); }
        if (mc.player != null) anchor = mc.player.getChunkPos();
    }

    @Override public void onDeactivate() {
        if (restore > 0 && mc.options != null) setVD(restore);
        restore = -1; sequence = 0; expandTimer = 0; wasBelow = false;
        if (kryptonLightFinder.get()) setKryptonLightFinder(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        // manual: hold low the whole time
        if (manualMode.get()) { if (getVD() != lowDistance.get()) { if (restore < 0) restore = getVD(); setVD(lowDistance.get()); } return; }

        double y = mc.player.getY();
        boolean below = y <= triggerY.get();
        boolean below2 = secondLine.get() && y <= secondY.get();

        // A pearl or a server teleport skips the crossing entirely: you were above and the next tick
        // you are well below. Treat a big downward jump as a crossing so those still refresh.
        boolean jumped = pearlTrigger.get() && lastY - y > 8.0;

        // fire once per downward crossing of either line
        boolean crossed = (below && !wasBelow) || (below2 && !wasBelow2)
            || (jumped && (below || below2));

        if (autoRefresh.get() && crossed) {
            restore = getVD(); setVD(lowDistance.get()); sequence = refreshTicks.get();
            anchor = mc.player.getChunkPos();   // shading measures from here
        }
        if (sequence > 0 && --sequence == 0 && restore > 0) { setVD(restore); restore = -1; }

        // y-drop: stay low while below, restore (after delay) when back above
        if (yDrop.get()) {
            if (below) { if (restore < 0) { restore = getVD(); if (restore <= 0) restore = 12; } setVD(lowDistance.get()); expandTimer = expandDelay.get(); }
            else if (restore > 0) { if (expandTimer > 0) expandTimer--; else { setVD(restore); restore = -1; } }
        }
        wasBelow = below;
        wasBelow2 = below2;
        lastY = y;
    }

    // Krypton light-finder toggle
    private void setKryptonLightFinder(boolean state) {
        try {
            String[] names = {"krypton", "Krypton", "krypton-plus", "KryptonPlus", "kryptonplus"};
            Module krypton = null;
            for (String name : names) { krypton = Modules.get().get(name); if (krypton != null) break; }
            if (krypton == null) return;
            String[] settingNames = {"light-finder", "lightFinder", "light_finder", "LightFinder"};
            for (String settingName : settingNames) {
                Setting<?> setting = krypton.settings.get(settingName);
                if (setting instanceof BoolSetting boolSetting) { boolSetting.set(state); return; }
            }
        } catch (Exception ignored) {}
    }
}
