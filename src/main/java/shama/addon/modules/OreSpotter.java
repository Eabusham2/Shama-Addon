package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ore Spotter — looks exactly like Ore Sim++ (same boxes, shape modes, per-ore
 * colors, ore toggles) but it's real X-ray, not a seed simulation. It scans the
 * actually-loaded blocks around you for real ore blocks and boxes them. No seed
 * needed and always correct for loaded chunks — but it can only see what's loaded,
 * where Ore Sim predicts ahead from the seed. Use whichever fits.
 */
public class OreSpotter extends Module {
    private static final String[] NAMES = {
        "Coal", "Iron", "Gold", "Redstone", "Diamond", "Lapis", "Copper", "Emerald", "Quartz", "Ancient Debris"
    };
    private static final Color[] COLORS = {
        new Color(47, 44, 54), new Color(236, 173, 119), new Color(247, 229, 30), new Color(245, 7, 23),
        new Color(33, 244, 255), new Color(8, 26, 189), new Color(239, 151, 0), new Color(27, 209, 45),
        new Color(205, 205, 205), new Color(209, 27, 245)
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOres = settings.createGroup("Ores");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Chunks around you to keep ore results for. Scanning happens once per chunk as it loads; this only controls how far results are kept/rendered.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 12)
        .build()
    );

    // Per-ore toggles (own settings; can't reuse Ore Sim's static ones).
    @SuppressWarnings("unchecked")
    private final Setting<Boolean>[] toggles = new Setting[NAMES.length];

    private final Setting<Boolean> colorByOre = sgRender.add(new BoolSetting.Builder()
        .name("color-by-ore")
        .description("Color-code each box by ore type. Off = use the single fill/line colors below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Draw outlines, filled sides, or both.")
        .defaultValue(ShapeMode.Lines)
        .build()
    );

    private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Fill opacity used when color-by-ore is on.")
        .defaultValue(40)
        .range(0, 255)
        .sliderRange(0, 255)
        .visible(colorByOre::get)
        .build()
    );

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Box fill color (used when color-by-ore is off).")
        .defaultValue(new SettingColor(255, 255, 255, 40))
        .visible(() -> !colorByOre.get())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Box outline color (used when color-by-ore is off).")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(() -> !colorByOre.get())
        .build()
    );

    private final Setting<Integer> glow = sgRender.add(new IntSetting.Builder()
        .name("glow")
        .description("How strongly each ore glows through terrain. 0 is off; higher makes the halo brighter and easier to pick out at a distance.")
        .defaultValue(0).min(0).max(100).sliderRange(0, 100)
        .build()
    );

    private final SettingGroup sgAlerts = settings.createGroup("Alerts");
    private final Setting<Boolean> popup = sgAlerts.add(new BoolSetting.Builder()
        .name("popup")
        .description("Put a title on screen when new ore turns up, naming what and how much. A vein arriving at once is one popup, not one per block.")
        .defaultValue(true).build());
    private final Setting<Boolean> sound = sgAlerts.add(new BoolSetting.Builder()
        .name("sound")
        .description("Play a chime when new ore turns up.")
        .defaultValue(true).build());
    private final Setting<Double> volume = sgAlerts.add(new DoubleSetting.Builder()
        .name("volume").description("How loud that chime is.")
        .defaultValue(1.5).min(0.1).max(10).sliderRange(0.5, 5).decimalPlaces(1).visible(sound::get).build());
    private final Setting<Boolean> chatAlert = sgAlerts.add(new BoolSetting.Builder()
        .name("chat")
        .description("Also note each batch of new ore in chat, with where it is.")
        .defaultValue(false).build());
    private final Setting<Boolean> netherOnlyDebris = sgAlerts.add(new BoolSetting.Builder()
        .name("debris-nether-only")
        .description("When ancient debris is the only ore switched on, only run in the Nether. Debris does not generate anywhere else, so outside it there is nothing to find and no reason to spend time scanning.")
        .defaultValue(true).build());

    // Ore positions kept per chunk so we can add on chunk-load and drop on chunk-unload cheaply.
    // Each entry is packed as {x, y, z, category}.
    private final Map<Long, List<int[]>> oresByChunk = new ConcurrentHashMap<>();

    {
        for (int i = 0; i < NAMES.length; i++) {
            boolean defaultOn = NAMES[i].equals("Diamond") || NAMES[i].equals("Ancient Debris");
            toggles[i] = sgOres.add(new BoolSetting.Builder()
                .name(NAMES[i].toLowerCase().replace(' ', '-'))
                .description("Highlight " + NAMES[i].toLowerCase() + (i == 9 ? "" : " ore") + ".")
                .defaultValue(defaultOn)
                .build()
            );
        }
    }

    public OreSpotter() {
        super(shama.addon.ShamaAddon.HUNT, "ore-spotter++", "X-ray that highlights ores through terrain, kept smooth so it doesn't stutter as you move.");
    }

    /** Category per block type, worked out once — the scan asks this for every block it reads. */
    private static final Map<net.minecraft.block.Block, Integer> CATEGORY = new ConcurrentHashMap<>();

    private static int categoryOfBlock(net.minecraft.block.Block b) {
        Integer c = CATEGORY.get(b);
        if (c == null) { c = categoryOf(shama.addon.util.BlockPaths.of(b)); CATEGORY.put(b, c); }
        return c;
    }

    /** Which ore categories are switched on, as bits. The scan only looks for these. */
    private volatile int enabledMask;
    private int computeMask() {
        int mask = 0;
        for (int i = 0; i < toggles.length; i++) if (toggles[i].get()) mask |= 1 << i;
        return mask;
    }

    private java.util.concurrent.ExecutorService scanner;
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> newFinds = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final int[] pendingCounts = new int[NAMES.length];
    private int[] pendingAt;
    private long lastAlert;
    private boolean wasActive;
    private Object lastWorld;

    /** Ancient debris on its own only exists in the Nether, so anywhere else there is nothing to do. */
    private boolean active() {
        if (mc.world == null) return false;
        if (!netherOnlyDebris.get()) return true;
        if (enabledMask != (1 << 9)) return true;
        return mc.world.getRegistryKey() == net.minecraft.world.World.NETHER;
    }

    @Override
    public void onActivate() {
        oresByChunk.clear(); announced.clear(); newFinds.clear();
        java.util.Arrays.fill(pendingCounts, 0);
        enabledMask = computeMask();
        scanner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "shama-orespotter");
            t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        wasActive = false;
        lastWorld = mc.world;
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        oresByChunk.clear(); announced.clear(); newFinds.clear();
    }

    /** Scan everything already loaded around you — on switching on, or when the ore list changes. */
    private void seed() {
        if (mc.world == null || mc.player == null) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = range.get();
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                var ch = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (ch instanceof WorldChunk wc) queue(wc);
            }
    }

    private void queue(WorldChunk chunk) {
        var s = scanner;
        if (s == null || s.isShutdown()) return;
        final int mask = enabledMask;
        try { s.submit(() -> { try { scanChunk(chunk, mask); } catch (Throwable ignored) {} }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) { lastWorld = mc.world; oresByChunk.clear(); announced.clear(); wasActive = false; }

        int mask = computeMask();
        boolean listChanged = mask != enabledMask;
        enabledMask = mask;
        boolean now = active();
        if (!now) {
            if (wasActive) oresByChunk.clear();
            wasActive = false;
            newFinds.clear();
            return;
        }
        if (!wasActive || listChanged) { oresByChunk.clear(); seed(); }
        wasActive = true;

        // gather what the scanner found and raise one alert for the lot, at most every two seconds
        int[] f;
        while ((f = newFinds.poll()) != null) {
            if ((enabledMask & (1 << f[3])) == 0) continue;
            pendingCounts[f[3]]++;
            if (pendingAt == null) pendingAt = f;
        }
        long t = System.currentTimeMillis();
        if (pendingAt != null && t - lastAlert >= 2000) {
            StringBuilder what = new StringBuilder();
            for (int i = 0; i < NAMES.length; i++) {
                if (pendingCounts[i] == 0) continue;
                if (what.length() > 0) what.append(", ");
                what.append(pendingCounts[i]).append(' ').append(NAMES[i]);
            }
            alert(what.toString(), pendingAt);
            java.util.Arrays.fill(pendingCounts, 0);
            pendingAt = null;
            lastAlert = t;
        }
    }

    private void alert(String what, int[] at) {
        if (chatAlert.get())
            shama.addon.util.Chat.info("[OreSpotter] %s near %d, %d, %d", what, at[0], at[1], at[2]);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 30, 8);
            mc.inGameHud.setTitle(net.minecraft.text.Text.literal("Ore spotted").formatted(net.minecraft.util.Formatting.AQUA));
            mc.inGameHud.setSubtitle(net.minecraft.text.Text.literal(what).formatted(net.minecraft.util.Formatting.WHITE));
        }
        if (sound.get() && mc.world != null && mc.player != null)
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_CHIME,
                net.minecraft.sound.SoundCategory.PLAYERS, volume.get().floatValue(), 1.4f);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!wasActive) return;
        if (event.chunk() instanceof WorldChunk wc) queue(wc);
        pruneFar();
    }

    /** Single-block updates (breaks/places) — patch just that block instead of re-scanning. */
    @EventHandler
    private void onBlockUpdate(PacketEvent.Receive event) {
        if (mc.world == null || !wasActive || !(event.packet instanceof BlockUpdateS2CPacket p)) return;
        BlockPos pos = p.getPos();
        long ck = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<int[]> list = oresByChunk.get(ck);
        if (list != null) list.removeIf(a -> a[0] == pos.getX() && a[1] == pos.getY() && a[2] == pos.getZ()); // drop old
        int cat = categoryOfBlock(p.getState().getBlock());
        if (cat >= 0 && (enabledMask & (1 << cat)) != 0) {
            int[] ore = new int[]{pos.getX(), pos.getY(), pos.getZ(), cat};
            oresByChunk.computeIfAbsent(ck, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(ore);
            if (announced.add(pos.asLong())) newFinds.add(ore);
        }
    }

    /**
     * Find the switched-on ores in one chunk. Runs on the scanner thread.
     *
     * Each 16-block section first asks its palette whether it holds any of those ores at all, and is
     * skipped outright if not. Most sections hold none of what you are looking for, so this is the
     * difference between reading every block in the chunk and reading a handful of sections.
     */
    private void scanChunk(WorldChunk chunk, int mask) {
        if (mask == 0) return;
        long ck = chunk.getPos().toLong();
        List<int[]> list = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<int[]> fresh = new ArrayList<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
        for (int si = 0; si < sections.length; si++) {
            net.minecraft.world.chunk.ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty()) continue;
            if (!sec.hasAny(st -> { int c = categoryOfBlock(st.getBlock()); return c >= 0 && (mask & (1 << c)) != 0; })) continue;
            int sy = chunk.sectionIndexToCoord(si) << 4;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                BlockState bs = sec.getBlockState(x, y, z);
                if (bs.isAir()) continue;
                int cat = categoryOfBlock(bs.getBlock());
                if (cat < 0 || (mask & (1 << cat)) == 0) continue;
                int[] ore = new int[]{bx + x, sy + y, bz + z, cat};
                list.add(ore);
                if (announced.add(BlockPos.asLong(ore[0], ore[1], ore[2]))) fresh.add(ore);
            }
        }
        if (list.isEmpty()) oresByChunk.remove(ck); else oresByChunk.put(ck, list);
        newFinds.addAll(fresh);
        if (announced.size() > 200_000) announced.clear();
    }

    /** Cheap distance prune (chunk-coord math only, no block scanning). */
    private void pruneFar() {
        if (mc.player == null) return;
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = range.get() + 2;
        oresByChunk.keySet().removeIf(k -> {
            int cx = ChunkPos.getPackedX(k), cz = ChunkPos.getPackedZ(k);
            return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r;
        });
    }

    private static int categoryOf(String p) {
        if (p.contains("coal_ore")) return 0;
        if (p.contains("iron_ore")) return 1;
        if (p.contains("gold_ore")) return 2;        // also catches nether_gold_ore
        if (p.contains("redstone_ore")) return 3;
        if (p.contains("diamond_ore")) return 4;
        if (p.contains("lapis_ore")) return 5;
        if (p.contains("copper_ore")) return 6;
        if (p.contains("emerald_ore")) return 7;
        if (p.contains("quartz_ore")) return 8;       // nether_quartz_ore
        if (p.equals("ancient_debris")) return 9;
        return -1;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (oresByChunk.isEmpty() || !wasActive) return;
        ShapeMode mode = shapeMode.get();
        boolean perOre = colorByOre.get();
        // one set of colours per frame rather than two new ones for every ore box
        Color[] lines = new Color[NAMES.length], sides = new Color[NAMES.length], halos = new Color[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            if (perOre) {
                Color c = COLORS[i];
                lines[i] = new Color(c.r, c.g, c.b, 255);
                sides[i] = new Color(c.r, c.g, c.b, fillOpacity.get());
            } else {
                lines[i] = lineColor.get();
                sides[i] = fillColor.get();
            }
            halos[i] = new Color(lines[i].r, lines[i].g, lines[i].b, Math.min(255, 15 + glow.get() * 2));
        }
        Color clear = new Color(0, 0, 0, 0);
        boolean glowing = glow.get() > 0;
        for (List<int[]> list : oresByChunk.values()) {
            for (int[] a : list) {
                int cat = a[3];
                if (!toggles[cat].get()) continue;
                int px = a[0], py = a[1], pz = a[2];
                if (glowing)
                    event.renderer.box(px - 0.15, py - 0.15, pz - 0.15, px + 1.15, py + 1.15, pz + 1.15,
                        halos[cat], clear, ShapeMode.Sides, 0);
                event.renderer.box(px, py, pz, px + 1, py + 1, pz + 1, sides[cat], lines[cat], mode, 0);
            }
        }
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (List<int[]> l : oresByChunk.values()) n += l.size();
        return n + " ores";
    }
}
