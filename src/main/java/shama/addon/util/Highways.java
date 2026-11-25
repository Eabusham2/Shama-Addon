package shama.addon.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The Nether highways and how to use them for a trip. Plain arithmetic, no game state.
 *
 * The highways run out from 0, 0 in eight directions: the four axes (+X, -X, +Z, -Z) and the four
 * diagonals, where X and Z grow together. A point on one of them is t steps along its direction.
 *
 * A trip uses either one highway — walk to the spot on it nearest you, run along it to the spot
 * nearest where you are going, step off — or two, meeting at 0, 0: ride one in to the middle and
 * another out, which is how you cross from one side of the map to the other.
 */
public final class Highways {
    private Highways() {}

    private static final int[][] AXES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** A run along one highway, direction dx, dz, from step fromT to step toT. */
    public record Leg(int dx, int dz, int fromT, int toT) {
        public boolean diagonal() { return dx != 0 && dz != 0; }
        public double length() { return Math.abs(toT - fromT) * Math.sqrt(dx * dx + dz * dz); }
        public String name() {
            String x = dx == 0 ? "" : (dx > 0 ? "+X" : "-X");
            String z = dz == 0 ? "" : (dz > 0 ? "+Z" : "-Z");
            return x + z;
        }
    }

    /**
     * A highway trip: the legs in order; its cost, where every block walked off a highway counts
     * offWeight times; the blocks actually walked; and the straight-line distance.
     */
    public record Route(List<Leg> legs, double cost, double blocks, double direct) {
        public Leg first() { return legs.get(0); }
        public Leg last() { return legs.get(legs.size() - 1); }
        public int entryX() { return first().fromT() * first().dx(); }
        public int entryZ() { return first().fromT() * first().dz(); }
        public double highwayLength() { double s = 0; for (Leg l : legs) s += l.length(); return s; }
        public String name() {
            StringBuilder b = new StringBuilder();
            for (Leg l : legs) { if (b.length() > 0) b.append(" then "); b.append(l.name()); }
            return b.toString();
        }
    }

    /** Steps along a highway to the spot on it nearest a point. Never behind 0, 0. */
    private static int nearestT(double px, double pz, int dx, int dz) {
        double t = (px * dx + pz * dz) / (dx * dx + dz * dz);
        return (int) Math.max(0, Math.round(t));
    }

    private static double dist(double ax, double az, double bx, double bz) {
        double x = ax - bx, z = az - bz;
        return Math.sqrt(x * x + z * z);
    }

    /** Cheaper wins; when two are within a block of each other, the one with more highway wins. */
    private static boolean better(Route a, Route b) {
        if (b == null) return true;
        if (Math.abs(a.cost() - b.cost()) < 1.0) return a.highwayLength() > b.highwayLength();
        return a.cost() < b.cost();
    }

