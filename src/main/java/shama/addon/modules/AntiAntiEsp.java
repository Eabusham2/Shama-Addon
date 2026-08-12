package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti Anti ESP — recovers what the server is hiding by reading the messages it cannot fake.
 *
 * The protection works on the bulk chunk packet. Everything below your cutoff gets replaced with
 * deepslate while you are above it, and things like amethyst and dripstone are stripped out unless
 * you are down there with them and looking. So the chunk you receive is a lie.
 *
 * It cannot lie on the other channels. A block change, a block entity, a particle, a sound — those
 * describe live events the client has to render correctly, so they carry true positions. A chest
 * announces itself; amethyst gives off particles; a farm makes noise. This listens to all of it and
 * keeps a picture built only from things the server told the truth about, which survives whatever
 * the chunk data claims.
 */
public class AntiAntiEsp extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> range = sg.add(new IntSetting.Builder()
        .name("range").description("How far out to keep what has been recovered, in blocks.")
        .defaultValue(160).min(16).max(512).sliderRange(48, 320).build());

    private final Setting<Boolean> forgetOnUpdate = sg.add(new BoolSetting.Builder()
        .name("forget-on-update")
        .description("Keep a recovered position until the server actually says that block changed, instead of dropping it after a timer. Hiding a block from chunk data is easy, but when somebody breaks it the server has to send a block update or your world would be wrong — so no update means it is still there. Works on anything, not just amethyst, and it sends nothing.")
        .defaultValue(true).build());

    private final Setting<Integer> keepSeconds = sg.add(new IntSetting.Builder()
        .name("remember-for")
        .description("How long a recovered position stays on screen, in seconds. Only used when forget-on-update is off — with that on, a position is kept until the server says it changed, which needs no timer.")
        .defaultValue(600).min(10).max(7200).sliderRange(60, 1800).visible(() -> !forgetOnUpdate.get()).build());

    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder()
        .name("chat").description("Report each recovery in chat.").defaultValue(false).build());

    // ---------------------------------------------------------------- channels
    private final SettingGroup sgSources = settings.createGroup("Channels");

    private final Setting<Boolean> fromBlockUpdates = sgSources.add(new BoolSetting.Builder()
        .name("block-changes")
        .description("Read single block changes. Something being placed inside ground the server told you was solid is that ground exposed as a lie. Blocks being broken are ignored — that is activity, not a hidden find, and deep-activity++ is the module for it.")
        .defaultValue(true).build());

    private final Setting<Boolean> fromBulkUpdates = sgSources.add(new BoolSetting.Builder()
        .name("bulk-changes")
        .description("Read bulk section updates as well. Mining and explosions arrive this way rather than one block at a time, so leaving this off misses most of what people are doing.")
        .defaultValue(true).build());

    private final Setting<Boolean> fromBlockEntities = sgSources.add(new BoolSetting.Builder()
        .name("block-entities")
        .description("Read block entities. Chests, hoppers, spawners and signs travel in their own packet with a real position attached, and the protection does not touch it — this is the most reliable channel there is.")
        .defaultValue(true).build());

    private final Setting<Boolean> fromParticles = sgSources.add(new BoolSetting.Builder()
        .name("particles")
        .description("Read particles. Amethyst, dripstone drips, portals and lava all give themselves away this way, and it works even when the block itself has been stripped out of the chunk you were sent.")
        .defaultValue(true).build());

    private final Setting<Boolean> fromSounds = sgSources.add(new BoolSetting.Builder()
        .name("sounds")
        .description("Read sounds. Amethyst chimes, water drips and machinery all carry a position, so a place that is silent in your chunk data can still be noisy on this channel.")
        .defaultValue(true).build());

    // ---------------------------------------------------------------- filter
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    private final Setting<Boolean> beyondSendRange = sgFilter.add(new BoolSetting.Builder()
        .name("beyond-send-range")
        .description("Trust anything reported from further away than the server says it sends chunks. It announces that distance itself, so a position past it cannot have come from chunk data at all — it can only have arrived on one of the honest channels. That makes it certain rather than probable, and it needs no guessing about what your client is drawing.")
        .defaultValue(true).build());

    private final Setting<Boolean> onlyHidden = sgFilter.add(new BoolSetting.Builder()
        .name("only-hidden")
        .description("Only keep positions where your client currently shows deepslate, stone or air. That mismatch is the protection caught in the act — the server described something real in a place it told you was solid rock.")
        .defaultValue(true).build());

    private final Setting<Boolean> belowYOnly = sgFilter.add(new BoolSetting.Builder()
        .name("below-y-only")
        .description("Only keep what is under the height below, which is where the ground gets replaced.")
        .defaultValue(true).build());

    private final Setting<Integer> belowY = sgFilter.add(new IntSetting.Builder()
        .name("below-y").description("The height that limit uses.")
        .defaultValue(0).min(-64).max(320).sliderRange(-64, 64)
        .visible(belowYOnly::get).build());

    private final Setting<List<net.minecraft.item.Item>> watch = sgFilter.add(new ItemListSetting.Builder()
        .name("blocks")
        .description("Which blocks are worth recovering. Anything not here is ignored even when the server slips up and mentions it. It is an item picker, matched against the block's item form.")
        .defaultValue(List.of(
            net.minecraft.item.Items.CHEST, net.minecraft.item.Items.TRAPPED_CHEST,
            net.minecraft.item.Items.BARREL, net.minecraft.item.Items.ENDER_CHEST,
            net.minecraft.item.Items.SHULKER_BOX, net.minecraft.item.Items.HOPPER,
            net.minecraft.item.Items.FURNACE, net.minecraft.item.Items.BLAST_FURNACE,
            net.minecraft.item.Items.SMOKER, net.minecraft.item.Items.BREWING_STAND,
            net.minecraft.item.Items.SPAWNER, net.minecraft.item.Items.BEACON,
            net.minecraft.item.Items.CONDUIT, net.minecraft.item.Items.ENCHANTING_TABLE,
            net.minecraft.item.Items.ANVIL, net.minecraft.item.Items.AMETHYST_CLUSTER,
            net.minecraft.item.Items.BUDDING_AMETHYST, net.minecraft.item.Items.AMETHYST_BLOCK,
            net.minecraft.item.Items.POINTED_DRIPSTONE, net.minecraft.item.Items.ANCIENT_DEBRIS,
            net.minecraft.item.Items.DIAMOND_ORE, net.minecraft.item.Items.DEEPSLATE_DIAMOND_ORE,
            net.minecraft.item.Items.OBSIDIAN, net.minecraft.item.Items.CRYING_OBSIDIAN))
        .build());

    // ---------------------------------------------------------------- render
    // ---------------------------------------------------------------- forcing the server's hand
    private final SettingGroup sgForce = settings.createGroup("Force Data");

    private final Setting<Boolean> forceData = sgForce.add(new BoolSetting.Builder()
        .name("force-data")
        .description("Try to make the server send the real thing rather than only listening for slips. It decides what to hide from two things it cannot verify: where you say you are, and where you say you are looking. Everything under this claims one of those is different from the truth, so all of it carries some risk — the options only appear once this is on.")
        .defaultValue(false).build());

    private final Setting<Boolean> lookDown = sgForce.add(new BoolSetting.Builder()
        .name("look-down (risky)")
        .description("Report your view as pointing straight down while your actual view stays put. If the rule is that you only get amethyst and dripstone you are looking at, then as far as the server is concerned you are always looking at the ground. Your aim on screen does not move, but rotation you never made is the classic thing anti-cheats watch for.")
        .defaultValue(false).visible(forceData::get).build());

    private final Setting<Boolean> lookSweep = sgForce.add(new BoolSetting.Builder()
        .name("look-sweep (risky)")
        .description("Turn the reported view right around between updates so every direction counts as looked at, not just downward. Covers far more ground than looking down alone, and looks correspondingly worse — a player does not spin like this.")
        .defaultValue(false).visible(forceData::get).build());

    private final Setting<Boolean> claimBelow = sgForce.add(new BoolSetting.Builder()
        .name("claim-below-y (risky)")
        .description("Report a height under the cutoff so the server stops swapping the ground for deepslate, then correct back the same tick. This is the one that gets you data from above ground, and it is also the most obvious — you are claiming to be somewhere you are not.")
        .defaultValue(false).visible(forceData::get).build());

    private final Setting<Integer> claimY = sgForce.add(new IntSetting.Builder()
        .name("claim-y")
        .description("The height to report. It needs to sit under the cutoff to be worth anything.")
        .defaultValue(-8).min(-64).max(320).sliderRange(-64, 32)
        .visible(() -> forceData.get() && claimBelow.get()).build());

    private final Setting<Integer> forceRate = sgForce.add(new IntSetting.Builder()
        .name("force-rate")
        .description("Ticks between attempts. Slower is quieter and less likely to be noticed.")
        .defaultValue(10).min(1).max(200).sliderRange(2, 40)
        .visible(forceData::get).build());

    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<SettingColor> blockColor = sgRender.add(new ColorSetting.Builder()
        .name("block-color").description("Colour for blocks recovered from change or block-entity packets.")
        .defaultValue(new SettingColor(0, 255, 140, 220)).build());
    private final Setting<SettingColor> hintColor = sgRender.add(new ColorSetting.Builder()
        .name("hint-color").description("Colour for positions recovered from particles or sounds, where the exact block is unknown.")
        .defaultValue(new SettingColor(255, 190, 0, 180)).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Outline only, filled sides only, or both.")
        .defaultValue(ShapeMode.Both).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each recovery.").defaultValue(false).build());

    /** position -> {when it was seen, 0 = a known block / 1 = a particle or sound hint} */
    private final Map<Long, long[]> found = new ConcurrentHashMap<>();
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet();
    /** How far the server says it sends real chunks; -1 until it tells us. */
    private int serverDistance = -1;

    public AntiAntiEsp() {
        super(shama.addon.ShamaAddon.HUNT, "anti-anti-esp++",
            "Recovers blocks the server left out of your chunk data. It listens to the messages that carry a real position — block entities, particles, sounds, live block changes — and keeps only the ones landing where your client shows plain stone, which is the server contradicting itself.");
    }

    @Override
    public void onActivate() { found.clear(); announced.clear(); }

    @Override
    public void onDeactivate() { found.clear(); announced.clear(); }

    /** Amethyst always counts, matched with the supplied AmethystESP check. */
    private boolean isAmethyst(BlockPos pos) {
        if (mc.world == null) return false;
        try { return shama.addon.util.AmethystScan.isAmethystLike(mc.world.getBlockState(pos)); }
        catch (Throwable t) { return false; }
    }

    private boolean wanted(BlockPos pos, Block block) {
        if (mc.player == null) return false;
        // A block turning to air is somebody breaking it, which is activity, not a hidden find.
        // deep-activity++ is the module for that; recording it here just fills the list with holes.
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return false;
        if (belowYOnly.get() && pos.getY() > belowY.get()) return false;
        if (mc.player.getBlockPos().getSquaredDistance(pos) > (double) range.get() * range.get()) return false;
        if (isAmethyst(pos)) return true;                     // amethyst is always worth keeping
        if (block == null) return true;                       // position-only channels
        var it = block.asItem();
        return it != net.minecraft.item.Items.AIR && watch.get().contains(it);
    }

    /** True when this position is further out than the server admits to sending chunks. */
    private boolean pastSendRange(BlockPos pos) {
        if (!beyondSendRange.get() || serverDistance <= 0 || mc.player == null) return false;
        int dx = Math.abs((pos.getX() >> 4) - mc.player.getChunkPos().x);
        int dz = Math.abs((pos.getZ() >> 4) - mc.player.getChunkPos().z);
        return Math.max(dx, dz) > serverDistance;
    }

    /** True when the client currently shows filler here — proof the chunk data was a lie. */
    private boolean looksHidden(BlockPos pos) {
        if (pastSendRange(pos)) return true;        // certain: chunk data never reached this far
        if (!onlyHidden.get()) return true;
        if (mc.world == null) return true;
        Block b = mc.world.getBlockState(pos).getBlock();
        return b == Blocks.DEEPSLATE || b == Blocks.STONE || b == Blocks.AIR
            || b == Blocks.TUFF || b == Blocks.NETHERRACK || b == Blocks.END_STONE;
    }

    private void record(BlockPos pos, Block block, boolean hint, String what) {
        if (!wanted(pos, block)) return;

        // A recovery only means something when the server described something real in a place your
        // client currently shows as filler. If your client already draws it, nothing was hidden and
        // there is nothing to recover.
        if (!looksHidden(pos)) return;
        long key = pos.asLong();
        found.put(key, new long[]{System.currentTimeMillis(), hint ? 1 : 0});
        if (chat.get() && announced.add(key))
            shama.addon.util.Chat.info("[AntiAntiEsp] %s at %d, %d, %d — the chunk data hid this",
                what, pos.getX(), pos.getY(), pos.getZ());
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (fromBlockUpdates.get() && event.packet instanceof BlockUpdateS2CPacket p) {
            BlockPos bp = p.getPos().toImmutable();
            // The one thing that removes a find: the server telling us this block is now something
            // we do not care about. A rescan coming back empty never removes anything.
            if (forgetOnUpdate.get() && found.containsKey(bp.asLong()) && !wanted(bp, p.getState().getBlock()))
                found.remove(bp.asLong());
            record(bp, p.getState().getBlock(), false, "block change");
        }
        else if (fromBulkUpdates.get() && event.packet instanceof ChunkDeltaUpdateS2CPacket bulk) {
            bulk.visitUpdates((bp, state) -> record(bp.toImmutable(), state.getBlock(), false, "block change"));
        }
        else if (fromBlockEntities.get() && event.packet instanceof BlockEntityUpdateS2CPacket be) {
            // the block itself is not named here, so accept it on position alone
            record(be.getPos().toImmutable(), null, false, "block entity");
        }
        else if (fromParticles.get() && event.packet instanceof ParticleS2CPacket p) {
            BlockPos at = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
            record(at, null, true, "particles");
        }
        else if (fromSounds.get() && event.packet instanceof PlaySoundS2CPacket p) {
            BlockPos at = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
            record(at, null, true, "sound");
        }
    }

    /**
     * The server announces how far it will send real chunks. Knowing it means anything reported
     * beyond that distance came from a channel the chunk data never covered, which is worth saying.
     */
    @EventHandler
    private void onServerDistance(PacketEvent.Receive event) {
        // matched by name, since this packet has been renamed between versions and naming the class
        // directly would stop the addon compiling if it moves again
        String n = event.packet.getClass().getSimpleName();
        if (!n.contains("ChunkLoadDistance") && !n.contains("SetChunkCacheRadius")) return;
        try {
            for (var m : event.packet.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                int v = (int) m.invoke(event.packet);
                if (v > 1 && v <= 64) { serverDistance = v; break; }
            }
        } catch (Throwable ignored) {}
    }

    private int forceTick, sweepStep;

    /**
     * Claim a position or a view the server cannot check, then correct straight back inside the same
     * tick so nothing you see moves and the server never has cause to drag you back.
     */
    private void tryForce() {
        if (!forceData.get() || mc.player == null || mc.getNetworkHandler() == null) return;
        if (++forceTick < forceRate.get()) return;
        forceTick = 0;

        var net = mc.getNetworkHandler();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        float realYaw = mc.player.getYaw(), realPitch = mc.player.getPitch();
        boolean ground = mc.player.isOnGround();

        float yaw = realYaw, pitch = realPitch;
        if (lookSweep.get()) {
            yaw = (360f / 8) * sweepStep - 180f;
            sweepStep = (sweepStep + 1) % 8;
            pitch = 60f;                                   // angled down as it sweeps
        } else if (lookDown.get()) {
            pitch = 90f;                                   // straight at the ground
        }

        double y = claimBelow.get() ? Math.min(py, claimY.get()) : py;

        if (lookDown.get() || lookSweep.get() || claimBelow.get()) {
            net.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                px, y, pz, yaw, pitch, ground, false));
            // put the truth back immediately; without this the server corrects you and you rubber-band
            net.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                px, py, pz, realYaw, realPitch, ground, false));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tryForce();
        if (found.isEmpty() || forgetOnUpdate.get()) return;      // updates handle removal instead
        long cutoff = System.currentTimeMillis() - keepSeconds.get() * 1000L;
        found.entrySet().removeIf(e -> e.getValue()[0] < cutoff);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (found.isEmpty() || mc.player == null) return;
        SettingColor bc = blockColor.get(), hc = hintColor.get();
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;

        for (Map.Entry<Long, long[]> e : found.entrySet()) {
            BlockPos p = BlockPos.fromLong(e.getKey());
            boolean hint = e.getValue()[1] == 1;
            SettingColor c = hint ? hc : bc;
            Color line = new Color(c.r, c.g, c.b, c.a);
            Color fill = new Color(c.r, c.g, c.b, Math.min(120, c.a / 3));
            event.renderer.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                fill, line, shapeMode.get(), 0);
            if (tracers.get())
                event.renderer.line(cam.x, cam.y, cam.z, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, line);
        }
    }

    @Override
    public String getInfoString() {
        if (found.isEmpty()) return null;
        return serverDistance > 0 ? found.size() + " (server " + serverDistance + ")" : Integer.toString(found.size());
    }
}
