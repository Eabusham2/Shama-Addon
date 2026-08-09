package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * RTP Finder — runs the server's random-teleport command over and over looking for rare loot.
 *
 * The loop: send /rtp, wait for the chunks around you to finish loading, then look for anything on
 * your rare list (dropped, in item frames, or in nearby storage). Nothing found means it waits a
 * random gap and rtps again. Something found means it marks the spot with a beacon, shouts at you,
 * optionally saves a home and/or logs you out, and switches itself off.
 *
 * It also switches itself off whenever you reconnect, so it can never quietly keep running after a
 * relog.
 */
public class RtpFinder extends Module {
    private final Random rng = new Random();

    // ---------------------------------------------------------------- commands
    private final SettingGroup sgCmd = settings.createGroup("Command");

    private final Setting<String> rtpCommand = sgCmd.add(new StringSetting.Builder()
        .name("rtp-command")
        .description("The command to send, without the slash. Usually just rtp.")
        .defaultValue("rtp").build());

    private final Setting<List<String>> extraVariants = sgCmd.add(new StringListSetting.Builder()
        .name("extra-variants")
        .description("Optional extra versions to mix in at random, e.g. \"rtp east\" and \"rtp west\". Leave empty to always send the plain command above.")
        .defaultValue(List.of()).build());

    private final Setting<Integer> minWait = sgCmd.add(new IntSetting.Builder()
        .name("min-wait-seconds")
        .description("Shortest gap between attempts. The real gap is picked at random between this and the maximum, so the timing never looks mechanical.")
        .defaultValue(20).min(1).max(600).sliderRange(5, 120).build());

    private final Setting<Integer> maxWait = sgCmd.add(new IntSetting.Builder()
        .name("max-wait-seconds")
        .description("Longest gap between attempts.")
        .defaultValue(45).min(1).max(1200).sliderRange(10, 300).build());

    private final Setting<Integer> loadTimeout = sgCmd.add(new IntSetting.Builder()
        .name("load-timeout-seconds")
        .description("Give up waiting for chunks to finish loading after this long and search anyway.")
        .defaultValue(20).min(3).max(120).sliderRange(5, 60).build());

    // ---------------------------------------------------------------- what counts as a find
    private final SettingGroup sgFind = settings.createGroup("What To Look For");

    private final Setting<List<Item>> rareItems = sgFind.add(new ItemListSetting.Builder()
        .name("rare-items")
        .description("The loot worth stopping for. Anything here counts as a find whether it's lying on the ground, hanging in an item frame, or sitting in a nearby container.")
        .defaultValue(List.of(
            Items.ELYTRA, Items.SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PLAYER_HEAD, Items.PIGLIN_HEAD, Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK, Items.NETHERITE_SCRAP,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE,
            Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE,
            Items.ANCIENT_DEBRIS, Items.DRAGON_EGG, Items.DRAGON_HEAD, Items.BEACON,
            Items.NETHER_STAR, Items.ENCHANTED_GOLDEN_APPLE, Items.TRIDENT, Items.HEART_OF_THE_SEA,
            Items.WITHER_SKELETON_SKULL, Items.SHULKER_BOX, Items.TOTEM_OF_UNDYING,
            Items.MUSIC_DISC_PIGSTEP, Items.SPONGE, Items.WET_SPONGE))
        .build());

    private final Setting<Boolean> checkDropped = sgFind.add(new BoolSetting.Builder()
        .name("check-dropped").description("Count rare items lying on the ground.").defaultValue(true).build());
    private final Setting<Boolean> checkPlaced = sgFind.add(new BoolSetting.Builder()
        .name("check-placed").description("Count rare blocks that have been placed in the world — a built beacon, a sponge wall, heads on display. These never drop as items, so without this a base full of them looks empty.")
        .defaultValue(true).build());
    private final Setting<Boolean> checkFrames = sgFind.add(new BoolSetting.Builder()
        .name("check-item-frames").description("Look inside item frames too — people display their best gear.").defaultValue(true).build());

    private final Setting<Boolean> storageCluster = sgFind.add(new BoolSetting.Builder()
        .name("storage-cluster")
        .description("Stop when a lot of containers sit close together. This counts containers rather than reading what's inside them, because a server won't tell you a chest's contents until you open it — a dense pile is a stash regardless.")
        .defaultValue(false).build());
    private final Setting<Integer> minStorage = sgFind.add(new IntSetting.Builder()
        .name("min-storage").description("How many containers must be nearby to count as a stash.")
        .defaultValue(12).min(2).max(200).sliderRange(4, 60).visible(storageCluster::get).build());

