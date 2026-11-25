package shama.addon.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import shama.addon.util.BaritoneBridge;
import shama.addon.util.Chat;
import shama.addon.util.Highways;

import java.util.ArrayList;
import java.util.List;

/**
 * Travel — Baritone takes you to a position.
 *
 * Overland it simply walks there. A long overworld trip can instead go through the Nether, where
 * every block covers eight: it heads for the nearest nether portal, crosses at an eighth of the
 * distance, and comes back out through a portal near the destination. In the Nether it can ride the
 * highways — the dug-out roads running from 0, 0 along the axes and diagonals — getting on at the
 * spot nearest you, changing highways at 0, 0 when that is shorter, and stepping off nearest where
 * you are going. With highway mining it digs the highway tunnel out as it goes instead of only
 * clearing a path for itself.
 *
 * Baritone does all of the walking, mining and pillaring. Baritone is optional: without it this
 * module tells you so and switches itself off.
 */
public class Travel extends Module {
    public enum Coords { Overworld, Nether }
    public enum Route { Auto, Overland, ThroughNether }

    private enum Phase { PLAN, TO_PORTAL, ENTERING, LEGS, TO_EXIT, WAIT_EXIT, DONE }

    private final SettingGroup sgTarget = settings.getDefaultGroup();
    private final SettingGroup sgRoute = settings.createGroup("Route");
    private final SettingGroup sgHighway = settings.createGroup("Highways");
    private final SettingGroup sgMining = settings.createGroup("Mining");
    private final SettingGroup sgBehaviour = settings.createGroup("Behaviour");

    // ---------------------------------------------------------------- target

