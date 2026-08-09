package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Particle ESP++ — while you are ABOVE a Y line, watches for particles spawning BELOW another Y line.
 * Particles underground that you aren't making yourself usually mean someone is down there (furnaces,
 * spawners, redstone, brewing, mining). Highlights them with a box and/or a tracer line.
 */
public class ParticleEsp extends Module {
    public enum ColorMode { Custom, ByParticleType }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filters");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgNotify = settings.createGroup("Notifications");

    // ---- general ----
    private final Setting<Integer> particleBelowY = sgGeneral.add(new IntSetting.Builder()
        .name("particles-below-y").description("Only care about particles that spawn at or below this Y. Default 0 = underground only.")
        .defaultValue(0).min(-64).max(320).sliderRange(-64, 64).build());
    private final Setting<Integer> playerAboveY = sgGeneral.add(new IntSetting.Builder()
        .name("only-above-y").description("Only run while YOU are above this Y, so your own underground particles don't trigger it. Default 3.")
        .defaultValue(3).min(-64).max(320).sliderRange(-64, 64).build());
    private final Setting<Integer> lifetimeTicks = sgGeneral.add(new IntSetting.Builder()
        .name("lifetime").description("How many ticks a spotted particle stays highlighted before fading out.")
        .defaultValue(200).min(20).max(2000).sliderRange(40, 600).build());
    private final Setting<Integer> maxTracked = sgGeneral.add(new IntSetting.Builder()
        .name("max-tracked").description("Safety cap on how many particle spots are kept at once.")
        .defaultValue(400).min(10).max(4000).sliderRange(50, 1000).build());

    // ---- filters ----
    private final Setting<Boolean> mergeClose = sgFilter.add(new BoolSetting.Builder()
        .name("merge-close").description("Group particles that spawn near each other into one box instead of many overlapping ones.")
        .defaultValue(true).build());
    private final Setting<Double> mergeRadius = sgFilter.add(new DoubleSetting.Builder()
        .name("merge-radius").description("How close (in blocks) two particles must be to count as the same spot.")
        .defaultValue(2.0).min(0.5).max(16).sliderRange(0.5, 8).visible(mergeClose::get).build());
    private final Setting<Integer> minCount = sgFilter.add(new IntSetting.Builder()
        .name("min-particles").description("How many particles must pile up at a spot before it's shown. 1 = show every single one.")
        .defaultValue(3).min(1).max(50).sliderRange(1, 20).build());
    private final Setting<Boolean> ignoreNearMe = sgFilter.add(new BoolSetting.Builder()
        .name("ignore-near-me").description("Ignore particles right next to you (your own blocks/effects).")
        .defaultValue(true).build());
    private final Setting<Double> ignoreRadius = sgFilter.add(new DoubleSetting.Builder()
        .name("ignore-radius").description("How close counts as 'near me' (blocks).")
        .defaultValue(8.0).min(1).max(64).sliderRange(2, 32).visible(ignoreNearMe::get).build());
    private final Setting<Double> maxDistance = sgFilter.add(new DoubleSetting.Builder()
        .name("max-distance").description("Don't track particles further away than this (blocks).")
        .defaultValue(256.0).min(16).max(1024).sliderRange(32, 512).build());

    // ---- render ----
    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box").description("Draw a box on each particle spot.").defaultValue(true).build());
    private final Setting<Boolean> blockShape = sgRender.add(new BoolSetting.Builder()
        .name("block-shape")
        .description("Fill the whole block the particles came from instead of drawing a small box floating at the exact point. A solid block is far easier to pick out through terrain, and it tells you which block to actually go and break.")
        .defaultValue(true).visible(box::get).build());

