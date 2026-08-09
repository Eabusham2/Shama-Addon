package shama.addon.oresim;

/**
 * Which Minecraft version's ore generation to simulate.
 *
 *  CURRENT  - reads the live game registry (the running 1.21.x). Registry-
 *             accurate, the same approach Nora Tweaks / Meteor Rejects use.
 *             This is the one to trust.
 *  V1_20 .. - older releases, driven by hardcoded per-era ore configs fed into
 *  V1_16      the real vein algorithm. 1.18+ share the "new" cave/ore
 *             distribution; 1.16/1.17 use the "old" distribution. These restore
 *             the cross-version pick but are best-effort - validate in
 *             singleplayer before trusting on a server.
 */
public enum OreVersion {
    CURRENT("Current (1.21.x)", Era.NEW),
    V1_20("1.20", Era.NEW),
    V1_19_4("1.19.4", Era.NEW),
    V1_19("1.19", Era.NEW),
    V1_18("1.18", Era.NEW),
    V1_17("1.17", Era.OLD),
    V1_16("1.16", Era.OLD);

    public enum Era { NEW, OLD }

    public final String label;
    public final Era era;

    OreVersion(String label, Era era) {
        this.label = label;
        this.era = era;
    }

    @Override
    public String toString() {
        return label;
    }
}
