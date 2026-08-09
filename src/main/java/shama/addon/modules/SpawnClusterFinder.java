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
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawn Cluster Finder++ — periodically counts live hostile mobs per chunk and boxes
 * any chunk with at least min-spawns present at once, revealing unusually dense mob
 * activity (grinder / farm / spawner) even when the spawner block isn't visible.
 * Ported from a mob-tracking scanner (renamed from its original troll branding).
 */
public class SpawnClusterFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> minSpawns = sg.add(new IntSetting.Builder()
        .name("min-spawns").description("Minimum hostile mobs in a chunk at once to flag it.")
        .defaultValue(3).range(2, 50).sliderRange(2, 30).build());

    private final Setting<Integer> rescanTicks = sg.add(new IntSetting.Builder()
        .name("rescan-ticks").description("Ticks between full rescans.").defaultValue(40).range(5, 100).sliderRange(5, 60).build());

    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder()
        .name("color").description("Highlight colour.").defaultValue(new SettingColor(255, 0, 0, 90)).build());

    private final Map<Long, Integer> counts = new ConcurrentHashMap<>();
    private int tick;

    public SpawnClusterFinder() {
        super(shama.addon.ShamaAddon.HUNT, "spawn-cluster-finder++", "Boxes chunks with unusually dense live hostile-mob counts.");
    }

    @Override
    public void onActivate() { counts.clear(); tick = 0; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        if (tick++ % Math.max(1, rescanTicks.get()) != 0) return;

        Map<Long, Integer> next = new ConcurrentHashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof HostileEntity) || !e.isAlive()) continue;
            long key = new ChunkPos(e.getBlockPos()).toLong();
            next.merge(key, 1, Integer::sum);
        }
        counts.clear();
        counts.putAll(next);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (counts.isEmpty()) return;
        Color c = color.get();
        Color line = new Color(c.r, c.g, c.b, 255);
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() < minSpawns.get()) continue;
            ChunkPos cp = new ChunkPos(e.getKey());
            double x0 = cp.x * 16, z0 = cp.z * 16;
            event.renderer.box(x0, 60, z0, x0 + 16, 61, z0 + 16, c, line, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() { return counts.size() + " chunks"; }
}
