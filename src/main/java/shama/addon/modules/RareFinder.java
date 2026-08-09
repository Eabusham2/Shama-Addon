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
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Rare Finder — highlights valuable items lying on the ground or displayed in item frames,
 * with an optional beam so you can spot them from a distance.
 */
public class RareFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> findDropped = sg.add(new BoolSetting.Builder()
        .name("find-dropped").description("Highlight rare items lying on the ground.").defaultValue(true).build());
    private final Setting<Boolean> findPlaced = sg.add(new BoolSetting.Builder()
        .name("find-placed")
        .description("Also find rare blocks that have been placed in the world — a beacon someone built, a sponge wall, heads on display, gilded blackstone. These never appear as dropped items, so without this they're invisible.")
        .defaultValue(true).build());
    private final Setting<Integer> placedRange = sg.add(new IntSetting.Builder()
        .name("placed-range")
        .description("How far out to look for placed rare blocks, in blocks.")
        .defaultValue(64).min(8).max(256).sliderRange(16, 128).visible(findPlaced::get).build());
    private final Setting<SettingColor> placedColor = sg.add(new ColorSetting.Builder()
        .name("placed-color").description("Colour used for placed rare blocks.")
        .defaultValue(new SettingColor(255, 160, 0, 255)).visible(findPlaced::get).build());

    private final Setting<Boolean> findFramed = sg.add(new BoolSetting.Builder()
        .name("find-framed").description("Highlight rare items displayed in item frames — people put their best gear on show.").defaultValue(true).build());
    private final Setting<Integer> scanTicks = sg.add(new IntSetting.Builder()
        .name("scan-ticks").description("Ticks between sweeps for dropped and framed items.")
        .defaultValue(20).min(1).max(200).sliderRange(5, 60).build());

    private final Setting<List<Item>> items = sg.add(new ItemListSetting.Builder()
        .name("items")
        .description("Everything worth flagging — one list for the lot. Anything here is reported whether it is lying on the ground, hanging in an item frame, or placed as a block in the world. Ores are not here on purpose: ore-spotter++ handles those.")
        .defaultValue(List.of(
            Items.DRAGON_EGG, Items.DRAGON_HEAD, Items.WITHER_SKELETON_SKULL, Items.PLAYER_HEAD,
            Items.SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PIGLIN_HEAD,
            Items.ELYTRA, Items.NETHER_STAR, Items.HEART_OF_THE_SEA, Items.BEACON, Items.CONDUIT,
            Items.ENCHANTED_GOLDEN_APPLE, Items.TRIDENT, Items.NETHERITE_INGOT, Items.NETHERITE_SCRAP,
            Items.NETHERITE_BLOCK, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS,
            Items.NETHERITE_BOOTS, Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE,
            Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.MUSIC_DISC_PIGSTEP, Items.DISC_FRAGMENT_5,
            Items.GILDED_BLACKSTONE, Items.SEA_LANTERN, Items.SPONGE, Items.WET_SPONGE, Items.ANCIENT_DEBRIS,
            Items.CONDUIT, Items.ENCHANTING_TABLE, Items.SHULKER_BOX, Items.NETHERITE_BLOCK,
            Items.CHEST, Items.TRAPPED_CHEST, Items.BARREL, Items.ENDER_CHEST, Items.HOPPER,
            Items.FURNACE, Items.BLAST_FURNACE, Items.SMOKER, Items.BREWING_STAND, Items.ANVIL))
        .build());


    private final SettingGroup sgAlert = settings.createGroup("Alerts");
    private final Setting<Boolean> chat = sgAlert.add(new BoolSetting.Builder()
        .name("chat").description("Log each new rare item in chat.").defaultValue(true).build());
    private final Setting<Boolean> popup = sgAlert.add(new BoolSetting.Builder()
        .name("popup").description("Throw a title on screen when one turns up.").defaultValue(false).build());
    private final Setting<Boolean> sound = sgAlert.add(new BoolSetting.Builder()
        .name("sound").description("Play a sound when one turns up.").defaultValue(false).build());
    private final Setting<Double> volume = sgAlert.add(new DoubleSetting.Builder()
        .name("volume").description("How loud that sound is.").defaultValue(2.0).min(0.1).max(10)
        .sliderRange(0.5, 6).decimalPlaces(1).visible(sound::get).build());

    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Boolean> beacon = sgRender.add(new BoolSetting.Builder()
        .name("beacon").description("Shoot a beam up from each rare item. Two within five blocks share one beam placed between them, so a pile doesn't become a wall of beams.")
        .defaultValue(false).build());
    private final Setting<SettingColor> beaconColor = sgRender.add(new ColorSetting.Builder()
        .name("beacon-color").description("Colour of those beams.").defaultValue(new SettingColor(255, 215, 0, 180))
        .visible(beacon::get).build());
    private final Setting<SettingColor> itemColor = sgRender.add(new ColorSetting.Builder()
        .name("item-color").description("Colour used for items on the ground.").defaultValue(new SettingColor(0, 255, 170, 255))
        .visible(findDropped::get).build());
    private final Setting<SettingColor> frameColor = sgRender.add(new ColorSetting.Builder()
        .name("frame-color").description("Colour used for items in frames.").defaultValue(new SettingColor(200, 90, 255, 255))
        .visible(findFramed::get).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each find.").defaultValue(false).build());

    /** {x, y, z, 0 = on the ground / 1 = in a frame} */
    private final List<double[]> found = new ArrayList<>();
    private final java.util.Set<Integer> announced = new java.util.HashSet<>();
    private int tick;

    public RareFinder() {
        super(shama.addon.ShamaAddon.HUNT, "rare-finder++",
            "Highlights valuable items on the ground or in item frames, and can beam them so you spot them from far off.");
    }

    @Override
    public void onDeactivate() { found.clear(); announced.clear(); }

    private void alert(String what, double x, double y, double z) {
        if (chat.get())
            shama.addon.util.Chat.info("[RareFinder] %s at (%.0f, %.0f, %.0f)", what, x, y, z);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 30, 8);
            mc.inGameHud.setTitle(net.minecraft.text.Text.literal("Rare item")
                .formatted(net.minecraft.util.Formatting.GOLD));
            mc.inGameHud.setSubtitle(net.minecraft.text.Text.literal(what)
                .formatted(net.minecraft.util.Formatting.YELLOW));
        }
        if (sound.get() && mc.world != null && mc.player != null)
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                net.minecraft.sound.SoundCategory.PLAYERS, volume.get().floatValue(), 1.8f);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || (!findDropped.get() && !findFramed.get())) { found.clear(); return; }
        if (tick++ % Math.max(1, scanTicks.get()) != 0) return;

        found.clear();

        // placed rare blocks: walk the loaded chunks around you and match the block against the
        // same item list, so a placed beacon or a sponge wall counts even though nothing dropped
        if (findPlaced.get() && mc.player != null) {
            int r = placedRange.get(), r2 = r * r;
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            int cr = Math.max(1, (int) Math.ceil(r / 16.0));
            net.minecraft.util.math.BlockPos me = mc.player.getBlockPos();
            for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) {
                net.minecraft.world.chunk.Chunk ch = mc.world.getChunk(pcx + dx, pcz + dz,
                    net.minecraft.world.chunk.ChunkStatus.FULL, false);
                if (!(ch instanceof net.minecraft.world.chunk.WorldChunk wc)) continue;
                int bx = wc.getPos().getStartX(), bz = wc.getPos().getStartZ();
                int bot = Math.max(wc.getBottomY(), me.getY() - r), top = Math.min(wc.getTopYInclusive(), me.getY() + r);
                net.minecraft.util.math.BlockPos.Mutable m = new net.minecraft.util.math.BlockPos.Mutable();
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = bot; y <= top; y++) {
                    var st = wc.getBlockState(m.set(bx + x, y, bz + z));
                    if (st.isAir()) continue;
                    // one list drives everything: a placed block is matched by the item it drops,
                    // so you only ever maintain a single picker
                    var it = st.getBlock().asItem();
                    if (it == net.minecraft.item.Items.AIR || !items.get().contains(it)) continue;
                    if (me.getSquaredDistance(bx + x, y, bz + z) > r2) continue;
                    found.add(new double[]{bx + x + 0.5, y, bz + z + 0.5, 2});
                }
            }
        }

        for (Entity e : mc.world.getEntities()) {
            if (findDropped.get() && e instanceof ItemEntity ie) {
                if (!items.get().contains(ie.getStack().getItem())) continue;
                found.add(new double[]{e.getX(), e.getY(), e.getZ(), 0});
                if (announced.add(e.getId()))
                    alert(ie.getStack().getName().getString(), e.getX(), e.getY(), e.getZ());
            } else if (findFramed.get() && e instanceof ItemFrameEntity fr) {
                // item frames keep what they're displaying in getHeldItemStack
                var held = fr.getHeldItemStack();
                if (held.isEmpty() || !items.get().contains(held.getItem())) continue;
                found.add(new double[]{e.getX(), e.getY(), e.getZ(), 1});
                if (announced.add(e.getId()))
                    alert(held.getName().getString() + " (framed)", e.getX(), e.getY(), e.getZ());
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (found.isEmpty()) return;
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;

        if (beacon.get()) {
            SettingColor bc = beaconColor.get();
            Color bl = new Color(bc.r, bc.g, bc.b, 255);
            Color bf = new Color(bc.r, bc.g, bc.b, 50);
            boolean[] used = new boolean[found.size()];
            for (int i = 0; i < found.size(); i++) {
                if (used[i]) continue;
                double[] a = found.get(i);
                double bx = a[0], by = a[1], bz = a[2];
                int merged = 1;
                for (int j = i + 1; j < found.size(); j++) {
                    if (used[j]) continue;
                    double[] b = found.get(j);
                    if (Math.abs(a[0] - b[0]) <= 5 && Math.abs(a[1] - b[1]) <= 5 && Math.abs(a[2] - b[2]) <= 5) {
                        bx += b[0]; by += b[1]; bz += b[2]; merged++; used[j] = true;
                    }
                }
                bx /= merged; by /= merged; bz /= merged;
                event.renderer.box(bx - 0.15, by, bz - 0.15, bx + 0.15, by + 320, bz + 0.15, bf, bl, ShapeMode.Both, 0);
            }
        }

        for (double[] d : found) {
            SettingColor c = d[3] == 0 ? itemColor.get() : (d[3] == 1 ? frameColor.get() : placedColor.get());
            Color line = new Color(c.r, c.g, c.b, c.a);
            Color fill = new Color(c.r, c.g, c.b, 60);
            event.renderer.box(d[0] - 0.25, d[1] - 0.25, d[2] - 0.25, d[0] + 0.25, d[1] + 0.25, d[2] + 0.25,
                fill, line, shapeMode.get(), 0);
            if (tracers.get()) event.renderer.line(cam.x, cam.y, cam.z, d[0], d[1], d[2], line);
        }
    }

    @Override
    public String getInfoString() { return found.isEmpty() ? null : Integer.toString(found.size()); }
}