    public final Setting<Integer> targetX = sgTarget.add(new IntSetting.Builder()
        .name("x").description("Where you are going: X. Set it here or with .travel <x> <z>.")
        .defaultValue(0).range(-30_000_000, 30_000_000).noSlider().build());
    public final Setting<Integer> targetZ = sgTarget.add(new IntSetting.Builder()
        .name("z").description("Where you are going: Z.")
        .defaultValue(0).range(-30_000_000, 30_000_000).noSlider().build());
    public final Setting<Boolean> useY = sgTarget.add(new BoolSetting.Builder()
        .name("use-y").description("Arrive at an exact height as well. Off, any height in that column counts as arrived.")
        .defaultValue(false).build());
    public final Setting<Integer> targetY = sgTarget.add(new IntSetting.Builder()
        .name("y").description("Where you are going: Y, when use-y is on.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320).visible(useY::get).build());
    public final Setting<Coords> coordsIn = sgTarget.add(new EnumSetting.Builder<Coords>()
        .name("coords-in").description("Which dimension the position is written in. Overworld coordinates are reached through the Nether at an eighth of their size; Nether coordinates are where the trip ends.")
        .defaultValue(Coords.Overworld).build());

    // ---------------------------------------------------------------- route

    private final Setting<Route> route = sgRoute.add(new EnumSetting.Builder<Route>()
        .name("route").description("How an overworld trip gets there. Overland walks the whole way. Through-nether goes in at the nearest portal and out at one near the destination. Auto goes through the Nether when the trip is longer than nether-above. Starting in the Nether, the trip always stays in it until the end.")
        .defaultValue(Route.Auto).build());
    private final Setting<Integer> netherAbove = sgRoute.add(new IntSetting.Builder()
        .name("nether-above").description("With route on Auto, overworld trips longer than this many blocks go through the Nether.")
        .defaultValue(1000).min(0).sliderRange(100, 10000).visible(() -> route.get() == Route.Auto).build());
    private final Setting<Integer> portalSearch = sgRoute.add(new IntSetting.Builder()
        .name("portal-search").description("How far around you to look for a nether portal to go in by, in blocks. If none is loaded that close, Baritone heads for the nearest one it remembers seeing.")
        .defaultValue(256).min(16).sliderRange(32, 512).build());
    private final Setting<Integer> exitRadius = sgRoute.add(new IntSetting.Builder()
        .name("exit-radius").description("How far from the Nether end of the trip a portal may be and still be used to come out. With none that close it stops and tells you where you are, so you can build one; walk into it and the trip carries on.")
        .defaultValue(96).min(8).sliderRange(16, 256).build());

    // ---------------------------------------------------------------- highways

    private final Setting<Boolean> useHighways = sgHighway.add(new BoolSetting.Builder()
        .name("use-highways").description("In the Nether, travel along the highways rather than in a straight line: on at the spot nearest you, along, and off nearest the destination — changing highways at 0, 0 when that is shorter.")
        .defaultValue(true).build());
    private final Setting<Boolean> diagonals = sgHighway.add(new BoolSetting.Builder()
        .name("diagonals").description("Use the four diagonal highways as well as the four on the axes.")
        .defaultValue(true).visible(useHighways::get).build());
    private final Setting<Integer> highwayY = sgHighway.add(new IntSetting.Builder()
        .name("highway-y").description("The height you walk at on the highways: your feet, not the floor. 120 is the usual height for dug Nether highways; check what your server uses.")
        .defaultValue(120).range(1, 127).sliderRange(100, 127).visible(useHighways::get).build());
    private final Setting<Integer> highwayMin = sgHighway.add(new IntSetting.Builder()
        .name("highway-min").description("Nether trips shorter than this many blocks go straight there; getting on and off a highway is not worth it for a short hop.")
        .defaultValue(500).min(0).sliderRange(0, 3000).visible(useHighways::get).build());
    private final Setting<Double> offWeight = sgHighway.add(new DoubleSetting.Builder()
        .name("highway-preference").description("How much a block walked off the highways counts against one walked on them. At 1 the shortest way wins even if it barely uses a highway. At 2 a block of open Nether is worth two of highway, which keeps you on the highways unless they really are the long way round.")
        .defaultValue(2.0).min(1.0).max(5.0).sliderRange(1.0, 4.0).decimalPlaces(1).visible(useHighways::get).build());
    private final Setting<Integer> gap = sgHighway.add(new IntSetting.Builder()
        .name("waypoint-gap").description("How far apart the points Baritone is sent to along a highway are. Closer keeps it on the highway; further lets it plan longer stretches at once.")
        .defaultValue(64).min(8).sliderRange(16, 256).visible(useHighways::get).build());

    // ---------------------------------------------------------------- mining

    private final Setting<Boolean> mineThrough = sgMining.add(new BoolSetting.Builder()
        .name("mine-through").description("Let Baritone break blocks in its way. Off, it only walks where it can already walk.")
        .defaultValue(true).build());
    private final Setting<Boolean> allowPlace = sgMining.add(new BoolSetting.Builder()
        .name("pillar-and-bridge").description("Let Baritone place throwaway blocks from your inventory to climb up or cross gaps. Getting up to highway height in the Nether usually needs it.")
        .defaultValue(true).build());
    private final Setting<Boolean> highwayMine = sgMining.add(new BoolSetting.Builder()
        .name("highway-mine").description("Dig the highway tunnel out as you go: every stretch is mined to the full width and height below before you walk it, rather than Baritone opening only a one-by-two path for itself. Slower, and it uses up your pickaxe; it leaves a proper highway behind you.")
        .defaultValue(false).visible(useHighways::get).build());
    private final Setting<Integer> tunnelWidth = sgMining.add(new IntSetting.Builder()
        .name("tunnel-width").description("How wide the dug highway is, centred on the highway's line. Diagonal tunnels are always at least two wide, or you could not walk them.")
        .defaultValue(3).range(1, 9).sliderRange(1, 7).visible(() -> useHighways.get() && highwayMine.get()).build());
    private final Setting<Integer> tunnelHeight = sgMining.add(new IntSetting.Builder()
        .name("tunnel-height").description("How tall the dug highway is, from your feet up.")
        .defaultValue(3).range(2, 6).sliderRange(2, 5).visible(() -> useHighways.get() && highwayMine.get()).build());

    // ---------------------------------------------------------------- behaviour

    private final Setting<Boolean> smoothTurning = sgBehaviour.add(new BoolSetting.Builder()
        .name("smooth-turning").description("Have Baritone turn your head gradually rather than snapping to each new direction.")
        .defaultValue(true).build());
    private final Setting<Boolean> stopOnArrival = sgBehaviour.add(new BoolSetting.Builder()
        .name("stop-on-arrival").description("Switch this module off once you get there.")
        .defaultValue(true).build());
    private final Setting<Boolean> sound = sgBehaviour.add(new BoolSetting.Builder()
        .name("sound").description("Chime when you arrive, and when the trip needs you to do something.")
        .defaultValue(true).build());
    private final Setting<Integer> stuckSeconds = sgBehaviour.add(new IntSetting.Builder()
        .name("stuck-after").description("Seconds without real progress before the current goal is handed to Baritone again. After three tries without progress a point on the way is skipped; the destination itself is never skipped — the trip stops and tells you instead.")
        .defaultValue(45).min(10).sliderRange(15, 180).build());

    public Travel() {
        super(shama.addon.ShamaAddon.MOVEMENT, "travel++",
            "Baritone takes you to a position — straight there, or through the Nether along the highways, mining through or digging the highway out as it goes.");
    }

    // ---------------------------------------------------------------- state

    /**
     * One stop on the way: x, z, a height or Integer.MIN_VALUE for any, and boxes to dig first.
     * Points along a highway also carry the point before them (px, pz), so a point you have already
     * run past can be recognised and not walked back to.
     */
    private record Stop(int x, int z, int y, List<int[]> dig, String what, boolean along, int px, int pz) {
        Stop(int x, int z, int y, List<int[]> dig, String what) { this(x, z, y, dig, what, false, 0, 0); }
    }

    private Phase phase = Phase.PLAN;
    private final List<Stop> stops = new ArrayList<>();
    private int stopIndex, digIndex, retries;
    private boolean issued, toldExit, cameFromNether;
    private int ticks;
    private BlockPos portal;
    private Object lastDim;
    private long phaseAt, progressAt, settleUntil, digStartedAt;
    private double progressX, progressZ;
    private String status = "";

    @Override
    public void onActivate() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            Chat.error("[travel] Baritone is not installed, so there is nothing to do the walking.");
            toggle();
            return;
        }
        BaritoneBridge.travelSettings(mineThrough.get(), allowPlace.get(), smoothTurning.get());
        stops.clear();
        phase = Phase.PLAN;
        lastDim = mc.world != null ? mc.world.getRegistryKey() : null;
        portal = null;
        toldExit = false;
        cameFromNether = false;
        settleUntil = 0;
        status = "planning";
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) {
            BaritoneBridge.stop();
            BaritoneBridge.restoreTravel();
        }
        stops.clear();
        phase = Phase.PLAN;
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    @Override
    public String getInfoString() {
        return status.isEmpty() ? null : status;
    }