    private final Setting<Boolean> susCluster = sgFind.add(new BoolSetting.Builder()
        .name("frames-and-stands")
        .description("Also stop on clusters of the little things players leave behind — item frames, armour stands and similar.")
        .defaultValue(false).build());
    private final Setting<Integer> minSus = sgFind.add(new IntSetting.Builder()
        .name("min-frames-stands").description("How many of those must be nearby to count.")
        .defaultValue(6).min(2).max(100).sliderRange(3, 30).visible(susCluster::get).build());

    private final Setting<Integer> searchRadius = sgFind.add(new IntSetting.Builder()
        .name("search-radius").description("How far around you to search after each teleport, in blocks.")
        .defaultValue(96).min(16).max(256).sliderRange(32, 160).build());

    // ---------------------------------------------------------------- after a find
    private final SettingGroup sgAfter = settings.createGroup("After A Find");

    private final Setting<Boolean> doHome = sgAfter.add(new BoolSetting.Builder()
        .name("set-home")
        .description("Save a home at the spot so you can come back. Off by default because it overwrites whichever home slot you pick.")
        .defaultValue(false).build());
    private final Setting<Integer> homeNumber = sgAfter.add(new IntSetting.Builder()
        .name("home-number").description("Which home slot to overwrite. It runs delhome on this slot first, then sethome.")
        .defaultValue(1).min(1).max(50).sliderRange(1, 10).visible(doHome::get).build());
    private final Setting<Boolean> doLogout = sgAfter.add(new BoolSetting.Builder()
        .name("log-out")
        .description("Disconnect afterwards. With set-home on it waits out one more random gap after saving; with set-home off it just waits that gap and then leaves.")
        .defaultValue(false).build());

    // ---------------------------------------------------------------- pacing
    private final SettingGroup sgPace = settings.createGroup("Pacing");

    private final Setting<Boolean> tpsBackoff = sgPace.add(new BoolSetting.Builder()
        .name("wait-longer-on-lag")
        .description("When the region you land in is lagging, add extra time before the next teleport rather than hammering a struggling server.")
        .defaultValue(true).build());
    private final Setting<Integer> tpsExtraSeconds = sgPace.add(new IntSetting.Builder()
        .name("extra-wait-seconds").description("How much time to add when that happens.")
        .defaultValue(15).min(1).max(120).sliderRange(5, 60).visible(tpsBackoff::get).build());
    private final Setting<Double> tpsThreshold = sgPace.add(new DoubleSetting.Builder()
        .name("low-tps-below").description("What counts as lagging.")
        .defaultValue(15.0).min(1).max(20).sliderRange(5, 20).decimalPlaces(1).visible(tpsBackoff::get).build());

    // ---------------------------------------------------------------- alerts
    private final SettingGroup sgAlert = settings.createGroup("Alerts");

    private final Setting<Boolean> popup = sgAlert.add(new BoolSetting.Builder()
        .name("popup").description("Throw a title across the screen on a find.").defaultValue(true).build());
    private final Setting<Boolean> sound = sgAlert.add(new BoolSetting.Builder()
        .name("sound").description("Play a loud alert on a find, so you hear it from across the room.").defaultValue(true).build());
    private final Setting<Double> volume = sgAlert.add(new DoubleSetting.Builder()
        .name("volume").description("How loud that alert is.").defaultValue(5.0).min(0.1).max(10).sliderRange(1, 10).decimalPlaces(1).visible(sound::get).build());
    private final Setting<Boolean> chat = sgAlert.add(new BoolSetting.Builder()
        .name("chat").description("Log what happened in chat: each teleport, each wait, and the find itself.").defaultValue(true).build());

    private final Setting<Boolean> beacon = sgAlert.add(new BoolSetting.Builder()
        .name("beacon").description("Shoot a beam up from the find so you can walk back to it.").defaultValue(true).build());
    private final Setting<SettingColor> beaconColor = sgAlert.add(new ColorSetting.Builder()
        .name("beacon-color").description("Colour of that beam.").defaultValue(new SettingColor(255, 215, 0, 200)).visible(beacon::get).build());

    // ---------------------------------------------------------------- state
    private enum Phase { IDLE, WAITING, LOADING, SEARCHING, FOLLOW_UP, DONE }

