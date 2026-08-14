package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Schematic Builder++ — builds a litematica schematic by hand, the way a person would.
 *
 * Litematica's own printer, and Baritone's build command, both work by asking the server to place a
 * block wherever you point. They get the job done and they get you kicked, because the requests
 * arrive at a machine's pace, from angles a person would never hold, against faces a person could
 * never have clicked.
 *
 * This does the same job through the ordinary placement path instead. Every block goes down against
 * a face that is genuinely there, aimed at with a rotation that is actually sent and eased into
 * rather than snapped to, at a pace that wanders and tires. Where the schematic wants a block in
 * mid-air it puts scaffolding underneath and takes it away afterwards, and where it cannot even do
 * that it moves on and comes back rather than asking the server to accept a block floating on
 * nothing.
 *
 * The other half of the problem is what happens when a placement does not land. A printer that keeps
 * retrying the same impossible block is both obvious and useless, so each position gets a small
 * number of attempts, then goes to the back of the queue, and finally gets left alone and reported.
 * It never spins on one spot.
 */
public class SchematicBuilder extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> fileName = sg.add(new StringSetting.Builder()
        .name("schematic")
        .description("File to build, from the schematics folder in your Minecraft directory. Include the .litematic on the end. Litematica writes there by default, so anything you have saved should already be sitting in it.")
        .defaultValue("build.litematic").build());

    private final Setting<Boolean> loadNow = sg.add(new BoolSetting.Builder()
        .name("reload")
        .description("Read the file again. Turn this on after changing the name above, or after editing the schematic — it switches itself back off once the file is in.")
        .defaultValue(false).build());

    private final Setting<Boolean> originHere = sg.add(new BoolSetting.Builder()
        .name("origin-at-me")
        .description("Put the corner of the schematic where you are standing when it loads. Off uses the coordinates saved inside the file, which is what you want if the schematic was cut from this world in the first place.")
        .defaultValue(true).build());

    // ---------------------------------------------------------------- pace
    private final SettingGroup sgPace = settings.createGroup("Pace");

    private final Setting<Integer> baseDelay = sgPace.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between placements, before any variation. Around 4 is a brisk but believable rate; 2 is faster than most people can click steadily.")
        .defaultValue(4).min(1).max(40).sliderRange(2, 15).build());

    private final Setting<Integer> jitter = sgPace.add(new IntSetting.Builder()
        .name("variation")
        .description("How much that delay wanders, as a percent. Nobody places blocks on a metronome, and a perfectly even interval is one of the easiest things to spot in a packet log.")
        .defaultValue(40).min(0).max(90).sliderRange(10, 70).build());

    private final Setting<Boolean> breaks = sgPace.add(new BoolSetting.Builder()
        .name("pauses")
        .description("Stop occasionally for a moment, the way somebody does when they look at what they are doing. Long unbroken runs of placements at a steady rate are what a printer looks like.")
        .defaultValue(true).build());

    private final Setting<Integer> breakChance = sgPace.add(new IntSetting.Builder()
        .name("pause-chance")
        .description("Roughly how often to stop, as a percent chance per placement.")
        .defaultValue(3).min(1).max(30).sliderRange(1, 12).visible(breaks::get).build());

    private final Setting<Integer> breakLength = sgPace.add(new IntSetting.Builder()
        .name("pause-length")
        .description("How long a pause lasts, in ticks. It varies either side of this.")
        .defaultValue(20).min(5).max(200).sliderRange(10, 80).visible(breaks::get).build());

    // ---------------------------------------------------------------- aiming
    private final SettingGroup sgAim = settings.createGroup("Aiming");

    private final Setting<Boolean> sendRotations = sgAim.add(new BoolSetting.Builder()
        .name("send-rotations")
        .description("Actually turn towards each block before placing it. This is the single most important thing here: a placement that arrives while you are facing somewhere else is the clearest possible sign of a printer, and most anarchy checks look for exactly that.")
        .defaultValue(true).build());

    private final Setting<Double> aimNoise = sgAim.add(new DoubleSetting.Builder()
        .name("aim-noise")
        .description("How far the aim wanders off centre each time, in degrees. Hitting the exact centre of a block face every single time is not something a hand does.")
        .defaultValue(2.5).min(0).max(15).sliderRange(0, 8).decimalPlaces(1).visible(sendRotations::get).build());

    private final Setting<Boolean> smoothTurning = sgAim.add(new BoolSetting.Builder()
        .name("smooth-turning")
        .description("Turn towards each block over several ticks instead of snapping to it. A head that jumps instantly from one exact angle to the next is the thing rotation checks are built to notice, and easing between them costs nothing but a few ticks.")
        .defaultValue(true).visible(sendRotations::get).build());

    private final Setting<Double> turnSpeed = sgAim.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How far the aim can move in one tick, in degrees. Lower looks more deliberate and builds more slowly; higher gets closer to snapping.")
        .defaultValue(22).min(3).max(180).sliderRange(8, 60).decimalPlaces(0)
        .visible(smoothTurning::get).build());

    private final Setting<Boolean> overshoot = sgAim.add(new BoolSetting.Builder()
        .name("overshoot")
        .description("Go slightly past the block and settle back, the way a hand does when it moves quickly. Landing exactly on target every time, from any distance, is not something a mouse produces.")
        .defaultValue(true).visible(smoothTurning::get).build());

    private final Setting<Boolean> varyFace = sgAim.add(new BoolSetting.Builder()
        .name("vary-face")
        .description("When more than one neighbouring face would work, pick between them rather than always taking the same one. A build placed entirely off one face has a signature you can see in the packets.")
        .defaultValue(true).build());

    private final Setting<Boolean> varySpot = sgAim.add(new BoolSetting.Builder()
        .name("vary-hit-spot")
        .description("Aim at a different point within the face each time instead of dead centre. Cheap, and it removes another repeating number from every placement you send.")
        .defaultValue(true).build());

    private final Setting<Double> reach = sgAim.add(new DoubleSetting.Builder()
        .name("reach")
        .description("How far away a block can be and still be placed. Vanilla allows a little under five, and going past that is rejected outright by most servers, so this stays inside it.")
        .defaultValue(4.2).min(1).max(5).sliderRange(2, 4.5).decimalPlaces(1).build());

    // ---------------------------------------------------------------- support
    private final SettingGroup sgSupport = settings.createGroup("Support");

    private final Setting<Boolean> scaffold = sgSupport.add(new BoolSetting.Builder()
        .name("scaffolding")
        .description("Where the schematic wants a block with nothing to place it against, put a temporary block underneath first. This is the honest way to build into open air — the alternative is asking the server to accept a block floating on nothing, which is what gets printers caught. If there is nothing to scaffold with, it simply builds the rest and comes back.")
        .defaultValue(true).build());

    private final Setting<Boolean> cleanScaffold = sgSupport.add(new BoolSetting.Builder()
        .name("remove-scaffolding")
        .description("Take the temporary blocks back out once the parts they were holding up are finished. Only the ones this module put down are touched, so it can never eat its own work.")
        .defaultValue(true).visible(scaffold::get).build());

    private final Setting<Boolean> skipUnsupported = sgSupport.add(new BoolSetting.Builder()
        .name("skip-if-no-support")
        .description("With scaffolding off, leave anything that has nothing to place against rather than trying anyway. Trying anyway is the easy-place behaviour this module exists to avoid.")
        .defaultValue(true).visible(() -> !scaffold.get()).build());

    // ---------------------------------------------------------------- mistakes
    private final SettingGroup sgFail = settings.createGroup("Mistakes");

    private final Setting<Boolean> misclicks = sgFail.add(new BoolSetting.Builder()
        .name("misclicks")
        .description("Occasionally fumble one and take an extra moment before trying again. A build that goes down without a single hesitation over thousands of blocks is not something a person produces, and the cost is a handful of wasted ticks.")
        .defaultValue(true).build());

    private final Setting<Integer> misclickChance = sgFail.add(new IntSetting.Builder()
        .name("misclick-chance")
        .description("How often that happens, as a percent.")
        .defaultValue(2).min(1).max(20).sliderRange(1, 8).visible(misclicks::get).build());

    private final Setting<Boolean> fatigue = sgFail.add(new BoolSetting.Builder()
        .name("fatigue")
        .description("Slow down gradually over a long build, then recover after a pause. People do not hold the same rate for an hour, and a constant one across thousands of placements stands out more than any single packet does.")
        .defaultValue(true).build());

    private final Setting<Integer> attempts = sgFail.add(new IntSetting.Builder()
        .name("attempts")
        .description("How many times to try one position before setting it aside. Retrying forever is what makes a stuck printer obvious, and it never fixes anything.")
        .defaultValue(3).min(1).max(10).sliderRange(1, 6).build());

    private final Setting<Boolean> requeue = sgFail.add(new BoolSetting.Builder()
        .name("come-back-later")
        .description("Send a position that failed to the back of the queue instead of giving up on it. Most failures are because something else is not built yet, so by the time it comes round again the problem has usually solved itself.")
        .defaultValue(true).build());

    private final Setting<Boolean> fixWrong = sgFail.add(new BoolSetting.Builder()
        .name("fix-wrong-blocks")
        .description("Break and replace anything already standing where the schematic wants something else. Off builds around whatever is in the way, which is safer on a server where breaking is watched.")
        .defaultValue(false).build());

    private final Setting<Boolean> report = sgFail.add(new BoolSetting.Builder()
        .name("report")
        .description("Say in chat what was placed, what was skipped and why, as it goes.")
        .defaultValue(true).build());

    // ---------------------------------------------------------------- render
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> preview = sgRender.add(new BoolSetting.Builder()
        .name("preview").description("Draw what is still to be built.").defaultValue(true).build());
    private final Setting<Integer> previewRange = sgRender.add(new IntSetting.Builder()
        .name("preview-range").description("How far the preview reaches, in blocks.")
        .defaultValue(48).min(4).max(256).sliderRange(16, 128).visible(preview::get).build());
    private final Setting<SettingColor> todoColor = sgRender.add(new ColorSetting.Builder()
        .name("todo-color").description("Colour for blocks still to place.")
        .defaultValue(new SettingColor(90, 190, 255, 60)).visible(preview::get).build());
    private final Setting<SettingColor> stuckColor = sgRender.add(new ColorSetting.Builder()
        .name("stuck-color").description("Colour for the ones it gave up on, so you can see what needs a hand.")
        .defaultValue(new SettingColor(255, 70, 70, 110)).visible(preview::get).build());

    /** One block the schematic wants, and how many times we have tried to put it there. */
    private static final class Target {
        final BlockPos pos;
        final BlockState state;
        int tries;
        Target(BlockPos pos, BlockState state) { this.pos = pos; this.state = state; }
    }

    private final List<Target> queue = new ArrayList<>();
    private final Set<Long> stuck = new HashSet<>();
    private final Set<Long> scaffoldPlaced = new HashSet<>();
    private int timer, pausedFor, placed, skipped;
    /** Where the aim actually is right now, so it can be walked towards the target rather than snapped. */
    private float curYaw, curPitch;
    private boolean aimStarted;
    private int sinceBreak;
    private String status = "nothing loaded";

    public SchematicBuilder() {
        super(shama.addon.ShamaAddon.PLAYER, "schematic-builder++",
            "Builds a litematica schematic through the ordinary placement path — real rotations eased into rather than snapped to, real faces, scaffolding instead of blocks floating on nothing, and a pace that wanders and tires.");
    }

    @Override
    public void onActivate() {
        queue.clear(); stuck.clear(); scaffoldPlaced.clear();
        timer = pausedFor = placed = skipped = sinceBreak = 0;
        aimStarted = false;
        status = "nothing loaded";
        loadNow.set(true);
    }

    @Override
    public void onDeactivate() { queue.clear(); stuck.clear(); scaffoldPlaced.clear(); }

    // ================================================================ loading

    /**
     * Read a .litematic and turn it into a list of positions to fill.
     *
     * The format is gzipped NBT: a Regions compound, each region carrying its size, a palette of
     * block states, and a long array holding one packed index per position. The index width is
     * whatever it takes to count the palette, minimum two bits.
     *
     * NbtIo's read method has been renamed more than once, so it is found by signature rather than
     * called by name — a rename disables loading instead of stopping the addon compiling.
     */
    private void load() {
        queue.clear(); stuck.clear(); placed = 0; skipped = 0;
        try {
            // the client knows its own directory; FabricLoader would do too, but this needs no extra API
            java.nio.file.Path dir = mc.runDirectory.toPath().resolve("schematics");
            java.nio.file.Path file = dir.resolve(fileName.get());
            if (!java.nio.file.Files.exists(file)) {
                status = "no file called " + fileName.get() + " in " + dir;
                if (report.get()) shama.addon.util.Chat.warning("[Builder] %s", status);
                return;
            }

            NbtCompound root = readNbt(file);
            if (root == null) { status = "could not read that file"; return; }

            NbtCompound regions = root.getCompound("Regions").orElse(null);
            if (regions == null) { status = "no regions in that schematic"; return; }

            BlockPos origin = originHere.get() && mc.player != null
                ? mc.player.getBlockPos() : BlockPos.ORIGIN;

            for (String name : regions.getKeys()) {
                NbtCompound r = regions.getCompound(name).orElse(null);
                if (r == null) continue;
                readRegion(r, origin);
            }

            // bottom up, then nearest first: you cannot stand on what you have not built yet
            if (mc.player != null) {
                BlockPos me = mc.player.getBlockPos();
                queue.sort((a, b) -> {
                    if (a.pos.getY() != b.pos.getY()) return Integer.compare(a.pos.getY(), b.pos.getY());
                    return Double.compare(me.getSquaredDistance(a.pos), me.getSquaredDistance(b.pos));
                });
            }
            status = queue.size() + " blocks to place";
            if (report.get()) shama.addon.util.Chat.info("[Builder] loaded %s — %d blocks", fileName.get(), queue.size());
        } catch (Throwable t) {
            status = "failed to load: " + t.getClass().getSimpleName();
            if (report.get()) shama.addon.util.Chat.warning("[Builder] %s", status);
        }
    }

    private NbtCompound readNbt(java.nio.file.Path file) {
        for (var m : net.minecraft.nbt.NbtIo.class.getMethods()) {
            if (!m.getName().toLowerCase().contains("readcompressed")) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 2 && p[0].isAssignableFrom(java.nio.file.Path.class))
                    return (NbtCompound) m.invoke(null, file, newSizeTracker());
                if (p.length == 1 && p[0].isAssignableFrom(java.nio.file.Path.class))
                    return (NbtCompound) m.invoke(null, file);
                if (p.length == 2 && p[0].isAssignableFrom(java.io.InputStream.class))
                    try (var in = java.nio.file.Files.newInputStream(file)) {
                        return (NbtCompound) m.invoke(null, in, newSizeTracker());
                    }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object newSizeTracker() {
        try {
            for (var m : net.minecraft.nbt.NbtSizeTracker.class.getMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType() == net.minecraft.nbt.NbtSizeTracker.class)
                    return m.invoke(null, 0x20000000L);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void readRegion(NbtCompound r, BlockPos origin) {
        NbtCompound posC = r.getCompound("Position").orElse(null);
        NbtCompound sizeC = r.getCompound("Size").orElse(null);
        if (posC == null || sizeC == null) return;

        int px = posC.getInt("x").orElse(0), py = posC.getInt("y").orElse(0), pz = posC.getInt("z").orElse(0);
        int sx = sizeC.getInt("x").orElse(0), sy = sizeC.getInt("y").orElse(0), sz = sizeC.getInt("z").orElse(0);

        // a region can be given with a negative size, meaning it extends the other way
        int ox = Math.min(px, px + sx + (sx < 0 ? 1 : -1));
        int oy = Math.min(py, py + sy + (sy < 0 ? 1 : -1));
        int oz = Math.min(pz, pz + sz + (sz < 0 ? 1 : -1));
        int w = Math.abs(sx), h = Math.abs(sy), d = Math.abs(sz);
        if (w == 0 || h == 0 || d == 0) return;

        var paletteList = r.getList("BlockStatePalette").orElse(null);
        long[] data = r.getLongArray("BlockStates").orElse(null);
        if (paletteList == null || data == null || data.length == 0) return;

        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteList.size(); i++) {
            NbtCompound e = paletteList.getCompound(i).orElse(null);
            palette.add(e == null ? Blocks.AIR.getDefaultState() : stateFrom(e));
        }
        if (palette.isEmpty()) return;

        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long mask = (1L << bits) - 1L;

        for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) for (int x = 0; x < w; x++) {
            long index = (long) (y * d + z) * w + x;
            long bitPos = index * bits;
            int slot = (int) (bitPos >> 6);
            int offset = (int) (bitPos & 63);
            if (slot >= data.length) continue;

            long value = data[slot] >>> offset;
            if (offset + bits > 64 && slot + 1 < data.length)
                value |= data[slot + 1] << (64 - offset);
            int id = (int) (value & mask);
            if (id < 0 || id >= palette.size()) continue;

            BlockState st = palette.get(id);
            if (st.isAir()) continue;
            queue.add(new Target(new BlockPos(origin.getX() + ox + x, origin.getY() + oy + y, origin.getZ() + oz + z), st));
        }
    }

    private BlockState stateFrom(NbtCompound e) {
        try {
            String name = e.getString("Name").orElse("minecraft:air");
            Block b = Registries.BLOCK.get(net.minecraft.util.Identifier.of(name));
            return b == null ? Blocks.AIR.getDefaultState() : b.getDefaultState();
        } catch (Throwable t) { return Blocks.AIR.getDefaultState(); }
    }

    // ================================================================ building

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (loadNow.get()) { loadNow.set(false); load(); }
        if (mc.player == null || mc.world == null || queue.isEmpty()) return;

        if (pausedFor > 0) { pausedFor--; return; }

        // the longer this has run without a rest, the slower it goes
        int pace = baseDelay.get();
        if (fatigue.get()) pace += Math.min(4, sinceBreak / 250);
        if (++timer < shama.addon.util.Humanize.jitter(pace, jitter.get())) return;
        timer = 0;
        sinceBreak++;

        // stop for a moment now and then, the way somebody checking their work does
        if (breaks.get() && shama.addon.util.Humanize.chance(breakChance.get())) {
            pausedFor = shama.addon.util.Humanize.jitter(breakLength.get(), 50);
            sinceBreak = 0;                        // a rest clears the accumulated slowdown
            return;
        }

        // fumble one now and then, and take a moment over it
        if (misclicks.get() && shama.addon.util.Humanize.shouldMiss(misclickChance.get())) {
            pausedFor = shama.addon.util.Humanize.jitter(6, 60);
            return;
        }

        Target t = nextReachable();
        if (t == null) {
            // nothing left within reach; if the build is finished, take the scaffolding back out
            if (queue.isEmpty() && cleanScaffold.get() && scaffold.get()) removeScaffolding();
            return;
        }

        BlockState here = mc.world.getBlockState(t.pos);
        if (here.getBlock() == t.state.getBlock()) { queue.remove(t); return; }   // already right

        if (!here.isAir()) {
            if (!fixWrong.get()) { queue.remove(t); skipped++; return; }
            mc.interactionManager.breakBlock(t.pos);
            mc.player.swingHand(Hand.MAIN_HAND);
            return;
        }

        if (!selectBlock(t.state.getBlock())) { retire(t, "not carrying it"); return; }

        Direction face = supportFace(t.pos);
        if (face == null) {
            if (scaffold.get()) { placeScaffold(t); return; }
            // Scaffolding is off. Either leave it alone, or put it to the back of the queue in the
            // hope its neighbours get built first — but never place it against nothing, which is the
            // easy-place behaviour this module exists to avoid.
            if (skipUnsupported.get()) retire(t, "nothing to place against");
            else if (requeue.get() && ++t.tries < attempts.get()) { queue.remove(t); queue.add(t); }
            else retire(t, "nothing to place against");
            return;
        }

        if (place(t.pos, face)) { queue.remove(t); placed++; }
        else if (++t.tries >= attempts.get()) retire(t, "would not go down");
        else if (requeue.get()) { queue.remove(t); queue.add(t); }
    }

    /**
     * Take out the temporary blocks once the parts they were holding up are done.
     *
     * Only the ones this module put down are touched, and only where the schematic did not want a
     * block anyway, so it can never eat its own work.
     */
    private void removeScaffolding() {
        if (scaffoldPlaced.isEmpty() || mc.player == null) return;
        double r2 = reach.get() * reach.get();
        Vec3d eye = mc.player.getEyePos();
        for (long k : new java.util.ArrayList<>(scaffoldPlaced)) {
            BlockPos p = BlockPos.fromLong(k);
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) > r2) continue;
            if (mc.world.getBlockState(p).isAir()) { scaffoldPlaced.remove(k); continue; }
            mc.interactionManager.breakBlock(p);
            mc.player.swingHand(Hand.MAIN_HAND);
            scaffoldPlaced.remove(k);
            return;                                   // one a tick, same as everything else here
        }
    }

    /** The first thing in the queue that is close enough to actually reach. */
    private Target nextReachable() {
        double r2 = reach.get() * reach.get();
        Vec3d eye = mc.player.getEyePos();
        for (Target t : queue) {
            if (eye.squaredDistanceTo(Vec3d.ofCenter(t.pos)) <= r2) return t;
        }
        return null;
    }

    /**
     * A face of a neighbouring block that is genuinely there to click on.
     *
     * This is the whole difference between this and an easy-place printer. If nothing is next to the
     * position, there is nothing to click, and the honest answer is to build something there first
     * rather than to ask the server to accept a placement against thin air.
     */
    private Direction supportFace(BlockPos pos) {
        List<Direction> options = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState s = mc.world.getBlockState(n);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            // and it has to be a face we could actually see from where we stand
            if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(n)) > reach.get() * reach.get()) continue;
            options.add(d.getOpposite());
        }
        if (options.isEmpty()) return null;
        if (!varyFace.get()) return options.get(0);
        return options.get((int) (Math.random() * options.size()));
    }

    /** Put a temporary block under something that has nothing to stand on. */
    private void placeScaffold(Target t) {
        BlockPos under = t.pos.down();
        if (!mc.world.getBlockState(under).isAir()) return;

        Direction face = supportFace(under);
        // Nothing to scaffold from, or nothing to scaffold with: neither is a reason to give up on
        // the block. Put it to the back of the queue and carry on with the rest of the build, since
        // by the time it comes round again the neighbours it needs are usually standing.
        if (face == null || !selectAnyBuildingBlock()) {
            if (requeue.get() && ++t.tries < attempts.get()) { queue.remove(t); queue.add(t); }
            else retire(t, "nothing to build it against yet");
            return;
        }
        if (place(under, face)) scaffoldPlaced.add(under.asLong());
    }

    /**
     * Send the placement, aiming at the face first.
     *
     * The rotation goes out as its own packet before the interaction, because a placement that
     * arrives while you are facing elsewhere is the single clearest sign of a printer. The aim is
     * nudged off centre, the point within the face moved around, and the turn eased into over
     * several ticks rather than snapped to, so no two placements carry the same numbers and no
     * single one arrives from an angle a hand could not have reached.
     */
    private boolean place(BlockPos pos, Direction face) {
        BlockPos against = pos.offset(face.getOpposite());
        Vec3d centre = Vec3d.ofCenter(against);

        double ox = 0, oy = 0, oz = 0;
        if (varySpot.get()) {
            // stay well inside the face; the edges are where misclicks happen
            ox = (Math.random() - 0.5) * 0.6;
            oy = (Math.random() - 0.5) * 0.6;
            oz = (Math.random() - 0.5) * 0.6;
        }
        Vec3d hit = centre.add(
            face.getOffsetX() * 0.5 + (face.getOffsetX() == 0 ? ox : 0),
            face.getOffsetY() * 0.5 + (face.getOffsetY() == 0 ? oy : 0),
            face.getOffsetZ() * 0.5 + (face.getOffsetZ() == 0 ? oz : 0));

        if (sendRotations.get()) {
            Vec3d eye = mc.player.getEyePos();
            double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
            double flat = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
            float n = aimNoise.get().floatValue();   // a boxed Double will not cast straight to float
            yaw += shama.addon.util.Humanize.rotationNoise(n);
            pitch += shama.addon.util.Humanize.rotationNoise(n);
            pitch = clampPitch(pitch);

            if (!aimStarted) { curYaw = mc.player.getYaw(); curPitch = mc.player.getPitch(); aimStarted = true; }

            if (smoothTurning.get()) {
                // A hand that moves quickly tends to go a little past and come back, so the target is
                // nudged beyond the block when the turn is a long one.
                if (overshoot.get() && Math.abs(wrap(yaw - curYaw)) > 25f)
                    yaw += wrap(yaw - curYaw) > 0 ? 3f : -3f;

                float step = turnSpeed.get().floatValue();
                float dYaw = wrap(yaw - curYaw);
                float dPitch = pitch - curPitch;
                curYaw += Math.max(-step, Math.min(step, dYaw));
                curPitch += Math.max(-step, Math.min(step, dPitch));

                // still swinging round to face it: send the turn and place on a later tick
                if (Math.abs(wrap(yaw - curYaw)) > 4f || Math.abs(pitch - curPitch) > 4f) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        curYaw, clampPitch(curPitch), mc.player.isOnGround(), false));
                    return false;
                }
            }

            curYaw = yaw; curPitch = pitch;
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                curYaw, clampPitch(curPitch), mc.player.isOnGround(), false));
        }

        BlockHitResult res = new BlockHitResult(hit, face, against, false);
        var before = mc.world.getBlockState(pos).getBlock();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, res);
        mc.player.swingHand(Hand.MAIN_HAND);
        return mc.world.getBlockState(pos).getBlock() != before;
    }

    /** Shortest way round the circle, so turning from 179 to -179 is two degrees, not 358. */
    private static float wrap(float degrees) {
        while (degrees > 180f) degrees -= 360f;
        while (degrees < -180f) degrees += 360f;
        return degrees;
    }

    private static float clampPitch(float p) { return Math.max(-90f, Math.min(90f, p)); }

    private boolean selectBlock(Block want) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() == want) {
                mc.player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    private boolean selectAnyBuildingBlock() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.getItem() instanceof BlockItem && s.getCount() > 1) {
                mc.player.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    /** Give up on a position, once, and say why. It never comes back round again. */
    private void retire(Target t, String why) {
        queue.remove(t);
        stuck.add(t.pos.asLong());
        skipped++;
        if (report.get())
            shama.addon.util.Chat.warning("[Builder] left %d, %d, %d — %s",
                t.pos.getX(), t.pos.getY(), t.pos.getZ(), why);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!preview.get() || mc.player == null) return;
        double r2 = (double) previewRange.get() * previewRange.get();
        Vec3d eye = mc.player.getEyePos();

        SettingColor tc = todoColor.get();
        Color fill = new Color(tc.r, tc.g, tc.b, tc.a);
        Color line = new Color(tc.r, tc.g, tc.b, Math.min(255, tc.a + 120));
        int drawn = 0;
        for (Target t : queue) {
            if (drawn++ > 3000) break;
            if (eye.squaredDistanceTo(Vec3d.ofCenter(t.pos)) > r2) continue;
            event.renderer.box(t.pos.getX(), t.pos.getY(), t.pos.getZ(),
                t.pos.getX() + 1, t.pos.getY() + 1, t.pos.getZ() + 1, fill, line, ShapeMode.Both, 0);
        }

        SettingColor sc = stuckColor.get();
        Color sf = new Color(sc.r, sc.g, sc.b, sc.a);
        Color sl = new Color(sc.r, sc.g, sc.b, Math.min(255, sc.a + 120));
        for (long k : stuck) {
            BlockPos p = BlockPos.fromLong(k);
            if (eye.squaredDistanceTo(Vec3d.ofCenter(p)) > r2) continue;
            event.renderer.box(p.getX(), p.getY(), p.getZ(),
                p.getX() + 1, p.getY() + 1, p.getZ() + 1, sf, sl, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() {
        if (queue.isEmpty()) return status;
        return queue.size() + " left, " + placed + " done" + (skipped > 0 ? ", " + skipped + " skipped" : "");
    }
}