    // ---------------------------------------------------------------- dimensions and coordinates

    private boolean inNether() { return mc.world.getRegistryKey() == World.NETHER; }
    private boolean inOverworld() { return mc.world.getRegistryKey() == World.OVERWORLD; }

    /** The destination written in the dimension you are standing in. */
    private double[] targetHere() {
        double x = targetX.get(), z = targetZ.get();
        if (coordsIn.get() == Coords.Overworld && inNether()) { x /= 8; z /= 8; }
        else if (coordsIn.get() == Coords.Nether && inOverworld()) { x *= 8; z *= 8; }
        return new double[]{x, z};
    }

    private double horizontal(double x, double z) {
        double dx = mc.player.getX() - x, dz = mc.player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void setPhase(Phase p, String s) {
        phase = p;
        status = s;
        phaseAt = System.currentTimeMillis();
        issued = false;
        resetProgress();
    }

    private void resetProgress() {
        progressAt = System.currentTimeMillis();
        if (mc.player != null) { progressX = mc.player.getX(); progressZ = mc.player.getZ(); }
        retries = 0;
    }

    // ---------------------------------------------------------------- the trip

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        if (mc.player.isDead()) {
            Chat.warning("[travel] You died; trip stopped.");
            toggle();
            return;
        }

        // through a portal: wait a moment for the ground to arrive, then plan the next part
        Object dim = mc.world.getRegistryKey();
        if (dim != lastDim) {
            // coming out of the Nether means that part is done; never head back in for the same trip
            if (lastDim == World.NETHER && inOverworld()) cameFromNether = true;
            lastDim = dim;
            BaritoneBridge.stop();
            stops.clear();
            portal = null;
            settleUntil = now + 2500;
            setPhase(Phase.PLAN, "arrived through a portal");
            return;
        }
        if (now < settleUntil) return;
        if (++ticks % 5 != 0) return;                 // decisions four times a second are plenty

        switch (phase) {
            case PLAN -> plan();
            case TO_PORTAL -> toPortal(now);
            case ENTERING -> entering(now);
            case LEGS -> legs(now);
            case TO_EXIT -> toExit(now);
            case WAIT_EXIT -> { /* waiting for you to build a portal and walk in; the dimension change moves us on */ }
            case DONE -> { }
        }
    }