    private final Setting<Double> boxSize = sgRender.add(new DoubleSetting.Builder()
        .name("box-size").description("How big each box is, in blocks. Only used when block-shape is off.").defaultValue(0.6).min(0.05).max(8).sliderRange(0.1, 3).visible(() -> box.get() && !blockShape.get()).build());
    private final Setting<Boolean> scaleWithCount = sgRender.add(new BoolSetting.Builder()
        .name("grow-with-count").description("Make the box bigger the more particles pile up at that spot. Only used when block-shape is off.").defaultValue(true).visible(() -> box.get() && !blockShape.get()).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).visible(box::get).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each particle spot.").defaultValue(false).build());
    private final Setting<Boolean> fadeOut = sgRender.add(new BoolSetting.Builder()
        .name("fade-out").description("Fade the highlight as the spot gets older.").defaultValue(true).build());
    private final Setting<ColorMode> colorMode = sgRender.add(new EnumSetting.Builder<ColorMode>()
        .name("color-mode").description("Custom = one colour you pick. By-particle-type = each particle type gets its own colour, guessed from what that particle actually looks like (flame = orange, smoke = grey, etc).")
        .defaultValue(ColorMode.ByParticleType).build());
    private final Setting<SettingColor> customColor = sgRender.add(new ColorSetting.Builder()
        .name("color").description("The colour used when colour-mode is Custom.").defaultValue(new SettingColor(0, 255, 200, 220))
        .visible(() -> colorMode.get() == ColorMode.Custom).build());
    private final Setting<Integer> realColorBlend = sgRender.add(new IntSetting.Builder()
        .name("real-color-blend")
        .description("For particles that carry a real colour in the packet (redstone dust, potion effects...), how much of that real colour to use. 100 = the particle's actual colour, 0 = ignore it and use the hand-picked colour for that type. In between = a mix. Types whose real colour is already exactly right (dust, potion effects) always use it fully.")
        .defaultValue(60).min(0).max(100).sliderRange(0, 100)
        .visible(() -> colorMode.get() == ColorMode.ByParticleType).build());
    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").description("How see-through the filled part of the box is.").defaultValue(50).min(0).max(255).sliderRange(0, 150).build());

    // ---- notifications ----
    private final Setting<Boolean> chatNotify = sgNotify.add(new BoolSetting.Builder()
        .name("chat").description("Print a chat message when a new particle spot is found.").defaultValue(false).build());
    private final Setting<Integer> notifyCooldown = sgNotify.add(new IntSetting.Builder()
        .name("notify-cooldown").description("Seconds before the same area can notify again (stops chat spam).").defaultValue(60).min(0).max(300).sliderRange(0, 60).visible(chatNotify::get).build());
    private final Setting<Boolean> sound = sgNotify.add(new BoolSetting.Builder()
        .name("sound").description("Play a ping on a new particle spot.").defaultValue(false).build());

    /** one tracked particle spot */
    private static class Spot {
        Vec3d pos; String type; int count; int age; long lastNotify; Color real;
        Spot(Vec3d p, String t, Color rc) { pos = p; type = t; count = 1; age = 0; real = rc; }
    }

    private final List<Spot> spots = new ArrayList<>();
    private final Map<String, Color> typeColors = new HashMap<>();
    // per particle-class: a function that pulls the real colour (or returns null). Built once, then cheap.
    private final Map<Class<?>, java.util.function.Function<Object, Color>> colorAccessors = new java.util.concurrent.ConcurrentHashMap<>();

    public ParticleEsp() { super(shama.addon.ShamaAddon.HUNT, "particle-esp++", "Highlights particles spawning underground while you're above ground (someone is down there)."); }

    @Override public void onActivate() { spots.clear(); }
    @Override public void onDeactivate() { spots.clear(); }

