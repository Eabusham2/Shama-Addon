package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class HostileEsp extends Module {
    public enum Mode {
        Box,
        Wireframe
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgTracer = settings.createGroup("Tracers");

    // General — keep the concrete type so add()'s generic inference resolves cleanly.
    private final EntityTypeListSetting entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which hostile mobs to show. Empty = all hostile mobs.")
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Only show mobs within this distance.")
        .defaultValue(128)
        .min(0)
        .sliderMax(256)
        .build()
    );

    // Render
    private final Setting<Mode> mode = sgRender.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How the mobs are rendered.")
        .defaultValue(Mode.Box)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill/side color.")
        .defaultValue(new SettingColor(255, 0, 0, 30))
        .visible(() -> mode.get() == Mode.Box)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    // Tracers
    private final Setting<Boolean> tracers = sgTracer.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your camera to each hostile mob.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgTracer.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer lines.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(tracers::get)
        .build()
    );

    private final List<Entity> targets = new ArrayList<>();

    public HostileEsp() {
        super(Categories.Render, "hostile-esp", "ESP for hostile mobs with an optional tracer.");
    }

    @Override
    public String getInfoString() {
        return Integer.toString(targets.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        targets.clear();
        if (mc.world == null || mc.player == null) return;

        double rangeSq = range.get() * range.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof Monster)) continue;
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;

            if (!entities.get().isEmpty() && !entities.get().contains(entity.getType())) continue;

            if (mc.player.squaredDistanceTo(entity) > rangeSq) continue;

            targets.add(entity);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (targets.isEmpty()) return;

        for (Entity entity : targets) {
            // Boxes / wireframe
            if (mode.get() == Mode.Box) {
                Box box = entity.getBoundingBox();
                event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            } else {
                WireframeEntityRenderer.render(event, entity, 1, sideColor.get(), lineColor.get(), shapeMode.get());
            }

            // Tracers
            if (tracers.get()) {
                Vec3d pos = entity.getLerpedPos(event.tickDelta).add(0, entity.getHeight() / 2, 0);
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.x, pos.y, pos.z,
                    tracerColor.get()
                );
            }
        }
    }
}