    /** Work out what this part of the trip is, from where you stand. */
    private void plan() {
        boolean nether = inNether(), overworld = inOverworld();
        double[] t = targetHere();

        if (overworld) {
            if (cameFromNether && !endsHere()) {
                // the destination is in the Nether but you have come out of it: nothing sensible to do
                Chat.warning("[travel] Came out of the Nether, but the destination is in it. Stopping.");
                toggle();
                return;
            }
            boolean throughNether = !cameFromNether && switch (route.get()) {
                case Overland -> false;
                case ThroughNether -> true;
                case Auto -> coordsIn.get() == Coords.Nether || horizontal(t[0], t[1]) > netherAbove.get();
            };
            if (throughNether) {
                setPhase(Phase.TO_PORTAL, "to a portal");
                return;
            }
            finalLeg(t);
            return;
        }

        if (nether) {
            netherLegs(t);
            return;
        }

        // the End, or anywhere else: no portals to use, walk the coordinates as written
        Chat.warning("[travel] Not in the Overworld or the Nether — walking straight to the coordinates as written.");
        finalLeg(new double[]{targetX.get(), targetZ.get()});
    }

    /** The last part: straight to the destination in this dimension. */
    private void finalLeg(double[] t) {
        stops.clear();
        int y = useY.get() && endsHere() ? targetY.get() : Integer.MIN_VALUE;
        stops.add(new Stop((int) Math.floor(t[0]), (int) Math.floor(t[1]), y, List.of(), "destination"));
        startLegs("to " + (int) Math.floor(t[0]) + ", " + (int) Math.floor(t[1]));
    }

    /** Whether the trip ends in the dimension you are in now. */
    private boolean endsHere() {
        return (coordsIn.get() == Coords.Nether) == inNether();
    }

