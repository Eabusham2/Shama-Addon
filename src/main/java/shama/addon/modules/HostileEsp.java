package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class HostileEsp extends Module {
    public enum HealthColor {
        Off,
        Outline,
        Fill,
        Both
    }

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

    private final Setting<Boolean> infiniteRange = sgGeneral.add(new BoolSetting.Builder()
        .name("infinite-range")
        .description("Ignore the range limit and show every loaded hostile mob. Note: the client only knows about entities the server has sent, so this reaches as far as your entity render distance, not literally infinite.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Only show mobs within this distance.")
        .defaultValue(128)
        .min(0)
        .sliderMax(256)
        .visible(() -> !infiniteRange.get())
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
        .name("fill-color")
        .description("The fill/side color.")
        .defaultValue(new SettingColor(255, 0, 0, 30))
        .visible(() -> mode.get() == Mode.Box)
        .build()
    );

    private final Setting<HealthColor> healthColor = sgRender.add(new EnumSetting.Builder<HealthColor>()
        .name("health-color")
        .description("Tint by health (green at full, fading to red). Choose whether it colors the Outline, the Fill, or Both. Off uses the fixed colors below.")
        .defaultValue(HealthColor.Off)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color (when health-color isn't tinting the outline).")
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
        super(shama.addon.ShamaAddon.MISC, "hostile-esp++", "ESP for hostile mobs with an optional tracer.");
    }

    @Override
    public String getInfoString() {
        return Integer.toString(targets.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        targets.clear();
        if (mc.world == null || mc.player == null) return;

        boolean noLimit = infiniteRange.get();
        double rangeSq = range.get() * range.get();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof Monster)) continue;
            if (entity == mc.player) continue;
            if (!entity.isAlive()) continue;

            if (!entities.get().isEmpty() && !entities.get().contains(entity.getType())) continue;

            if (!noLimit && mc.player.squaredDistanceTo(entity) > rangeSq) continue;

            targets.add(entity);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (targets.isEmpty()) return;

        for (Entity entity : targets) {
            SettingColor line = lineFor(entity);
            SettingColor side = sideFor(entity);

            // Boxes / wireframe
            if (mode.get() == Mode.Box) {
                Box box = entity.getBoundingBox();
                event.renderer.box(box, side, line, shapeMode.get(), 0);
            } else {
                WireframeEntityRenderer.render(event, entity, 1, side, line, shapeMode.get());
            }

            // Tracers — use the health tint when it's applied to the outline.
            if (tracers.get()) {
                Vec3d pos = entity.getLerpedPos(event.tickDelta).add(0, entity.getHeight() / 2, 0);
                boolean tintTracer = healthColor.get() == HealthColor.Outline || healthColor.get() == HealthColor.Both;
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    pos.x, pos.y, pos.z,
                    tintTracer ? line : tracerColor.get()
                );
            }
        }
    }

    // Outline color: health gradient if health-color tints the outline, else fixed.
    private SettingColor lineFor(Entity entity) {
        if ((healthColor.get() == HealthColor.Outline || healthColor.get() == HealthColor.Both)
            && entity instanceof LivingEntity living) {
            return healthGradient(living, 255);
        }
        return lineColor.get();
    }

    // Fill color: health gradient if health-color tints the fill, else fixed.
    private SettingColor sideFor(Entity entity) {
        if ((healthColor.get() == HealthColor.Fill || healthColor.get() == HealthColor.Both)
            && entity instanceof LivingEntity living) {
            return healthGradient(living, sideColor.get().a);
        }
        return sideColor.get();
    }

    // green (full) -> yellow -> red (empty), at the given alpha.
    private SettingColor healthGradient(LivingEntity living, int alpha) {
        float max = living.getMaxHealth();
        float frac = max <= 0 ? 1f : Math.max(0f, Math.min(1f, living.getHealth() / max));
        int r = (int) ((1f - frac) * 255);
        int g = (int) (frac * 255);
        return new SettingColor(r, g, 0, alpha);
    }
}
