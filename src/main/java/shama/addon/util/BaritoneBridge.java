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
