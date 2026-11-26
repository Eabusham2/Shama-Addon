package shama.addon.util;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.util.math.BlockPos;

/**
 * Everything that touches Baritone, kept in one class.
 *
 * Baritone is compiled against but not shipped, so it may not be installed. Nothing loads this class
 * unless the caller has already checked BaritoneUtils.IS_AVAILABLE, which means a missing Baritone
 * disables the features that use it instead of crashing the game. Every call is guarded as well, so a
 * Baritone update that moves something turns into "did nothing" rather than an exception.
 */
public final class BaritoneBridge {
    private BaritoneBridge() {}

    /** Baritone's own settings as they were before we changed them, so they can be put back. */
    private static Boolean savedBreak, savedPlace;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Path to within range of a position. False if Baritone could not be asked. */
    public static boolean walkNear(BlockPos pos, int range) {
        try {
            baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isPathing() {
        try { return baritone().getPathingBehavior().isPathing(); }
        catch (Throwable t) { return false; }
    }

    public static void stop() {
        try { baritone().getPathingBehavior().cancelEverything(); }
        catch (Throwable ignored) {}
    }

    /**
     * Keep Baritone's hands off the build while it walks. It never breaks anything, and it only
     * places its own blocks to pillar or bridge when that has been allowed.
     */
    public static void handsOff(boolean allowBridging) {
        try {
            var s = BaritoneAPI.getSettings();
            if (savedBreak == null) { savedBreak = s.allowBreak.value; savedPlace = s.allowPlace.value; }
            s.allowBreak.value = false;
            s.allowPlace.value = allowBridging;
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ travel

    /** Walk to a column, any height. */
    public static boolean goXZ(int x, int z) {
        try { baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(x, z)); return true; }
        catch (Throwable t) { return false; }
    }

    /** Walk until your feet are in this exact block. */
    public static boolean goBlock(int x, int y, int z) {
        try { baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(x, y, z)); return true; }
        catch (Throwable t) { return false; }
    }

    /** Walk to within range blocks of a spot. */
    public static boolean goNear(int x, int y, int z, int range) {
        try { baritone().getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalNear(new BlockPos(x, y, z), range)); return true; }
        catch (Throwable t) { return false; }
    }

    /** Head for the nearest block of this kind Baritone knows about, loaded or remembered. */
    public static boolean goToNearest(net.minecraft.block.Block block) {
        try { baritone().getGetToBlockProcess().getToBlock(block); return true; }
        catch (Throwable t) { return false; }
    }

    /** Mine out everything between two corners. */
    public static boolean clearArea(BlockPos a, BlockPos b) {
        try { baritone().getBuilderProcess().clearArea(a, b); return true; }
        catch (Throwable t) { return false; }
    }

    /** Whether any of the travel jobs handed out here is still being worked on. */
    public static boolean busy() {
        try {
            var b = baritone();
            return b.getCustomGoalProcess().isActive() || b.getGetToBlockProcess().isActive()
                || b.getBuilderProcess().isActive() || b.getPathingBehavior().isPathing();
        } catch (Throwable t) { return false; }
    }

    public static boolean clearing() {
        try { return baritone().getBuilderProcess().isActive(); }
        catch (Throwable t) { return false; }
    }

    /** Settings changed for a trip, as they were before, by name. */
    private static final java.util.Map<String, Object> travelSaved = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    private static <T> void setSaving(baritone.api.Settings.Setting<T> s, String key, T value) {
        if (!travelSaved.containsKey(key)) travelSaved.put(key, s.value);
        s.value = value;
    }

    /**
     * Set Baritone up for a trip: whether it may break blocks in its way, place blocks to pillar or
     * bridge, walk into the portal it was sent to, and turn smoothly. The old values are kept.
     */
    public static void travelSettings(boolean breakBlocks, boolean place, boolean smoothLook) {
        try {
            var s = BaritoneAPI.getSettings();
            setSaving(s.allowBreak, "allowBreak", breakBlocks);
            setSaving(s.allowPlace, "allowPlace", place);
            setSaving(s.enterPortal, "enterPortal", true);
            setSaving(s.smoothLook, "smoothLook", smoothLook);
        } catch (Throwable ignored) {}
    }

    /** Put back everything travelSettings changed. Safe to call when nothing was changed. */
    @SuppressWarnings("unchecked")
    public static void restoreTravel() {
        if (travelSaved.isEmpty()) return;
        try {
            var s = BaritoneAPI.getSettings();
            if (travelSaved.containsKey("allowBreak")) s.allowBreak.value = (Boolean) travelSaved.get("allowBreak");
            if (travelSaved.containsKey("allowPlace")) s.allowPlace.value = (Boolean) travelSaved.get("allowPlace");
            if (travelSaved.containsKey("enterPortal")) s.enterPortal.value = (Boolean) travelSaved.get("enterPortal");
            if (travelSaved.containsKey("smoothLook")) s.smoothLook.value = (Boolean) travelSaved.get("smoothLook");
        } catch (Throwable ignored) {}
        travelSaved.clear();
    }

    /** Put Baritone's settings back the way they were. Safe to call when nothing was changed. */
    public static void restore() {
        if (savedBreak == null) return;
        try {
            var s = BaritoneAPI.getSettings();
            s.allowBreak.value = savedBreak;
            s.allowPlace.value = savedPlace;
        } catch (Throwable ignored) {}
        savedBreak = null;
        savedPlace = null;
    }
}