    private Phase phase = Phase.IDLE;
    private long phaseUntil;              // wall-clock deadline for the current phase
    private long loadDeadline;
    private BlockPos foundAt;
    private String foundWhat = "";
    private int attempts;
    private int followStep;
    private boolean sawWorld;             // used to notice a relog

    public RtpFinder() {
        super(shama.addon.ShamaAddon.HUNT, "rtp-finder++",
            "Teleports around with /rtp hunting for rare loot, marks anything it finds with a beacon, and stops itself once it does.");
    }

    @Override
    public void onActivate() {
        phase = Phase.WAITING;
        phaseUntil = System.currentTimeMillis();     // first attempt goes out immediately
        foundAt = null; foundWhat = ""; attempts = 0; followStep = 0;
        sawWorld = mc.world != null;
        if (chat.get()) shama.addon.util.Chat.info("[RtpFinder] started — searching with /%s", rtpCommand.get());
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        foundAt = null;
    }

    private long randomGap() {
        int lo = Math.min(minWait.get(), maxWait.get());
        int hi = Math.max(minWait.get(), maxWait.get());
        return (lo + (hi > lo ? rng.nextInt(hi - lo + 1) : 0)) * 1000L;
    }

    private void send(String command) {
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand(command);
    }

    private String pickCommand() {
        List<String> variants = extraVariants.get();
        if (variants.isEmpty() || rng.nextBoolean()) return rtpCommand.get();
        return variants.get(rng.nextInt(variants.size()));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            // dropped out of the world: this is the relog case, so stop rather than resume blindly
            if (sawWorld && phase != Phase.IDLE) {
                phase = Phase.IDLE;
                toggle();
            }
            return;
        }
        sawWorld = true;
        long now = System.currentTimeMillis();