    /** The Nether part: on to a highway, along it, off at the end — or straight there. */
    private void netherLegs(double[] t) {
        stops.clear();
        double sx = mc.player.getX(), sz = mc.player.getZ();
        double direct = horizontal(t[0], t[1]);
        Highways.Route hw = null;
        if (useHighways.get() && direct >= highwayMin.get()) {
            hw = Highways.best(sx, sz, t[0], t[1], diagonals.get(), offWeight.get());
            if (hw != null && hw.cost() >= direct * offWeight.get()) hw = null;   // the highways are the long way round
        }

        if (hw != null) {
            int hy = highwayY.get();
            stops.add(new Stop(hw.entryX(), hw.entryZ(), hy, List.of(), "on to the " + hw.first().name() + " highway"));
            for (Highways.Leg leg : hw.legs()) {
                int prevT = leg.fromT();
                for (int[] w : Highways.waypoints(leg, gap.get())) {
                    List<int[]> dig = highwayMine.get()
                        ? Highways.tunnel(leg, prevT, w[2], hy, tunnelWidth.get(), tunnelHeight.get())
                        : List.of();
                    stops.add(new Stop(w[0], w[1], hy, dig, leg.name() + " highway", true, prevT * leg.dx(), prevT * leg.dz()));
                    prevT = w[2];
                }
            }
            Chat.info("[travel] Riding %s: about %.0f blocks, against %.0f in a straight line.", hw.name(), hw.blocks(), direct);
        }

        int y = useY.get() && endsHere() ? targetY.get() : Integer.MIN_VALUE;
        stops.add(new Stop((int) Math.floor(t[0]), (int) Math.floor(t[1]), y, List.of(),
            endsHere() ? "destination" : "the Nether end of the trip"));
        startLegs(hw != null ? "highway " + hw.name() : "across the Nether");
    }

    private void startLegs(String what) {
        stopIndex = 0;
        digIndex = 0;
        setPhase(Phase.LEGS, what);
    }

    private boolean reached(Stop s) {
        double d = horizontal(s.x() + 0.5, s.z() + 0.5);
        if (d > (s.y() == Integer.MIN_VALUE ? 2.5 : 1.5)) return false;
        return s.y() == Integer.MIN_VALUE || Math.abs(mc.player.getBlockY() - s.y()) <= 1;
    }

    /** Along a highway: you are already beyond this point, measured along the stretch leading to it. */
    private boolean passed(Stop s) {
        if (!s.along()) return false;
        double vx = s.x() - s.px(), vz = s.z() - s.pz();
        double len2 = vx * vx + vz * vz;
        if (len2 < 1) return false;
        double wx = mc.player.getX() - s.px(), wz = mc.player.getZ() - s.pz();
        double u = (wx * vx + wz * vz) / len2;
        // past the end of the stretch, and still close to the highway's line
        double off = Math.abs(wx * vz - wz * vx) / Math.sqrt(len2);
        return u >= 1.0 && off < 8;
    }

    private void legs(long now) {
        if (stopIndex >= stops.size()) { legsDone(); return; }
        Stop s = stops.get(stopIndex);

        // skip points you have reached or already run past — Baritone can carry you beyond one
        // on its way, and walking back to it would be pointless. A stretch still to dig is never skipped.
        if (reached(s) || (s.dig().isEmpty() && passed(s))) { nextStop(); return; }

        // highway mining: clear this stretch before walking it
        if (digIndex < s.dig().size()) {
            if (!issued) {
                int[] b = s.dig().get(digIndex);
                BaritoneBridge.clearArea(new BlockPos(b[0], b[1], b[2]), new BlockPos(b[3], b[4], b[5]));
                issued = true;
                digStartedAt = now;
                status = "digging " + s.what() + " (" + (digIndex + 1) + "/" + s.dig().size() + ")";
                resetProgress();
                return;
            }
            if (!BaritoneBridge.clearing()) { digIndex++; issued = false; return; }
            // a stretch that has not finished in five minutes has something in it Baritone cannot
            // get at; leave it and carry on rather than standing there for ever
            if (now - digStartedAt > 5 * 60_000L) {
                Chat.warning("[travel] Gave up digging a stretch of the %s that would not clear; walking on.", s.what());
                BaritoneBridge.stop();
                digIndex++;
                issued = false;
            }
            return;
        }

        if (!issued) {
            boolean ok = s.y() == Integer.MIN_VALUE ? BaritoneBridge.goXZ(s.x(), s.z()) : BaritoneBridge.goBlock(s.x(), s.y(), s.z());
            if (!ok) { Chat.error("[travel] Baritone would not take the goal; stopping."); toggle(); return; }
            issued = true;
            status = s.what() + " (" + (int) horizontal(s.x(), s.z()) + " blocks)";
            return;
        }

        status = s.what() + " (" + (int) horizontal(s.x(), s.z()) + " blocks)";
        // Baritone gave up or finished without us being there: hand it the goal again
        if (!BaritoneBridge.busy()) { issued = false; retries++; }
        checkStuck(now);
        if (retries > 3) {
            if (stopIndex < stops.size() - 1) {
                Chat.warning("[travel] Could not reach a point on the way (%d, %d); going on to the next one.", s.x(), s.z());
                nextStop();
            } else {
                Chat.error("[travel] Baritone cannot find a way to %d, %d from here. Stopping.", s.x(), s.z());
                chime(0.6f);
                toggle();
            }
        }
    }

