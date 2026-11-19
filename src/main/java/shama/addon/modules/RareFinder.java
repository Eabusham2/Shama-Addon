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
 * Rare Finder — highlights genuinely rare things lying on the ground, displayed in item frames, or
 * placed as blocks, with an optional beam so you can spot them from a distance. Every kind of find
 * raises the same chat line, popup and sound.
 */
public class RareFinder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> findDropped = sg.add(new BoolSetting.Builder()
        .name("find-dropped").description("Highlight rare items lying on the ground.").defaultValue(true).build());
    private final Setting<Boolean> findPlaced = sg.add(new BoolSetting.Builder()
        .name("find-placed")
        .description("Also find rare blocks standing in the world — a beacon someone built, a sponge wall, heads on display, a monster spawner, a shulker box left in a hole. These never appear as dropped items, so without this they're invisible. Each loaded chunk is checked once as it arrives, so this costs nothing while you stand still.")
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
        .name("scan-ticks").description("Ticks between sweeps for dropped and framed items. Placed blocks do not use this; they are checked as their chunk arrives and whenever a block in it changes.")
        .defaultValue(20).min(1).max(200).sliderRange(5, 60).build());

    private final Setting<List<Item>> items = sg.add(new ItemListSetting.Builder()
        .name("rare-items")
        .description("Everything worth flagging — one list for the lot, and only things that are genuinely hard to come by. Anything here is reported whether it is lying on the ground, hanging in an item frame, or standing as a block in the world. Everyday blocks such as chests, furnaces and anvils are left out on purpose, since every base and village is full of them; ores are left to ore-spotter++.")
        .defaultValue(List.of(
            // the one-of-a-kind and boss drops
            Items.DRAGON_EGG, Items.DRAGON_HEAD, Items.ELYTRA, Items.NETHER_STAR, Items.BEACON,
            Items.CONDUIT, Items.HEART_OF_THE_SEA, Items.ENCHANTED_GOLDEN_APPLE, Items.TRIDENT,
            Items.MACE, Items.HEAVY_CORE,
            // every head and skull
            Items.WITHER_SKELETON_SKULL, Items.SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD,
            Items.PIGLIN_HEAD, Items.PLAYER_HEAD,
            // netherite, the whole set
            Items.NETHERITE_INGOT, Items.NETHERITE_SCRAP, Items.NETHERITE_BLOCK, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
            // rare finds and blocks
            Items.MUSIC_DISC_PIGSTEP, Items.DISC_FRAGMENT_5, Items.SPONGE, Items.WET_SPONGE, Items.SPAWNER,
            // shulker boxes, every colour — a box on its own is somebody's stash or kit
            Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX))
        .build());

    private final SettingGroup sgAlert = settings.createGroup("Alerts");
    private final Setting<Boolean> chat = sgAlert.add(new BoolSetting.Builder()
        .name("chat").description("Log each new find in chat with where it is — dropped, framed and placed alike.").defaultValue(true).build());
    private final Setting<Boolean> popup = sgAlert.add(new BoolSetting.Builder()
        .name("popup").description("Throw a title on screen when something turns up, whether it is on the ground, in a frame or placed as a block. Several at once share one title.").defaultValue(true).build());
    private final Setting<Boolean> sound = sgAlert.add(new BoolSetting.Builder()
        .name("sound").description("Play a sound when something turns up, for all three kinds of find.").defaultValue(true).build());
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

    /** {x, y, z, 0 = on the ground / 1 = in a frame / 2 = placed} — what is drawn this frame. */
    private volatile List<double[]> found = new ArrayList<>();
    private final java.util.Set<Integer> announced = new java.util.HashSet<>();
    private int tick;

    /** Placed rare blocks per chunk, as packed positions. Filled by the scanner thread. */
    private final java.util.Map<Long, long[]> placedByChunk = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> placedAnnounced = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile java.util.Set<Item> rareSet = java.util.Set.of();
    private int listHash;
    private java.util.concurrent.ExecutorService scanner;
    private Object lastWorld;

    /** Finds waiting to be announced together, so a pile or a wall is one alert. */
    private final List<String> pendingNames = new ArrayList<>();
    private double[] pendingAt;
    private long lastAlert;

    public RareFinder() {
        super(shama.addon.ShamaAddon.HUNT, "rare-finder++",
            "Highlights genuinely rare things — on the ground, in item frames, or placed as blocks — and can beam them so you spot them from far off.");
    }

    @Override
    public void onActivate() {
        found = new ArrayList<>(); announced.clear(); placedByChunk.clear(); placedAnnounced.clear();
        pendingNames.clear(); pendingAt = null; listHash = 0; lastWorld = mc.world;
        scanner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "shama-rarefinder");
            t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        found = new ArrayList<>(); announced.clear(); placedByChunk.clear(); placedAnnounced.clear(); pendingNames.clear();
    }

    /** Collect a find for the next alert. Alerts go out at most every couple of seconds. */
    private void note(String what, double x, double y, double z) {
        if (chat.get())
            shama.addon.util.Chat.info("[RareFinder] %s at (%.0f, %.0f, %.0f)", what, x, y, z);
        if (pendingNames.size() < 8) pendingNames.add(what);
        if (pendingAt == null) pendingAt = new double[]{x, y, z};
    }

    private void flushAlerts() {
        if (pendingAt == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAlert < 2000) return;
        lastAlert = now;
        String what = pendingNames.size() == 1 ? pendingNames.get(0)
            : pendingNames.get(0) + " and " + (pendingNames.size() - 1) + " more";
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 30, 8);
            mc.inGameHud.setTitle(net.minecraft.text.Text.literal("Rare find")
                .formatted(net.minecraft.util.Formatting.GOLD));
            mc.inGameHud.setSubtitle(net.minecraft.text.Text.literal(what)
                .formatted(net.minecraft.util.Formatting.YELLOW));
        }
        if (sound.get() && mc.world != null && mc.player != null)
            mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                net.minecraft.sound.SoundCategory.PLAYERS, volume.get().floatValue(), 1.8f);
        pendingNames.clear();
        pendingAt = null;
    }

    // ---------------------------------------------------------------- placed blocks

    private void queue(net.minecraft.world.chunk.WorldChunk chunk) {
        var s = scanner;
        if (s == null || s.isShutdown() || !findPlaced.get()) return;
        final java.util.Set<Item> want = rareSet;
        try { s.submit(() -> { try { scanPlaced(chunk, want); } catch (Throwable ignored) {} }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    /**
     * Find rare blocks in one chunk, on the scanner thread. A placed block is matched by the item it
     * drops, so the one list covers everything. Each section asks its palette first and is skipped if
     * none of the list is in it, which is nearly every section in the world.
     */
    private void scanPlaced(net.minecraft.world.chunk.WorldChunk chunk, java.util.Set<Item> want) {
        long ck = chunk.getPos().toLong();
        if (want.isEmpty()) { placedByChunk.remove(ck); return; }
        java.util.List<Long> hits = new ArrayList<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
        for (int si = 0; si < sections.length; si++) {
            net.minecraft.world.chunk.ChunkSection sec = sections[si];
            if (sec == null || sec.isEmpty() || !sec.hasAny(st -> !st.isAir() && want.contains(st.getBlock().asItem()))) continue;
            int sy = chunk.sectionIndexToCoord(si) << 4;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                var st = sec.getBlockState(x, y, z);
                if (st.isAir() || !want.contains(st.getBlock().asItem())) continue;
                hits.add(net.minecraft.util.math.BlockPos.asLong(bx + x, sy + y, bz + z));
            }
        }
        if (hits.isEmpty()) { placedByChunk.remove(ck); return; }
        long[] arr = new long[hits.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = hits.get(i);
        placedByChunk.put(ck, arr);
    }

    private void seedPlaced() {
        if (mc.world == null || mc.player == null) return;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int cr = Math.max(1, (int) Math.ceil(placedRange.get() / 16.0)) + 1;
        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) {
            var ch = mc.world.getChunk(pcx + dx, pcz + dz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (ch instanceof net.minecraft.world.chunk.WorldChunk wc) queue(wc);
        }
    }

    @EventHandler
    private void onChunkData(meteordevelopment.meteorclient.events.world.ChunkDataEvent event) {
        if (findPlaced.get() && event.chunk() instanceof net.minecraft.world.chunk.WorldChunk wc) queue(wc);
    }

    /** A block changing in a chunk that matters gets that chunk looked at again. */
    @EventHandler
    private void onBlockChange(meteordevelopment.meteorclient.events.packets.PacketEvent.Receive event) {
        if (!findPlaced.get() || mc.world == null) return;
        net.minecraft.util.math.BlockPos pos = null;
        if (event.packet instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket p) {
            net.minecraft.util.math.BlockPos bp = p.getPos();
            long ck = net.minecraft.util.math.ChunkPos.toLong(bp.getX() >> 4, bp.getZ() >> 4);
            boolean rareNow = rareSet.contains(p.getState().getBlock().asItem());
            if (rareNow || placedByChunk.containsKey(ck)) pos = bp.toImmutable();
        } else if (event.packet instanceof net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket bulk) {
            net.minecraft.util.math.BlockPos[] first = new net.minecraft.util.math.BlockPos[1];
            boolean[] rare = new boolean[1];
            bulk.visitUpdates((bp, st) -> {
                if (first[0] == null) first[0] = bp.toImmutable();
                if (rareSet.contains(st.getBlock().asItem())) rare[0] = true;
            });
            if (first[0] != null && (rare[0] || placedByChunk.containsKey(
                net.minecraft.util.math.ChunkPos.toLong(first[0].getX() >> 4, first[0].getZ() >> 4)))) pos = first[0];
        }
        if (pos == null) return;
        final int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        // the packet is handled after this event, so look a moment later once it is in the world
        mc.execute(() -> {
            if (mc.world == null) return;
            var ch = mc.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (ch instanceof net.minecraft.world.chunk.WorldChunk wc) queue(wc);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        flushAlerts();
        if (mc.world == null || mc.player == null) { found = new ArrayList<>(); return; }
        if (mc.world != lastWorld) { lastWorld = mc.world; placedByChunk.clear(); placedAnnounced.clear(); announced.clear(); listHash = 0; }

        // the list changed (or this is the first tick): rebuild the lookup and look again
        int h = items.get().hashCode() * 31 + (findPlaced.get() ? 1 : 0);
        if (h != listHash) {
            listHash = h;
            rareSet = new java.util.HashSet<>(items.get());
            placedByChunk.clear();
            if (findPlaced.get()) seedPlaced();
        }

        if (!findDropped.get() && !findFramed.get() && !findPlaced.get()) { found = new ArrayList<>(); return; }
        if (tick++ % Math.max(1, scanTicks.get()) != 0) return;

        List<double[]> next = new ArrayList<>();

        if (findPlaced.get()) {
            int r = placedRange.get();
            double r2 = (double) r * r;
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            for (long[] arr : placedByChunk.values()) {
                for (long packed : arr) {
                    int x = net.minecraft.util.math.BlockPos.unpackLongX(packed);
                    int y = net.minecraft.util.math.BlockPos.unpackLongY(packed);
                    int z = net.minecraft.util.math.BlockPos.unpackLongZ(packed);
                    double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    next.add(new double[]{x + 0.5, y + 0.5, z + 0.5, 2});
                    if (placedAnnounced.add(packed)) {
                        var st = mc.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
                        note(new net.minecraft.item.ItemStack(st.getBlock().asItem()).getName().getString() + " (placed)", x, y, z);
                    }
                }
            }
            if (placedAnnounced.size() > 100_000) placedAnnounced.clear();
        }

        for (Entity e : mc.world.getEntities()) {
            if (findDropped.get() && e instanceof ItemEntity ie) {
                if (!rareSet.contains(ie.getStack().getItem())) continue;
                next.add(new double[]{e.getX(), e.getY(), e.getZ(), 0});
                if (announced.add(e.getId()))
                    note(ie.getStack().getName().getString(), e.getX(), e.getY(), e.getZ());
            } else if (findFramed.get() && e instanceof ItemFrameEntity fr) {
                // item frames keep what they're displaying in getHeldItemStack
                var held = fr.getHeldItemStack();
                if (held.isEmpty() || !rareSet.contains(held.getItem())) continue;
                next.add(new double[]{e.getX(), e.getY(), e.getZ(), 1});
                if (announced.add(e.getId()))
                    note(held.getName().getString() + " (framed)", e.getX(), e.getY(), e.getZ());
            }
        }
        if (announced.size() > 50_000) announced.clear();
        found = next;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        List<double[]> found = this.found;
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
    public String getInfoString() { List<double[]> f = found; return f.isEmpty() ? null : Integer.toString(f.size()); }
}