    /** average-ish colour for a particle type, derived from what the particle actually looks like in game */
    private Color colorForType(String t) {
        return typeColors.computeIfAbsent(t, k -> {
            String s = k.toLowerCase();
            if (s.contains("soul_fire") || s.contains("soul")) return new Color(80, 220, 235, 255);   // cyan soul flame
            if (s.contains("flame") || s.contains("lava") || s.contains("campfire")) return new Color(255, 145, 40, 255);
            if (s.contains("smoke") || s.contains("ash")) return new Color(120, 120, 120, 255);
            if (s.contains("enchant")) return new Color(160, 140, 255, 255);
            if (s.contains("portal")) return new Color(150, 60, 220, 255);
            if (s.contains("witch")) return new Color(120, 40, 160, 255);
            if (s.contains("happy") || s.contains("composter")) return new Color(90, 230, 90, 255);
            if (s.contains("heart")) return new Color(255, 90, 130, 255);
            if (s.contains("redstone") || s.contains("dust")) return new Color(230, 40, 40, 255);
            if (s.contains("note")) return new Color(90, 200, 255, 255);
            if (s.contains("water") || s.contains("splash") || s.contains("bubble") || s.contains("rain") || s.contains("drip")) return new Color(70, 140, 255, 255);
            if (s.contains("crit")) return new Color(220, 200, 120, 255);
            if (s.contains("sculk") || s.contains("shriek") || s.contains("vibration") || s.contains("sonic")) return new Color(20, 190, 190, 255);
            if (s.contains("glow") || s.contains("end_rod")) return new Color(240, 240, 200, 255);
            if (s.contains("snow") || s.contains("cloud") || s.contains("poof")) return new Color(230, 230, 240, 255);
            if (s.contains("squid_ink") || s.contains("ink")) return new Color(30, 30, 45, 255);
            if (s.contains("honey") || s.contains("nectar")) return new Color(240, 190, 60, 255);
            if (s.contains("spore") || s.contains("mycelium")) return new Color(180, 160, 200, 255);
            if (s.contains("damage") || s.contains("angry")) return new Color(200, 40, 40, 255);
            if (s.contains("totem")) return new Color(80, 230, 160, 255);
            if (s.contains("explos")) return new Color(255, 220, 160, 255);
            // fallback: stable pseudo-colour from the name so each type still gets its own hue
            int h = Math.abs(k.hashCode());
            return new Color(80 + h % 176, 80 + (h / 7) % 176, 80 + (h / 13) % 176, 255);
        });
    }

    /** Types whose packet colour genuinely IS what the particle looks like — always use it as-is. */
    private static final java.util.Set<String> TRUE_COLOR = java.util.Set.of(
        "dust", "dust_color_transition", "entity_effect", "ambient_entity_effect", "trail", "tinted_leaves");

    /** Decide the colour for a spot: real packet colour, hand-picked type colour, or a mix of both. */
    private Color resolveColor(Spot s) {
        Color curated = colorForType(s.type);
        if (s.real == null) return curated;                       // no real colour -> hand-picked
        if (TRUE_COLOR.contains(s.type)) return s.real;           // real colour is exactly right -> use it

        // A real colour that's almost black or almost white tells you nothing about how the
        // particle looks, so fall back to the hand-picked colour for that type.
        int lum = (s.real.r * 299 + s.real.g * 587 + s.real.b * 114) / 1000;
        if (lum < 24 || lum > 244) return curated;

        int b = realColorBlend.get();                             // 0 = curated, 100 = real
        return new Color(
            (s.real.r * b + curated.r * (100 - b)) / 100,
            (s.real.g * b + curated.g * (100 - b)) / 100,
            (s.real.b * b + curated.b * (100 - b)) / 100,
            255);
    }