    private void nextStop() {
        stopIndex++;
        digIndex = 0;
        issued = false;
        resetProgress();
    }

    /** Has the last stuck-after seconds seen real movement? If not, ask Baritone again. */
    private void checkStuck(long now) {
        double moved = Math.hypot(mc.player.getX() - progressX, mc.player.getZ() - progressZ);
        if (moved > 3) {
            // real progress: earlier give-ups no longer count against this goal
            progressAt = now; progressX = mc.player.getX(); progressZ = mc.player.getZ();
            retries = 0;
            return;
        }
        if (now - progressAt > stuckSeconds.get() * 1000L) {
            BaritoneBridge.stop();
            issued = false;
            retries++;
            progressAt = now;
        }
    }

    private void legsDone() {
        if (endsHere()) { arrive(); return; }
        if (inNether()) { setPhase(Phase.TO_EXIT, "to an exit portal"); return; }
        // anywhere else the trip has nowhere further to go
        arrive();
    }

    private void arrive() {
        BaritoneBridge.stop();
        String where = targetX.get() + (useY.get() ? ", " + targetY.get() : "") + ", " + targetZ.get();
        Chat.info("[travel] Arrived at %s (%s).", where, coordsIn.get() == Coords.Nether ? "Nether" : "Overworld");
        chime(1.4f);
        setPhase(Phase.DONE, "arrived");
        if (stopOnArrival.get()) toggle();
    }

    // ---------------------------------------------------------------- portals

    private void toPortal(long now) {
        if (!issued) {
            portal = nearestPortal(mc.player.getBlockPos(), portalSearch.get());
            if (portal != null) {
                BaritoneBridge.goBlock(portal.getX(), portal.getY(), portal.getZ());
                status = "to the portal at " + portal.getX() + ", " + portal.getZ();
            } else {
                // none loaded near you: Baritone heads for the nearest portal it remembers
                BaritoneBridge.goToNearest(Blocks.NETHER_PORTAL);
                status = "to the nearest remembered portal";
                Chat.info("[travel] No portal within %d blocks; heading for the nearest one Baritone remembers.", portalSearch.get());
            }
            issued = true;
            return;
        }
        if (standingInPortal()) { setPhase(Phase.ENTERING, "going through"); return; }
        if (!BaritoneBridge.busy() && now - phaseAt > 3000) {
            if (++retries > 3) {
                Chat.error("[travel] Could not reach a nether portal. Build one, or switch route to Overland.");
                chime(0.6f);
                toggle();
                return;
            }
            issued = false;
        }
    }

