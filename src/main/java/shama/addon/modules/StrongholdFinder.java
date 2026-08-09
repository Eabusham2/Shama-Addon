package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;

/**
 * Stronghold Finder — works out where the stronghold is from your ender eye throws.
 *
 * Throw an eye, note the line it flies along, walk a few hundred blocks sideways, throw again:
 * where the two lines cross is the stronghold. This does that maths for you and draws the spot.
 */
public class StrongholdFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> autoThrowDetect = sg.add(new BoolSetting.Builder()
        .name("auto-detect-throws")
        .description("Pick up eye throws automatically as you make them. Turn off to only record throws with the bind.")
        .defaultValue(true).build());

    private final Setting<Integer> minSeparation = sg.add(new IntSetting.Builder()
        .name("min-separation")
        .description("How far apart two throws must be (in blocks) before a guess is trusted. Throws made too close together give wildly inaccurate results.")
        .defaultValue(200).min(10).max(2000).sliderRange(50, 800).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat")
        .description("Print the estimated coordinates in chat.")
        .defaultValue(true).build());

    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Draw a marker at the estimated stronghold.").defaultValue(true).build());
    private final Setting<Double> markerY = sgRender.add(new DoubleSetting.Builder()
        .name("marker-y").description("Height to draw the marker at.").defaultValue(64).min(-64).max(320).sliderRange(-64, 200).visible(render::get).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Colour of the marker.").defaultValue(new SettingColor(0, 255, 200, 220)).visible(render::get).build());
    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer").description("Draw a line from you to the estimate.").defaultValue(true).visible(render::get).build());

    /** One recorded throw: where it was thrown from and which way it went (unit-ish vector). */
    private record Throw(double x, double z, double dx, double dz) {}

    private Throw first, second;
    private Double estX, estZ;
    private boolean sawEye;

    public StrongholdFinder() {
        super(shama.addon.ShamaAddon.HUNT, "stronghold-finder++",
            "Works out where the stronghold is from two ender eye throws and marks the spot.");
    }

    @Override
    public void onActivate() { reset(); }

    private void reset() { first = second = null; estX = estZ = null; sawEye = false; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || !autoThrowDetect.get()) return;

        // Matched by entity type rather than by class: the eye-of-ender class has moved between
        // packages across versions, and the type constant is stable.
        Entity eye = null;
        for (Entity e : mc.world.getEntities()) {
            if (e.getType() == net.minecraft.entity.EntityType.EYE_OF_ENDER) { eye = e; break; }
        }

        if (eye != null) {
            // Track the eye while it flies; its velocity is the bearing to the stronghold.
            double dx = eye.getVelocity().x, dz = eye.getVelocity().z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                pendingX = eye.getX(); pendingZ = eye.getZ();
                pendingDX = dx / len; pendingDZ = dz / len;
                sawEye = true;
            }
        } else if (sawEye) {
            // The eye just vanished — commit the last bearing we saw as a throw.
            sawEye = false;
            record(new Throw(pendingX, pendingZ, pendingDX, pendingDZ));
        }
    }

    private double pendingX, pendingZ, pendingDX, pendingDZ;

    private void record(Throw t) {
        if (first == null) {
            first = t;
            if (chat.get()) shama.addon.util.Chat.info("[StrongholdFinder] first throw recorded — walk at least %d blocks sideways, then throw again.", minSeparation.get());
            return;
        }
        second = t;
        double sep = Math.hypot(second.x() - first.x(), second.z() - first.z());
        solve();
        if (chat.get()) {
            if (estX == null) shama.addon.util.Chat.warning("[StrongholdFinder] those two throws are nearly parallel — walk further sideways and throw again.");
            else if (sep < minSeparation.get())
                shama.addon.util.Chat.warning("[StrongholdFinder] rough guess %.0f, %.0f (only %.0f blocks apart — walk further and throw again for accuracy).", estX, estZ, sep);
            else shama.addon.util.Chat.info("[StrongholdFinder] stronghold is around %.0f, %.0f", estX, estZ);
        }
        // the newer throw becomes the anchor for the next one, so repeated throws keep refining
        first = second;
    }

    /** Intersect the two bearing lines. Null when they're too close to parallel to trust. */
    private void solve() {
        estX = estZ = null;
        if (first == null || second == null) return;
        double denom = first.dx() * second.dz() - first.dz() * second.dx();
        if (Math.abs(denom) < 1e-6) return;                       // parallel: no usable crossing
        double t = ((second.x() - first.x()) * second.dz() - (second.z() - first.z()) * second.dx()) / denom;
        estX = first.x() + first.dx() * t;
        estZ = first.z() + first.dz() * t;
        if (!Double.isFinite(estX) || !Double.isFinite(estZ)) { estX = estZ = null; }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || estX == null || estZ == null || mc.player == null) return;
        double y = markerY.get();
        Color c = color.get();
        Color fill = new Color(c.r, c.g, c.b, 60);
        // a chunk-sized box on the estimate (strongholds are big, so a point marker would be misleading)
        double x0 = Math.floor(estX / 16) * 16, z0 = Math.floor(estZ / 16) * 16;
        event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, fill, c, ShapeMode.Both, 0);
        if (tracer.get()) {
            var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;
            event.renderer.line(cam.x, cam.y, cam.z, estX, y, estZ, c);
        }
    }

    @Override
    public String getInfoString() {
        if (estX == null) return first == null ? "no throws" : "1 throw";
        return String.format("%.0f, %.0f", estX, estZ);
    }
}
