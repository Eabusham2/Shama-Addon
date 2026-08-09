package shama.addon.util;

import java.util.Random;

/**
 * Small helpers to make automated actions look less robotic to anti-cheats:
 * random misses, timing jitter, and rotation noise. Pure math — no Minecraft API,
 * so it's safe to call from anywhere.
 */
public final class Humanize {
    private static final Random RNG = new Random();

    private Humanize() {}

    /** True if this action should be skipped this time to simulate a human miss. percent is 0-100. */
    public static boolean shouldMiss(int percent) {
        return percent > 0 && RNG.nextInt(100) < percent;
    }

    /** Vary a delay by +/- jitterPercent so it isn't the exact same number every time. Never below 0. */
    public static int jitter(int baseTicks, int jitterPercent) {
        if (jitterPercent <= 0) return baseTicks;
        double factor = 1.0 + (RNG.nextDouble() * 2 - 1) * (jitterPercent / 100.0);
        return Math.max(0, (int) Math.round(baseTicks * factor));
    }

    /** A small random rotation offset (degrees), gaussian so most values are tiny. */
    public static float rotationNoise(float maxDegrees) {
        if (maxDegrees <= 0) return 0f;
        return (float) Math.max(-maxDegrees, Math.min(maxDegrees, RNG.nextGaussian() * (maxDegrees / 2f)));
    }

    /** Occasionally return true, e.g. to insert a tiny extra pause. percent is 0-100. */
    public static boolean chance(int percent) {
        return percent > 0 && RNG.nextInt(100) < percent;
    }
}