        switch (phase) {
            case WAITING -> {
                if (now < phaseUntil) return;
                attempts++;
                String cmd = pickCommand();
                if (chat.get()) shama.addon.util.Chat.info("[RtpFinder] attempt %d — /%s", attempts, cmd);
                send(cmd);
                phase = Phase.LOADING;
                loadDeadline = now + loadTimeout.get() * 1000L;
            }
            case LOADING -> {
                if (chunksReady() || now >= loadDeadline) {
                    phase = Phase.SEARCHING;
                }
            }
            case SEARCHING -> {
                String hit = search();
                if (hit != null) {
                    foundWhat = hit;
                    foundAt = mc.player.getBlockPos();
                    announce();
                    phase = Phase.FOLLOW_UP;
                    followStep = 0;
                    phaseUntil = now + randomGap();
                } else {
                    long gap = randomGap();
                    if (tpsBackoff.get() && regionLagging()) {
                        gap += tpsExtraSeconds.get() * 1000L;
                        if (chat.get()) shama.addon.util.Chat.warning("[RtpFinder] region is lagging — waiting %ds longer.", tpsExtraSeconds.get());
                    }
                    if (chat.get()) shama.addon.util.Chat.info("[RtpFinder] nothing here, next try in %ds", gap / 1000);
                    phase = Phase.WAITING;
                    phaseUntil = now + gap;
                }
            }
            case FOLLOW_UP -> {
                if (now < phaseUntil) return;
                if (followStep == 0 && doHome.get()) {
                    send("delhome " + homeNumber.get());
                    send("sethome " + homeNumber.get());
                    if (chat.get()) shama.addon.util.Chat.info("[RtpFinder] saved home %d here.", homeNumber.get());
                    followStep = 1;
                    phaseUntil = now + randomGap();
                    return;
                }
                if (doLogout.get()) {
                    if (chat.get()) shama.addon.util.Chat.info("[RtpFinder] logging out.");
                    if (mc.getNetworkHandler() != null)
                        mc.getNetworkHandler().getConnection().disconnect(Text.literal("[RtpFinder] found something"));
                }
                phase = Phase.DONE;
                toggle();     // job done — switch off so it can't wander away from the find
            }
            default -> { }
        }
    }

    /** True once the chunks in a small ring around you have arrived. */
    private boolean chunksReady() {
        if (mc.world == null || mc.player == null) return false;
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) return false;
        return true;
    }

    private boolean regionLagging() {
        LagDetector m = meteordevelopment.meteorclient.systems.modules.Modules.get().get(LagDetector.class);
        if (m == null || !m.isActive()) return false;
        double t = m.currentTps();
        return t > 0 && t < tpsThreshold.get();
    }

    /** Returns a short description of what was found, or null for nothing. */
    private String search() {
        if (mc.world == null || mc.player == null) return null;
        double r = searchRadius.get(), r2 = r * r;
        List<Item> wanted = rareItems.get();
        int storage = 0, sus = 0;

        for (Entity e : mc.world.getEntities()) {
            if (e.squaredDistanceTo(mc.player) > r2) continue;
            if (checkDropped.get() && e instanceof ItemEntity ie && wanted.contains(ie.getStack().getItem()))
                return ie.getStack().getName().getString() + " on the ground";
            if (e instanceof ItemFrameEntity fr) {
                ItemStack held = fr.getHeldItemStack();
                if (checkFrames.get() && !held.isEmpty() && wanted.contains(held.getItem()))
                    return held.getName().getString() + " in an item frame";
                sus++;
            } else if (e.getType() == net.minecraft.entity.EntityType.ARMOR_STAND) sus++;
        }

        // walk the loaded chunks around you using the same access pattern the other scanners use
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int cr = Math.max(1, (int) Math.ceil(r / 16.0));
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                net.minecraft.world.chunk.Chunk ch = mc.world.getChunk(pcx + dx, pcz + dz,
                    net.minecraft.world.chunk.ChunkStatus.FULL, false);
                if (!(ch instanceof WorldChunk wc)) continue;
                for (java.util.Map.Entry<BlockPos, BlockEntity> en : wc.getBlockEntities().entrySet()) {
                    if (en.getKey().getSquaredDistance(mc.player.getBlockPos()) > r2) continue;
                    // Count only. A server almost never sends you what's inside a chest until you
                    // open it, so counting the containers is the honest signal — a pile of them is
                    // a stash whether or not we can see the contents.
                    if (en.getValue() instanceof Inventory) storage++;
                }
                // placed rare blocks in this chunk
                if (checkPlaced.get()) {
                    int bx2 = wc.getPos().getStartX(), bz2 = wc.getPos().getStartZ();
                    BlockPos me = mc.player.getBlockPos();
                    int bot = Math.max(wc.getBottomY(), me.getY() - (int) r), top2 = Math.min(wc.getTopYInclusive(), me.getY() + (int) r);
                    BlockPos.Mutable m2 = new BlockPos.Mutable();
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = bot; y <= top2; y++) {
                        var st = wc.getBlockState(m2.set(bx2 + x, y, bz2 + z));
                        if (st.isAir()) continue;
                        var it = st.getBlock().asItem();
                        if (it != Items.AIR && wanted.contains(it)) return it.getName().getString() + " placed";
                    }
                }
            }
        }

        if (storageCluster.get() && storage >= minStorage.get()) return storage + " containers together";
        if (susCluster.get() && sus >= minSus.get()) return sus + " frames/stands together";
        return null;
    }

    private void announce() {
        if (chat.get())
            shama.addon.util.Chat.info("[RtpFinder] FOUND %s at %d, %d, %d (after %d tries)",
                foundWhat, foundAt.getX(), foundAt.getY(), foundAt.getZ(), attempts);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 60, 10);
            mc.inGameHud.setTitle(Text.literal("RTP FIND").formatted(Formatting.GOLD));
            mc.inGameHud.setSubtitle(Text.literal(foundWhat).formatted(Formatting.YELLOW));
        }
        if (sound.get() && mc.world != null && mc.player != null) {
            float v = volume.get().floatValue();
            // played a few times so it carries even if you're away from the keyboard
            for (int i = 0; i < 3; i++)
                mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING,
                    net.minecraft.sound.SoundCategory.PLAYERS, v, 1.6f);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!beacon.get() || foundAt == null) return;
        SettingColor c = beaconColor.get();
        Color line = new Color(c.r, c.g, c.b, 255);
        Color fill = new Color(c.r, c.g, c.b, 60);
        event.renderer.box(foundAt.getX(), foundAt.getY(), foundAt.getZ(),
            foundAt.getX() + 1, foundAt.getY() + 320, foundAt.getZ() + 1, fill, line, ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        return switch (phase) {
            case WAITING -> "waiting";
            case LOADING -> "loading";
            case SEARCHING -> "searching";
            case FOLLOW_UP -> "wrapping up";
            case DONE -> "found";
            default -> null;
        };
    }
}