    /** Pull a real RGB colour out of particles that carry one (dust/redstone, coloured effects). null if the type has no colour. */
    private static Color decode(Object v) {
        if (v instanceof Integer i) { int c = i; return new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 255); }
        if (v instanceof org.joml.Vector3f vec) return new Color((int) (vec.x * 255), (int) (vec.y * 255), (int) (vec.z * 255), 255);
        return null;
    }

    private Color extractColor(net.minecraft.particle.ParticleEffect params) {
        var fn = colorAccessors.computeIfAbsent(params.getClass(), ParticleEsp::buildAccessor);
        try { return fn.apply(params); } catch (Throwable ignored) { return null; }
    }

    /** Runs the reflection scan ONCE per particle class and returns a cheap accessor for future packets. */
    private static java.util.function.Function<Object, Color> buildAccessor(Class<?> cls) {
        try {
            for (var m : cls.getMethods()) {
                if (m.getName().equals("getColor") && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return obj -> { try { return decode(m.invoke(obj)); } catch (Throwable t) { return null; } };
                }
            }
            for (var f : cls.getFields()) {
                if (f.getName().toLowerCase().contains("color")) {
                    f.setAccessible(true);
                    return obj -> { try { return decode(f.get(obj)); } catch (Throwable t) { return null; } };
                }
            }
        } catch (Throwable ignored) {}
        return obj -> null;   // this particle type carries no colour
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (!(event.packet instanceof ParticleS2CPacket p)) return;
        if (mc.player.getY() < playerAboveY.get()) return;          // only while you're above ground
        if (p.getY() > particleBelowY.get()) return;                // only particles underground

        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        if (ignoreNearMe.get() && new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(pos) <= ignoreRadius.get()) return;
        if (new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(pos) > maxDistance.get()) return;

        String type;
        try { type = Registries.PARTICLE_TYPE.getId(p.getParameters().getType()).getPath(); }
        catch (Throwable t) { type = "unknown"; }
        Color real = extractColor(p.getParameters());

        synchronized (spots) {
            if (mergeClose.get()) {
                for (Spot s : spots) {
                    if (s.pos.distanceTo(pos) <= mergeRadius.get()) {
                        s.count++; s.age = 0; if (real != null) s.real = real;
                        // nudge the spot toward the running average of its particles
                        s.pos = s.pos.multiply(0.8).add(pos.multiply(0.2));
                        maybeNotify(s);
                        return;
                    }
                }
            }
            Spot s = new Spot(pos, type, real);
            spots.add(s);
            maybeNotify(s);
            while (spots.size() > maxTracked.get()) spots.remove(0);
        }
    }

    private void maybeNotify(Spot s) {
        if (s.count < minCount.get()) return;
        long now = System.currentTimeMillis();
        if (now - s.lastNotify < notifyCooldown.get() * 1000L) return;
        boolean first = s.lastNotify == 0;
        s.lastNotify = now;
        if (!first && notifyCooldown.get() > 0) return;   // only re-notify after cooldown
        if (chatNotify.get())
            shama.addon.util.Chat.info("[ParticleESP] %s x%d at (%.0f, %.0f, %.0f)", s.type, s.count, s.pos.x, s.pos.y, s.pos.z);
        if (sound.get() && mc.world != null && mc.player != null)
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.6f);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        synchronized (spots) {
            Iterator<Spot> it = spots.iterator();
            while (it.hasNext()) { if (++it.next().age > lifetimeTicks.get()) it.remove(); }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        synchronized (spots) {
            if (spots.isEmpty()) return;
            for (Spot s : spots) {
                if (s.count < minCount.get()) continue;

                Color base = colorMode.get() == ColorMode.Custom ? customColor.get() : resolveColor(s);
                float fade = fadeOut.get() ? 1f - (s.age / (float) lifetimeTicks.get()) : 1f;
                if (fade <= 0) continue;

                Color line = new Color(base.r, base.g, base.b, (int) (255 * fade));
                Color fill = new Color(base.r, base.g, base.b, (int) (fillAlpha.get() * fade));

                if (box.get()) {
                    if (blockShape.get()) {
                        // snap to the block the particles came out of, so you get a solid cube you can
                        // actually aim at rather than a dot hanging in mid-air
                        double bx = Math.floor(s.pos.x), by = Math.floor(s.pos.y), bz = Math.floor(s.pos.z);
                        event.renderer.box(bx, by, bz, bx + 1, by + 1, bz + 1, fill, line, shapeMode.get(), 0);
                    } else {
                        double sz = boxSize.get();
                        if (scaleWithCount.get()) sz = Math.min(sz * (1 + Math.log10(Math.max(1, s.count))), 8.0);
                        double h = sz / 2.0;
                        event.renderer.box(s.pos.x - h, s.pos.y - h, s.pos.z - h, s.pos.x + h, s.pos.y + h, s.pos.z + h, fill, line, shapeMode.get(), 0);
                    }
                }
                if (tracers.get()) {
                    var c = RenderUtils.center;
                    event.renderer.line(c.x, c.y, c.z, s.pos.x, s.pos.y, s.pos.z, line);
                }
            }
        }
    }

    @Override public String getInfoString() { synchronized (spots) { return spots.isEmpty() ? null : spots.size() + " spots"; } }
}
