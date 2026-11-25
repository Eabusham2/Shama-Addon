package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import shama.addon.util.BlockPaths;
import shama.addon.util.Chat;
import shama.addon.util.SelfActivity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sound Detector — marks where sounds come from, so you can see what is going on around you
 * without seeing it.
 *
 * The server tells you about every sound near you, whether or not you can see what made it: a
 * chest opening behind a wall, a furnace crackling underground, a villager working, a piston
 * firing, someone mining, someone eating, a totem popping. Each one arrives with its position, so a
 * base, a farm or a player you cannot see still gives itself away.
 *
 * You choose what to listen for — blocks, entities and items — and every sound from one of those
 * within range gets a box where it came from, with a column through the whole height of the world
 * so it can be found from anywhere. Sounds are only reported by the server within its own hearing
 * distance of you, usually a few chunks; the range here can only narrow that.
 */
public class SoundDetector extends Module {
    private enum Kind { Block, Entity, Item, Other }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSources = settings.createGroup("What to listen for");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---------------------------------------------------------------- general

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range").description("How many chunks out from yours, in every direction, a sound is marked — at any height, from the bottom of the world to the top. The server only sends sounds within its own hearing distance of you, so going past that finds nothing more.")
        .defaultValue(4).range(1, 32).sliderRange(1, 16).build());
    private final Setting<Integer> keepSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("keep-for").description("How long a mark stays after the last sound from that spot, in seconds. It fades out over that time. 0 keeps marks until you switch the module off.")
        .defaultValue(120).range(0, 3600).sliderRange(0, 600).build());
    private final Setting<Integer> ignoreNear = sgGeneral.add(new IntSetting.Builder()
        .name("ignore-near-me").description("Leave out sounds within this many blocks of you — your own doors, chests, eating and footsteps-adjacent noise. Sounds your own character makes are always left out.")
        .defaultValue(4).range(0, 64).sliderRange(0, 16).build());
    private final Setting<Boolean> anyBreaking = sgGeneral.add(new BoolSetting.Builder()
        .name("any-block-breaking").description("Mark every block somebody else breaks in range, whatever it is. Mining is the loudest thing a hidden player does, and the server tells you about each block that goes.")
        .defaultValue(true).build());
    private final Setting<Boolean> allSounds = sgGeneral.add(new BoolSetting.Builder()
        .name("all-sounds").description("Mark every sound in range, not just the ones picked below. Noisy — mobs, water and lava all count — but nothing slips past.")
        .defaultValue(false).build());
    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat").description("Note new sounds in chat with what made them and where. A spot that keeps making noise is noted once, and the same kind of sound in the same chunk at most once every half minute — someone mining is one line, not one per block.")
        .defaultValue(true).build());

    // ---------------------------------------------------------------- sources

    private final Setting<List<Block>> blocks = sgSources.add(new BlockListSetting.Builder()
        .name("blocks").description("Blocks whose sounds are marked: opening, firing, brewing, working, ringing and so on. A wooden door covers every wooden door, a shulker box every colour, a piston both kinds. Somebody breaking one of these is marked as well.")
        .defaultValue(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.ANVIL,
            Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.NOTE_BLOCK, Blocks.NETHER_PORTAL,
            Blocks.BEACON, Blocks.CONDUIT, Blocks.RESPAWN_ANCHOR, Blocks.BELL, Blocks.COMPOSTER,
            Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.OAK_DOOR, Blocks.IRON_DOOR,
            Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.LEVER, Blocks.CRAFTER, Blocks.JUKEBOX, Blocks.CAMPFIRE)
        .build());
    private final Setting<Set<EntityType<?>>> entities = sgSources.add(new EntityTypeListSetting.Builder()
        .name("entities").description("Entities whose sounds are marked, at the entity. Players cover eating and drinking too; TNT and end crystals cover explosions.")
        .defaultValue(EntityType.PLAYER, EntityType.VILLAGER, EntityType.ZOMBIE_VILLAGER, EntityType.IRON_GOLEM,
            EntityType.FIREWORK_ROCKET, EntityType.ENDER_PEARL, EntityType.EXPERIENCE_ORB, EntityType.TNT,
            EntityType.ITEM_FRAME, EntityType.MINECART, EntityType.ARMOR_STAND, EntityType.WITHER, EntityType.END_CRYSTAL)
        .build());
    private final Setting<List<Item>> items = sgSources.add(new ItemListSetting.Builder()
        .name("items").description("Items whose sounds are marked where they were used: a totem popping, bone meal, flint and steel, a bucket, a crossbow, a shield blocking. Any one hoe, axe or shovel covers them all, and any armour piece covers putting armour on.")
        .defaultValue(Items.TOTEM_OF_UNDYING, Items.BONE_MEAL, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE, Items.TRIDENT,
            Items.CROSSBOW, Items.SHIELD, Items.CHORUS_FRUIT, Items.BUCKET, Items.IRON_HOE, Items.IRON_AXE,
            Items.NETHERITE_CHESTPLATE, Items.GOAT_HORN, Items.MACE)
        .build());

    // ---------------------------------------------------------------- render

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How each mark is drawn.")
        .defaultValue(ShapeMode.Both).build());
    private final Setting<Boolean> column = sgRender.add(new BoolSetting.Builder()
        .name("column").description("Run a thin column through each mark from the bottom of the world to the top, so a sound deep underground or high above is still easy to find.")
        .defaultValue(true).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from your view to each mark.")
        .defaultValue(false).build());
    private final Setting<SettingColor> blockColor = sgRender.add(new ColorSetting.Builder()
        .name("block-color").description("Colour for sounds made by blocks, and blocks being broken.")
        .defaultValue(new SettingColor(255, 150, 30, 160)).build());
    private final Setting<SettingColor> entityColor = sgRender.add(new ColorSetting.Builder()
        .name("entity-color").description("Colour for sounds made by entities.")
        .defaultValue(new SettingColor(255, 50, 60, 160)).build());
    private final Setting<SettingColor> itemColor = sgRender.add(new ColorSetting.Builder()
        .name("item-color").description("Colour for sounds made by items being used.")
        .defaultValue(new SettingColor(40, 200, 255, 160)).build());
    private final Setting<SettingColor> otherColor = sgRender.add(new ColorSetting.Builder()
        .name("other-color").description("Colour for everything else, with all-sounds on.")
        .defaultValue(new SettingColor(200, 200, 200, 120)).build());

    public SoundDetector() {
        super(shama.addon.ShamaAddon.HUNT, "sound-detector++",
            "Marks where sounds come from — chests, furnaces, pistons, villagers, players, totems, mining and whatever else you pick — with a box and a full-height column, so bases and hidden players give themselves away.");
    }

    // ---------------------------------------------------------------- state

    private static final class Mark {
        final int x, y, z;
        final Kind kind;
        final String what;
        long last;
        int count;
        Mark(int x, int y, int z, Kind kind, String what, long at) { this.x = x; this.y = y; this.z = z; this.kind = kind; this.what = what; this.last = at; this.count = 1; }
    }

    private final Map<String, Mark> marks = new ConcurrentHashMap<>();
    /** When each kind of sound was last noted in chat, per chunk. */
    private final Map<String, Long> lastSaid = new HashMap<>();
    /** Sounds arrive on the network thread; they are looked at on the game thread. */
    private final ConcurrentLinkedQueue<Runnable> incoming = new ConcurrentLinkedQueue<>();

    private final Map<String, Block> blockBySubject = new HashMap<>();
    private final Map<String, EntityType<?>> entityBySubject = new HashMap<>();
    private final Map<String, Item> itemBySubject = new HashMap<>();
    private final Set<Block> blockSet = new HashSet<>();
    private int listHash;

    @Override
    public void onActivate() { marks.clear(); incoming.clear(); lastSaid.clear(); listHash = 0; }

    @Override
    public void onDeactivate() { marks.clear(); incoming.clear(); }

    @Override
    public String getInfoString() { return marks.isEmpty() ? null : Integer.toString(marks.size()); }

    // ---------------------------------------------------------------- what made it

    /**
     * Sound names read "block.chest.open", "entity.villager.work_farmer", "item.totem.use": a kind,
     * then the thing that made it, then what it did. These are the middle words a picked block,
     * entity or item can appear under — usually just its own name, sometimes a shared one.
     */
    private static Set<String> blockSubjects(Block b) {
        String p = BlockPaths.of(b);
        Set<String> s = new HashSet<>();
        s.add(p);
        s.add(p.replace("_", ""));
        if (p.endsWith("shulker_box")) s.add("shulker_box");
        if (p.equals("trapped_chest")) s.add("chest");
        if (p.equals("blast_furnace")) s.add("blastfurnace");
        if (p.equals("nether_portal")) s.add("portal");
        if (p.equals("enchanting_table")) s.add("enchantment_table");
        if (p.equals("sticky_piston")) s.add("piston");
        if (p.endsWith("anvil")) s.add("anvil");
        if (p.endsWith("campfire")) s.add("campfire");
        if (p.endsWith("fire") && !p.endsWith("campfire")) s.add("fire");
        String wood = p.startsWith("bamboo") ? "bamboo_wood_" : p.startsWith("cherry") ? "cherry_wood_"
            : (p.startsWith("crimson") || p.startsWith("warped")) ? "nether_wood_" : "wooden_";
        boolean copper = p.contains("copper");
        if (p.endsWith("_trapdoor")) s.add(p.startsWith("iron") ? "iron_trapdoor" : copper ? "copper_trapdoor" : wood + "trapdoor");
        else if (p.endsWith("_door")) s.add(p.startsWith("iron") ? "iron_door" : copper ? "copper_door" : wood + "door");
        if (p.endsWith("fence_gate")) s.add(wood.equals("wooden_") ? "fence_gate" : wood + "fence_gate");
        if (p.endsWith("_button")) s.add(p.contains("stone") || p.contains("blackstone") ? "stone_button" : wood + "button");
        if (p.endsWith("pressure_plate")) s.add(p.contains("weighted") ? "metal_pressure_plate"
            : p.contains("stone") || p.contains("blackstone") ? "stone_pressure_plate" : wood + "pressure_plate");
        return s;
    }

    private static Set<String> entitySubjects(EntityType<?> t) {
        String p = Registries.ENTITY_TYPE.getId(t).getPath();
        Set<String> s = new HashSet<>();
        s.add(p);
        if (p.endsWith("boat") || p.endsWith("raft")) s.add("boat");
        if (p.endsWith("minecart")) s.add("minecart");
        return s;
    }

    private static Set<String> itemSubjects(Item i) {
        String p = Registries.ITEM.getId(i).getPath();
        Set<String> s = new HashSet<>();
        s.add(p);
        s.add(p.replace("_", ""));
        if (p.equals("totem_of_undying")) s.add("totem");
        if (p.endsWith("_hoe")) s.add("hoe");
        if (p.endsWith("_axe")) s.add("axe");
        if (p.endsWith("_shovel")) s.add("shovel");
        if (p.endsWith("bucket")) s.add("bucket");
        if (p.equals("glass_bottle") || p.endsWith("potion")) s.add("bottle");
        if (p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots") || p.equals("elytra"))
            s.add("armor");
        return s;
    }

    /** Rebuild the lookups when the picks change. */
    private void refreshLists() {
        int h = blocks.get().hashCode() * 31 * 31 + entities.get().hashCode() * 31 + items.get().hashCode();
        if (h == listHash) return;
        listHash = h;
        blockBySubject.clear(); entityBySubject.clear(); itemBySubject.clear(); blockSet.clear();
        for (Block b : blocks.get()) { blockSet.add(b); for (String s : blockSubjects(b)) blockBySubject.putIfAbsent(s, b); }
        for (EntityType<?> t : entities.get()) for (String s : entitySubjects(t)) entityBySubject.putIfAbsent(s, t);
        for (Item i : items.get()) for (String s : itemSubjects(i)) itemBySubject.putIfAbsent(s, i);
    }

    private static String idOf(RegistryEntry<SoundEvent> e) {
        String id = e.getIdAsString();
        if (id != null && id.contains(":")) return id.substring(id.indexOf(':') + 1);
        try { return e.comp_349().id().getPath(); } catch (Throwable t) { return ""; }
    }

    private record Source(Kind kind, String what) {}

    /** What made a sound, by its name; null if it is nothing you picked. */
    private Source classify(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2) return allSounds.get() ? new Source(Kind.Other, path) : null;
        String kind = parts[0], subject = parts[1];
        switch (kind) {
            case "block" -> {
                Block b = blockBySubject.get(subject);
                if (b != null) return new Source(Kind.Block, b.getName().getString());
            }
            case "item" -> {
                Item i = itemBySubject.get(subject);
                if (i != null) return new Source(Kind.Item, new ItemStack(i).getName().getString());
            }
            case "entity" -> {
                EntityType<?> t = entityBySubject.get(subject);
                if (t != null) return new Source(Kind.Entity, t.getName().getString());
                if (subject.equals("generic") && parts.length > 2) {
                    String act = parts[2];
                    if ((act.equals("eat") || act.equals("drink")) && entities.get().contains(EntityType.PLAYER))
                        return new Source(Kind.Entity, "Player (" + act + "ing)");
                    if (act.equals("explode") && (entities.get().contains(EntityType.TNT) || entities.get().contains(EntityType.END_CRYSTAL)
                        || entities.get().contains(EntityType.CREEPER) || entities.get().contains(EntityType.TNT_MINECART)))
                        return new Source(Kind.Entity, "Explosion");
                }
            }
            default -> { }
        }
        return allSounds.get() ? new Source(Kind.Other, path) : null;
    }

    // ---------------------------------------------------------------- listening

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlaySoundS2CPacket p) {
            String path = idOf(p.getSound());
            double x = p.getX(), y = p.getY(), z = p.getZ();
            incoming.add(() -> heard(path, x, y, z, null));
        } else if (event.packet instanceof PlaySoundFromEntityS2CPacket p) {
            String path = idOf(p.getSound());
            int id = p.getEntityId();
            incoming.add(() -> {
                if (mc.world == null) return;
                Entity e = mc.world.getEntityById(id);
                if (e == null || e == mc.player) return;
                heard(path, e.getX(), e.getY(), e.getZ(), e);
            });
        } else if (event.packet instanceof WorldEventS2CPacket p && !p.isGlobal()) {
            int code = p.getEventId(), data = p.getData();
            BlockPos pos = p.getPos().toImmutable();
            incoming.add(() -> worldEvent(code, pos, data));
        }
    }

    /** A sound with a name and a position — and, for sounds tied to an entity, the entity. */
    private void heard(String path, double x, double y, double z, Entity from) {
        if (path.isEmpty() || !inRange(x, y, z)) return;
        // your own pearl lands with a teleport sound where you are about to be; that one is yours
        if ((path.startsWith("entity.ender_pearl") || path.startsWith("entity.player.teleport"))
            && SelfActivity.INSTANCE.pearlInFlight()) return;
        Source s = classify(path);
        // a sound tied to an entity you picked counts as that entity, whatever the sound is called
        if (s == null && from != null && entities.get().contains(from.getType()))
            s = new Source(Kind.Entity, from.getType().getName().getString());
        if (s == null) return;
        mark(x, y, z, s);
    }

    /**
     * World events are the sounds the game plays from a block's own position — dispensers, doors
     * opened by mobs, anvils, brewing, jukeboxes, composters — and blocks being broken, which comes
     * with the block that broke.
     */
    private void worldEvent(int code, BlockPos pos, int data) {
        if (mc.world == null || !inRange(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) return;
        if (code == 2001) {
            Block b = Block.getStateFromRawId(data).getBlock();
            if (b == Blocks.AIR) return;
            if (anyBreaking.get() || blockSet.contains(b))
                mark(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new Source(Kind.Block, b.getName().getString() + " broken"));
            return;
        }
        if ((code >= 1000 && code < 1100) || (code >= 1500 && code < 1600)) {
            Block b = mc.world.getBlockState(pos).getBlock();
            if (blockSet.contains(b))
                mark(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new Source(Kind.Block, b.getName().getString()));
            else if (allSounds.get())
                mark(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new Source(Kind.Other, "world event " + code));
        }
    }

    /** Within the chosen number of chunks, at any height, and not on top of you. */
    private boolean inRange(double x, double y, double z) {
        if (mc.player == null) return false;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        if (Math.abs(cx - pcx) > chunkRange.get() || Math.abs(cz - pcz) > chunkRange.get()) return false;
        return ignoreNear.get() <= 0 || !SelfActivity.INSTANCE.near(x, y, z, ignoreNear.get());
    }

    private void mark(double x, double y, double z, Source s) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        String key = bx + "," + by + "," + bz + "," + s.what();
        long now = System.currentTimeMillis();
        Mark m = marks.get(key);
        if (m != null) { m.last = now; m.count++; return; }
        if (marks.size() >= 2000) {
            // forget the stalest spot to make room
            String oldest = null; long t = Long.MAX_VALUE;
            for (var e : marks.entrySet()) if (e.getValue().last < t) { t = e.getValue().last; oldest = e.getKey(); }
            if (oldest != null) marks.remove(oldest);
        }
        marks.put(key, new Mark(bx, by, bz, s.kind(), s.what(), now));
        if (chat.get()) {
            String quiet = s.what() + "@" + (bx >> 4) + "," + (bz >> 4);
            Long said = lastSaid.get(quiet);
            if (said == null || now - said > 30_000) {
                lastSaid.put(quiet, now);
                if (lastSaid.size() > 4000) lastSaid.clear();
                Chat.info("[sound] %s at %d, %d, %d", s.what(), bx, by, bz);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) { incoming.clear(); return; }
        refreshLists();
        Runnable r;
        int budget = 512;                                 // a flood of sounds cannot stall a tick
        while (budget-- > 0 && (r = incoming.poll()) != null) {
            try { r.run(); } catch (Throwable ignored) {}
        }
        if (keepSeconds.get() > 0) {
            long cutoff = System.currentTimeMillis() - keepSeconds.get() * 1000L;
            marks.values().removeIf(m -> m.last < cutoff);
        }
    }

    // ---------------------------------------------------------------- drawing

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (marks.isEmpty() || mc.world == null) return;
        long now = System.currentTimeMillis();
        long keep = keepSeconds.get() * 1000L;
        double bottom = mc.world.getBottomY(), top = mc.world.getTopYInclusive() + 1;
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;
        ShapeMode mode = shapeMode.get();
        for (Mark m : marks.values()) {
            SettingColor c = switch (m.kind) {
                case Block -> blockColor.get();
                case Entity -> entityColor.get();
                case Item -> itemColor.get();
                case Other -> otherColor.get();
            };
            // fade over the keep-for time, never quite to nothing
            double life = keep > 0 ? Math.max(0.25, 1.0 - (double) (now - m.last) / keep) : 1.0;
            Color line = new Color(c.r, c.g, c.b, (int) (c.a * life));
            Color fill = new Color(c.r, c.g, c.b, (int) (Math.min(120, c.a / 2) * life));
            event.renderer.box(m.x, m.y, m.z, m.x + 1, m.y + 1, m.z + 1, fill, line, mode, 0);
            if (column.get()) {
                Color col = new Color(c.r, c.g, c.b, (int) (Math.min(70, c.a / 3) * life));
                event.renderer.box(m.x + 0.4, bottom, m.z + 0.4, m.x + 0.6, top, m.z + 0.6, col, col, ShapeMode.Sides, 0);
            }
            if (tracers.get()) event.renderer.line(cam.x, cam.y, cam.z, m.x + 0.5, m.y + 0.5, m.z + 0.5, line);
        }
    }
}