    /**
     * The cheapest highway trip from one point to another, or null when there is no trip that
     * actually runs along a highway (start and end nearest the same spot on every one).
     *
     * offWeight is how much more a block off the highways costs than a block on one. Highways are
     * dug out, flat and travelled; the ground between them is not. At 1 the shortest trip wins even
     * if it barely touches a highway; at 2, the default, a block of open Nether is worth two of
     * highway, which keeps trips on the highways unless they really are the long way round.
     */
    public static Route best(double fromX, double fromZ, double toX, double toZ, boolean diagonals, double offWeight) {
        double direct = dist(fromX, fromZ, toX, toZ);
        List<int[]> dirs = new ArrayList<>(List.of(AXES));
        if (diagonals) dirs.addAll(List.of(DIAGONALS));
        Route best = null;

        // one highway
        for (int[] d : dirs) {
            int a = nearestT(fromX, fromZ, d[0], d[1]);
            int b = nearestT(toX, toZ, d[0], d[1]);
            if (a == b) continue;                       // nothing to ride
            Leg leg = new Leg(d[0], d[1], a, b);
            double off = dist(fromX, fromZ, a * d[0], a * d[1]) + dist(b * d[0], b * d[1], toX, toZ);
            Route r = new Route(List.of(leg), off * offWeight + leg.length(), off + leg.length(), direct);
            if (better(r, best)) best = r;
        }

        // in along one, out along another, changing at 0, 0
        for (int[] in : dirs) for (int[] out : dirs) {
            if (in == out) continue;
            int a = nearestT(fromX, fromZ, in[0], in[1]);
            int b = nearestT(toX, toZ, out[0], out[1]);
            if (a == 0 || b == 0) continue;             // that is a one-highway trip, already counted
            Leg l1 = new Leg(in[0], in[1], a, 0), l2 = new Leg(out[0], out[1], 0, b);
            double off = dist(fromX, fromZ, a * in[0], a * in[1]) + dist(b * out[0], b * out[1], toX, toZ);
            double along = l1.length() + l2.length();
            Route r = new Route(List.of(l1, l2), off * offWeight + along, off + along, direct);
            if (better(r, best)) best = r;
        }
        return best;
    }

    /**
     * Points to walk through along one leg, from just past its start to its end, about gap blocks
     * apart. Each is {x, z, t}. The last one is always the end of the leg.
     */
    public static List<int[]> waypoints(Leg leg, int gap) {
        List<int[]> out = new ArrayList<>();
        double stepLen = Math.sqrt(leg.dx() * leg.dx() + leg.dz() * leg.dz());
        int step = Math.max(1, (int) Math.round(gap / stepLen));
        int sign = leg.toT() >= leg.fromT() ? 1 : -1;
        for (int t = leg.fromT() + sign * step; sign > 0 ? t < leg.toT() : t > leg.toT(); t += sign * step)
            out.add(new int[]{t * leg.dx(), t * leg.dz(), t});
        out.add(new int[]{leg.toT() * leg.dx(), leg.toT() * leg.dz(), leg.toT()});
        return out;
    }

    /**
     * Boxes that, mined out in order, open a tunnel along a highway between two steps. Each is
     * {x1, y1, z1, x2, y2, z2}. On an axis it is one long box, width blocks across and height high,
     * centred on the highway. On a diagonal it is a chain of overlapping squares, since a straight
     * box would take a huge bite out of the ground either side; a diagonal tunnel is at least two
     * wide, because one-wide squares only touch at their corners and cannot be walked through.
     */
    public static List<int[]> tunnel(Leg leg, int fromT, int toT, int y, int width, int height) {
        List<int[]> out = new ArrayList<>();
        int lo = Math.min(fromT, toT), hi = Math.max(fromT, toT);
        int y2 = y + Math.max(1, height) - 1;
        if (!leg.diagonal()) {
            int left = (width - 1) / 2, right = width - 1 - left;
            if (leg.dx() != 0) {
                int x1 = lo * leg.dx(), x2 = hi * leg.dx();
                out.add(new int[]{Math.min(x1, x2), y, -left, Math.max(x1, x2), y2, right});
            } else {
                int z1 = lo * leg.dz(), z2 = hi * leg.dz();
                out.add(new int[]{-left, y, Math.min(z1, z2), right, y2, Math.max(z1, z2)});
            }
            return out;
        }
        int side = Math.max(2, width);
        int step = Math.max(1, side - 1);
        int left = (side - 1) / 2, right = side - 1 - left;
        // walked in the direction of travel, so the boxes are cleared in the order you reach them
        int sign = toT >= fromT ? 1 : -1;
        for (int t = fromT; ; t += sign * step) {
            int c = sign > 0 ? Math.min(t, toT) : Math.max(t, toT);
            int cx = c * leg.dx(), cz = c * leg.dz();
            out.add(new int[]{cx - left, y, cz - left, cx + right, y2, cz + right});
            if (c == toT) break;
        }
        return out;
    }
}
