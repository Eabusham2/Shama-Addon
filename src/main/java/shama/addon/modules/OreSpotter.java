package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
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

    // Ore positions kept per chunk so we can add on chunk-load and drop on chunk-unload cheaply.
    // Each entry is packed as {x, y, z, category}.
    private final Map<Long, List<int[]>> oresByChunk = new ConcurrentHashMap<>();

    {
        for (int i = 0; i < NAMES.length; i++) {
            boolean defaultOn = NAMES[i].equals("Diamond") || NAMES[i].equals("Ancient Debris");
            toggles[i] = sgOres.add(new BoolSetting.Builder()
                .name(NAMES[i].toLowerCase().replace(' ', '-'))
                .description("Show" + NAMES[i] + ".")
                .defaultValue(defaultOn)
                .build()
            );
        }
    }

    public OreSpotter() {
        super(shama.addon.ShamaAddon.HUNT, "ore-spotter++", "X-ray that highlights ores through terrain, kept smooth so it doesn't stutter as you move.");
    }

    @Override
    public void onActivate() {
        oresByChunk.clear();
        // seed from already-loaded chunks around the player
        if (mc.world != null && mc.player != null) {
            int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = range.get();
            for (int cx = pcx - r; cx <= pcx + r; cx++)
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    var ch = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (ch instanceof WorldChunk wc) scanChunk(wc);
                }
        }
    }

    @Override
    public void onDeactivate() {
        oresByChunk.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (event.chunk() instanceof WorldChunk wc) scanChunk(wc);
        pruneFar();
    }

    /** Single-block updates (breaks/places) — patch just that block instead of re-scanning. */
    @EventHandler
    private void onBlockUpdate(PacketEvent.Receive event) {
        if (mc.world == null || !(event.packet instanceof BlockUpdateS2CPacket p)) return;
        BlockPos pos = p.getPos();
        long ck = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<int[]> list = oresByChunk.get(ck);
        if (list != null) list.removeIf(a -> a[0] == pos.getX() && a[1] == pos.getY() && a[2] == pos.getZ()); // drop old
        int cat = categoryOf(shama.addon.util.BlockPaths.of(p.getState().getBlock()));
        if (cat >= 0) oresByChunk.computeIfAbsent(ck, k -> new ArrayList<>()).add(new int[]{pos.getX(), pos.getY(), pos.getZ(), cat}); // add if now ore
    }

    private void scanChunk(WorldChunk chunk) {
        long ck = chunk.getPos().toLong();
        List<int[]> list = new ArrayList<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY(), top = chunk.getTopYInclusive();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = bottom; y <= top; y++) {
            BlockState bs = chunk.getBlockState(m.set(bx + x, y, bz + z));
            if (bs.isAir()) continue;
            int cat = categoryOf(shama.addon.util.BlockPaths.of(bs.getBlock()));
            if (cat >= 0) list.add(new int[]{bx + x, y, bz + z, cat});
        }
        if (list.isEmpty()) oresByChunk.remove(ck); else oresByChunk.put(ck, list);
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

    private int categoryOf(String p) {
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
        if (oresByChunk.isEmpty()) return;
        ShapeMode mode = shapeMode.get();
        boolean perOre = colorByOre.get();
        for (List<int[]> list : oresByChunk.values()) {
            for (int[] a : list) {
                int cat = a[3];
                if (!toggles[cat].get()) continue;
                Color line, side;
                if (perOre) {
                    Color c = COLORS[cat];
                    line = new Color(c.r, c.g, c.b, 255);
                    side = new Color(c.r, c.g, c.b, fillOpacity.get());
                } else {
                    line = lineColor.get();
                    side = fillColor.get();
                }
                int px = a[0], py = a[1], pz = a[2];
                if (glow.get() > 0) {
                    Color halo = new Color(line.r, line.g, line.b, Math.min(255, 15 + glow.get() * 2));
                    event.renderer.box(px - 0.15, py - 0.15, pz - 0.15, px + 1.15, py + 1.15, pz + 1.15,
                        halo, new Color(0, 0, 0, 0), ShapeMode.Sides, 0);
                }
                event.renderer.box(px, py, pz, px + 1, py + 1, pz + 1, side, line, mode, 0);
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