    private void entering(long now) {
        // a portal sends you after four seconds standing in it; the dimension change picks it up
        if (!standingInPortal() && now - phaseAt > 2000) { setPhase(inNether() ? Phase.TO_EXIT : Phase.TO_PORTAL, "back to the portal"); return; }
        if (now - phaseAt > 20_000) {
            Chat.warning("[travel] The portal has not taken you across; trying again.");
            setPhase(inNether() ? Phase.TO_EXIT : Phase.TO_PORTAL, "back to the portal");
        }
    }

    private void toExit(long now) {
        if (!issued) {
            portal = nearestPortal(mc.player.getBlockPos(), exitRadius.get());
            if (portal == null) {
                if (!toldExit) {
                    Chat.warning("[travel] No portal within %d blocks of Nether %d, %d. Build one here and walk in — the trip carries on from the other side.",
                        exitRadius.get(), mc.player.getBlockX(), mc.player.getBlockZ());
                    chime(0.8f);
                    toldExit = true;
                }
                setPhase(Phase.WAIT_EXIT, "waiting for a portal");
                return;
            }
            BaritoneBridge.goBlock(portal.getX(), portal.getY(), portal.getZ());
            status = "to the exit portal at " + portal.getX() + ", " + portal.getZ();
            issued = true;
            return;
        }
        if (standingInPortal()) { setPhase(Phase.ENTERING, "going through"); return; }
        if (!BaritoneBridge.busy() && now - phaseAt > 3000) {
            if (++retries > 3) {
                Chat.warning("[travel] Could not reach the exit portal at %d, %d. Walk into it yourself and the trip carries on.", portal.getX(), portal.getZ());
                setPhase(Phase.WAIT_EXIT, "waiting for you to go through");
                return;
            }
            issued = false;
        }
    }

    private boolean standingInPortal() {
        return mc.world.getBlockState(mc.player.getBlockPos()).isOf(Blocks.NETHER_PORTAL);
    }

    /**
     * The nearest portal block you can stand in — the bottom row of a portal — within a radius,
     * among loaded chunks. Each chunk section asks its palette first, so only sections that hold a
     * portal at all are read.
     */
    private BlockPos nearestPortal(BlockPos from, int radius) {
        BlockPos best = null;
        double bestD = (double) radius * radius;
        int cr = (radius >> 4) + 1;
        int pcx = from.getX() >> 4, pcz = from.getZ() >> 4;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int cx = pcx - cr; cx <= pcx + cr; cx++) for (int cz = pcz - cr; cz <= pcz + cr; cz++) {
            var ch = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!(ch instanceof WorldChunk wc)) continue;
            var secs = wc.getSectionArray();
            for (int si = 0; si < secs.length; si++) {
                var sec = secs[si];
                if (sec == null || sec.isEmpty() || !sec.hasAny(st -> st.isOf(Blocks.NETHER_PORTAL))) continue;
                int sy = wc.sectionIndexToCoord(si) << 4;
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (!sec.getBlockState(x, y, z).isOf(Blocks.NETHER_PORTAL)) continue;
                    int wx = (cx << 4) + x, wy = sy + y, wz = (cz << 4) + z;
                    if (mc.world.getBlockState(m.set(wx, wy - 1, wz)).isOf(Blocks.NETHER_PORTAL)) continue;   // not the bottom row
                    double dx = wx - from.getX(), dy = wy - from.getY(), dz = wz - from.getZ();
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) { bestD = d; best = new BlockPos(wx, wy, wz); }
                }
            }
        }
        return best;
    }

    private void chime(float pitch) {
        if (!sound.get() || mc.world == null || mc.player == null) return;
        mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING,
            net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, pitch);
    }

    // ---------------------------------------------------------------- used by the .travel command

    /** Set a new destination and start (or restart) the trip. */
    public void travelTo(int x, Integer y, int z, Coords in) {
        targetX.set(x);
        targetZ.set(z);
        if (y != null) { targetY.set(y); useY.set(true); }
        else useY.set(false);
        if (in != null) coordsIn.set(in);
        if (isActive()) toggle();
        toggle();
    }
}
