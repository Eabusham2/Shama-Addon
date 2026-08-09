package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Spawner Finder++ — ESPs mob spawners and 1.21 trial spawners as chunks load, at any Y
 * (dungeons, XP/loot farms, trial chambers). Consolidates the assorted spawner-detect /
 * spawner-notifier finders into one module. Reads spawners straight from chunk data, so
 * it catches ones the client isn't rendering.
 */
public class SpawnerFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> mobSpawners = sgGeneral.add(new BoolSetting.Builder()
        .name("mob-spawners").description("Classic mob spawners (dungeons, XP farms).").defaultValue(true).build());

    private final Setting<Boolean> trialSpawners = sgGeneral.add(new BoolSetting.Builder()
        .name("trial-spawners").description("1.21 trial spawners (trial chambers).").defaultValue(true).build());

    private final Setting<Boolean> belowYOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("below-y-only")
        .description("Only report spawners under the height below. Handy when you're hunting stashes and don't care about surface dungeons.")
        .defaultValue(false).build());
    private final Setting<Integer> belowY = sgGeneral.add(new IntSetting.Builder()
        .name("below-y")
        .description("The height that cut-off uses.")
        .defaultValue(0).min(-64).max(320).sliderRange(-64, 64).visible(belowYOnly::get).build());

    private final Setting<Boolean> sound = sgRender.add(new BoolSetting.Builder().name("sound").description("Play a ping when a new spawner is found.").defaultValue(false).build());
    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
        .name("chat").description("Print each new spawner to chat.").defaultValue(true).build());

    private final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder()
        .name("render-distance").description("How far away (in blocks) things are still drawn.").defaultValue(256).min(16).sliderRange(32, 512).build());

    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("beam").description("Draw a tall vertical beam above each spawner.").defaultValue(false).build());
    private final Setting<Boolean> nametags = sgRender.add(new BoolSetting.Builder()
        .name("nametags").description("Distance label over each spawner.").defaultValue(false).build());
    private final Setting<Boolean> chunkOutline = sgRender.add(new BoolSetting.Builder()
        .name("chunk-outline").description("Outline the chunk each spawner is in.").defaultValue(false).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each highlighted thing.").defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("Colour of the filled part of the box.").defaultValue(new SettingColor(255, 130, 0, 45)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(255, 130, 0, 255)).build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color").description("Colour of the tracer lines.").defaultValue(new SettingColor(255, 130, 0, 160)).visible(tracers::get).build());

    private final Map<BlockPos, String> found = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> alerts = new ConcurrentLinkedQueue<>();

    public SpawnerFinder() {
        super(shama.addon.ShamaAddon.HUNT, "spawner-finder++", "Highlights mob and trial spawners through terrain, at any height.");
    }

    @Override
    public void onActivate() { found.clear(); alerts.clear(); }

    @Override
    public void onDeactivate() { found.clear(); alerts.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
            BlockEntityType<?> type = e.getValue().getType();
            String label = null;
            if (mobSpawners.get() && type == BlockEntityType.MOB_SPAWNER) label = "spawner";
            else if (trialSpawners.get() && type == BlockEntityType.TRIAL_SPAWNER) label = "trial spawner";
            if (label == null) continue;
            BlockPos pos = e.getKey().toImmutable();
            if (belowYOnly.get() && pos.getY() > belowY.get()) continue;   // stash hunting: ignore surface spawners
            if (found.put(pos, label) == null) {
                playPing();
                if (chatAlert.get())
                    alerts.add(String.format("[SpawnerFinder] %s at (%d, %d, %d)", label, pos.getX(), pos.getY(), pos.getZ()));
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        String line; int n = 0;
        while ((line = alerts.poll()) != null && n++ < 20) shama.addon.util.Chat.info(line);
    }

    private void playPing() {
        if (sound.get() && mc.world != null && mc.player != null)
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!nametags.get() || mc.player == null) return;
        var text = meteordevelopment.meteorclient.renderer.text.TextRenderer.get();
        for (BlockPos pos : found.keySet()) {
            org.joml.Vector3d p = new org.joml.Vector3d(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
            double dist = Math.sqrt(mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (NametagUtils.to2D(p, 1.0)) {
                String label = String.format("Spawner %.0fm", dist);
                double w = text.getWidth(label);
                NametagUtils.begin(p);
                text.beginBig();
                text.render(label, -w / 2, 0, new Color(255, 0, 255, 255));
                text.end();
                NametagUtils.end();
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || found.isEmpty()) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Vec3d eye = mc.player.getEyePos();
        Color fill = sideColor.get(), line = lineColor.get(), tc = tracerColor.get();
        for (BlockPos pos : found.keySet()) {
            double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;
            event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, fill, line, shapeMode.get(), 0);
            if (tracers.get())
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, tc);
            if (beam.get())
                event.renderer.line(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, pos.getX() + 0.5, pos.getY() + 32, pos.getZ() + 0.5, line);
            if (chunkOutline.get() && mc.world != null) {
                double cx0 = (pos.getX() >> 4) * 16, cz0 = (pos.getZ() >> 4) * 16;
                event.renderer.box(cx0, pos.getY() - 0.5, cz0, cx0 + 16, pos.getY() + 0.5, cz0 + 16, fill, line, shapeMode.get(), 0);
            }
        }
    }

    @Override
    public String getInfoString() { return String.valueOf(found.size()); }
}
