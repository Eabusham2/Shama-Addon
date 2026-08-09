package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * Ocean Monument Finder++ — their actual method: guardians only exist near ocean
 * monuments, so any guardian entity in range reveals a nearby monument even before
 * you've seen its prismarine structure. Tracers to each detected guardian.
 */
public class OceanMonumentFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Double> scanRadius = sg.add(new DoubleSetting.Builder()
        .name("scan-radius").description("How far out to scan, in chunks.").defaultValue(128).min(16).sliderRange(32, 256).build());

    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder()
        .name("color").description("Highlight colour.").defaultValue(new SettingColor(0, 180, 255, 200)).build());

    private final Set<Integer> found = new HashSet<>();

    public OceanMonumentFinder() {
        super(shama.addon.ShamaAddon.HUNT, "ocean-monument-finder++", "Detects nearby ocean monuments by spotting guardian entities (their only habitat).");
    }

    @Override
    public void onActivate() { found.clear(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        double r = scanRadius.get();
        Box box = mc.player.getBoundingBox().expand(r);
        for (Entity e : mc.world.getOtherEntities(mc.player, box)) {
            if (e instanceof GuardianEntity) found.add(e.getId());
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || found.isEmpty()) return;
        Vec3d cam = RenderUtils.center;
        for (Integer id : found) {
            Entity e = mc.world.getEntityById(id);
            if (e == null) continue;
            event.renderer.line(cam.x, cam.y, cam.z, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), color.get());
        }
    }

    @Override
    public String getInfoString() { return found.size() + " guardians"; }
}
