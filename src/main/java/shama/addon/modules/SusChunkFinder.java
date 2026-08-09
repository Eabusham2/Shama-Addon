package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sus Chunk Finder++ — flags chunks that look player-built by counting tell-tale
 * growables/blocks a base tends to stockpile, then boxes the top of the chunk (like
 * Krypton's sus chunk finder). Each chunk is scanned once as it loads; the flag is
 * re-evaluated live against your toggles + sensitivity, so tweaking them updates the
 * map without a rescan.
 *
 * A chunk is flagged when ANY enabled category has at least `sensitivity` blocks in it.
 */
public class SusChunkFinder extends Module {
    public enum TypePreset { All, Growables, Containers, Custom }

    private final SettingGroup sgTypes = settings.createGroup("Types");
    private final Setting<Double> chunkDelay = sgTypes.add(new DoubleSetting.Builder()
        .name("chunk-delay")
        .description("Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.")
        .defaultValue(0.0).min(0).max(10).sliderRange(0, 3).decimalPlaces(1).build());

    private final Setting<Integer> zoneSize = sgTypes.add(new IntSetting.Builder()
        .name("scan-zone-size")
        .description("Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.")
        .defaultValue(1).min(1).max(6).sliderRange(1, 6).build());

    /** Collapse a chunk coord onto its zone, so neighbouring chunks count as one place. */
    private long zoneKey(int cx, int cz) {
        int n = Math.max(1, zoneSize.get());
        return ChunkPos.toLong(Math.floorDiv(cx, n) * n, Math.floorDiv(cz, n) * n);
    }

    private final Setting<Boolean> customThresholds = sgTypes.add(new BoolSetting.Builder()
        .name("custom-thresholds")
        .description("Set each threshold by hand instead of letting the sensitivity slider work them out. Leave this off unless you want to fine-tune one particular detection.")
        .defaultValue(false).build());

    /**
     * How many hits are needed before something is flagged. Normally that's just the sensitivity
     * slider — one number, so sensitivity 1 flags on a single detection and sensitivity 10 needs
     * ten. Turning on custom thresholds lets each detection use its own number instead.
     */
    /**
     * A threshold that isn't a simple hit count — a column height, a weighted score, a tunnel
     * length. It follows sensitivity in the same direction: a low number is looser, a high number
     * is tighter. At the default sensitivity it sits on its own tuned value.
     */
    private int scaled(Setting<Integer> setting, int base) {
        if (customThresholds.get()) return setting.get();
        return Math.max(1, (int) Math.round(base * (sensitivity.get() / 6.0)));
    }

    private int hitsNeeded(Setting<Integer> setting) {
        return customThresholds.get() ? setting.get() : sensitivity.get();
    }

    private final Setting<TypePreset> preset = sgTypes.add(new EnumSetting.Builder<TypePreset>()
        .name("types-preset")
        .description("Quick way to pick what counts as suspicious. All = every type below. Growables = only plants that overgrow when a chunk stays loaded. Containers = storage and player-placed blocks. Custom = use the individual tickboxes.")
        .defaultValue(TypePreset.Custom)
        .build());
    private final SettingGroup sgAmethyst = settings.createGroup("Amethyst");
    private final SettingGroup sgTrace = settings.createGroup("Player Traces");
    private final Setting<Boolean> playerWasHere = sgTrace.add(new BoolSetting.Builder()
        .name("active-chunk")
        .description("Flag chunks somebody else is holding open. A chunk read from disk takes real time to hand over; one already in memory for a player comes back almost instantly, so a run of instant arrivals means someone is keeping it live. Available on its own as active-chunk-detector++, which reads the chunk's inhabited clock as well and gives you far more detail.")
        .defaultValue(false).build());
    private final Setting<Integer> traceInstantMs = sgTrace.add(new IntSetting.Builder()
        .name("instant-threshold")
        .description("A chunk arriving within this many milliseconds of the one before counts as instant.")
        .defaultValue(3).min(1).max(50).sliderRange(1, 20).visible(playerWasHere::get).build());
    private final Setting<Integer> traceRun = sgTrace.add(new IntSetting.Builder()
        .name("instant-run")
        .description("How many instant arrivals in a row before the chunk is flagged.")
        .defaultValue(20).min(4).max(200).sliderRange(8, 60).visible(playerWasHere::get).build());
    private final Setting<SettingColor> traceColor = sgTrace.add(new ColorSetting.Builder()
        .name("trace-color").description("Colour used for chunks flagged this way.")
        .defaultValue(new SettingColor(90, 0, 160, 90)).visible(playerWasHere::get).build());

    private final java.util.Set<Long> traceChunks = ConcurrentHashMap.newKeySet();
    private long prevChunkAt;
    private int traceStreak;

    private final SettingGroup sgIndirect = settings.createGroup("Indirect (packet-based)");
    private final SettingGroup sgRender = settings.createGroup("Render");
    // Category order must match the counts[] indices below.
    // types that occur naturally in the world and thus get the exception tolerance
    private static final java.util.Set<Integer> EXCEPTIONAL = java.util.Set.of(0, 1, 2, 3, 4, 6, 8, 9, 10, 13, 14, 15, 16, 17);
    // count is dropped for these — a precise file method governs instead (no natural-growth false positives)
    private static final java.util.Set<Integer> COUNT_SUPERSEDED = java.util.Set.of(1, 3);
    private static final String[] NAMES = {"kelp", "vines", "cocoa", "cave vines", "amethyst", "rotated deepslate", "bamboo", "bee nest", "dripstone", "sculk", "sugar cane", "containers", "obsidian", "moss", "azalea", "glow lichen", "spore blossom", "big dripleaf", "glow ink", "egg", "turtle scute", "armadillo scute", "sweet berries", "cactus"};
    private final Setting<Integer> scanRate = sgTypes.add(new IntSetting.Builder()
        .name("scan-rate")
        .description("How many newly loaded chunks to analyse each tick. Lower this if the game stutters while flying or after an RTP; raise it to find things sooner.")
        .defaultValue(4).min(1).max(64).sliderRange(1, 16).build());

    private final Setting<Boolean> kelp = sgTypes.add(new BoolSetting.Builder().name("kelp").description("Flag chunks with lots of kelp — usually an underwater kelp farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> fullKelpOnly = sgTypes.add(new BoolSetting.Builder()
        .name("kelp-at-full-height")
        .description("Flag kelp only when its columns have grown all the way to the ocean surface. Wild ocean kelp sits at random heights; a chunk kept loaded by a nearby base grows it all to max over time. The exception-amount is how many columns are allowed to still be short.")
        .defaultValue(true).visible(kelp::get).build());
    private final Setting<Integer> kelpSurfaceY = sgTypes.add(new IntSetting.Builder()
        .name("kelp-surface-y")
        .description("Y a kelp column's top must reach to count as grown-to-max (ocean surface is 62).")
        .defaultValue(62).min(0).max(120).sliderRange(50, 70).visible(() -> kelp.get() && fullKelpOnly.get()).build());
    private final Setting<Boolean> inhabitedTime = sgIndirect.add(new BoolSetting.Builder()
        .name("long-loaded-chunks")
        .description("Flag chunks the server has kept loaded/inhabited for a long time (a base players stay near). Uses the chunk's inhabited-time counter.")
        .defaultValue(false).build());
    private final Setting<Boolean> scannerScore = sgIndirect.add(new BoolSetting.Builder()
        .name("weighted-scoring")
        .description("Their ChunkScanner weighted scoring, added on top: vine-length + amethyst-cluster-size + full beehives combined into one score.")
        .defaultValue(false).build());
    private final Setting<Integer> scannerThreshold = sgIndirect.add(new IntSetting.Builder()
        .name("weighted-min-score").description("Combined scanner score to flag a chunk.").defaultValue(6).min(1).max(50).sliderRange(1,20).visible(() -> customThresholds.get() && scannerScore.get()).build());
    private final Setting<Integer> inhabitedMinutes = sgIndirect.add(new IntSetting.Builder()
        .name("inhabited-minutes")
        .description("Minimum inhabited minutes to flag a chunk (180 = three hours).")
        .defaultValue(180).min(1).max(1440).sliderRange(10, 360).visible(inhabitedTime::get).build());
    private final Setting<Integer> growthLength = sgIndirect.add(new IntSetting.Builder()
        .name("min-column-height")
        .description("A vine/cave-vine/bamboo/sugar-cane/kelp column grown at least this tall flags the chunk. Runs alongside the per-type counts.")
        .defaultValue(25).min(2).max(320).sliderRange(6, 100).visible(customThresholds::get).build());
    private final Setting<Integer> extremeLength = sgIndirect.add(new IntSetting.Builder()
        .name("extreme-column-height")
        .description("A column this long is 'extreme' overgrowth — the chunk is drawn in the extreme colour instead.")
        .defaultValue(100).min(10).max(400).sliderRange(30, 200).visible(customThresholds::get).build());
    private final Setting<Boolean> suspicionScore = sgIndirect.add(new BoolSetting.Builder()
        .name("overgrowth-score")
        .description("Also flag on a weighted overgrowth score (vines x0.5 + dripstone x0.75), not just column length or per-type counts.")
        .defaultValue(true).build());
    private final Setting<Boolean> baseLock = sgIndirect.add(new BoolSetting.Builder()
        .name("base-lock")
        .description("A chunk flagged this many times gets locked as a confirmed base and stays highlighted (in the locked colour) even if the growth stops matching.")
        .defaultValue(true).build());
    private final Setting<Integer> lockAfter = sgIndirect.add(new IntSetting.Builder()
        .name("lock-after").description("Times a chunk must be flagged before it locks as a base.")
        .defaultValue(3).min(2).max(20).visible(baseLock::get).build());
    private final Setting<Boolean> packetAmethyst = sgIndirect.add(new BoolSetting.Builder()
        .name("amethyst-from-packets").description("Also catch amethyst from live block-update packets, which reveals it even in chunks you never fully scan.").defaultValue(false).build());
    private final Setting<Integer> packetAmethystThreshold = sgIndirect.add(new IntSetting.Builder()
        .name("amethyst-packet-threshold").description("How many amethyst block-updates in a chunk before it flags.").defaultValue(4).min(1).max(50).sliderRange(1, 20).visible(() -> customThresholds.get() && packetAmethyst.get()).build());

    private final Setting<Integer> exceptionAmount = sgTypes.add(new IntSetting.Builder()
        .name("natural-tolerance")
        .description("Extra tolerance (added on top of sensitivity) for naturally-occurring types (kelp, bamboo, vines, cave growth, amethyst, etc.) so wild growth isn't flagged. Global like sensitivity, applied per-type at flag time: an excepted type won't trip a chunk on its own, but any OTHER type over its threshold still will (e.g. tolerable bamboo + a real kelp farm still flags on kelp).")
        .defaultValue(2).min(0).max(32).sliderRange(0, 16).build());
    private final Setting<Boolean> vines = sgTypes.add(new BoolSetting.Builder().name("vines").description("Flag chunks with long vine growth — often a hidden vine/XP farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> cocoa = sgTypes.add(new BoolSetting.Builder().name("cocoa").description("Flag chunks with many cocoa pods — a cocoa bean farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> caveVines = sgTypes.add(new BoolSetting.Builder().name("cave-vines").description("Flag chunks with lots of glow-berry cave vines — a cave-vine farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> amethyst = sgTypes.add(new BoolSetting.Builder().name("amethyst").description("Flag chunks with lots of amethyst — someone farming a geode.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());

    private final Setting<Integer> amethystMinClusters = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-min-clusters").description("How many amethyst blocks a chunk needs to flag on amethyst (used instead of the global sensitivity for this one type).")
        .defaultValue(8).min(1).max(64).sliderRange(2, 24).visible(() -> customThresholds.get() && amethyst.get()).build());
    private final Setting<Boolean> amethystIncludeBudding = sgAmethyst.add(new BoolSetting.Builder()
        .name("amethyst-include-budding").description("Count budding amethyst (the block that grows the crystals).").defaultValue(true).visible(amethyst::get).build());
    private final Setting<Boolean> amethystIncludeBlock = sgAmethyst.add(new BoolSetting.Builder()
        .name("amethyst-include-block").description("Count plain amethyst blocks too (noisier — geodes are full of them).").defaultValue(false).visible(amethyst::get).build());
    private final Setting<Boolean> amethystFullyGrownOnly = sgAmethyst.add(new BoolSetting.Builder()
        .name("amethyst-grown-only").description("Only count fully-grown clusters, ignoring the small/medium/large buds.").defaultValue(false).visible(amethyst::get).build());
    private final Setting<Boolean> amethystRequireDeep = sgAmethyst.add(new BoolSetting.Builder()
        .name("amethyst-require-deep").description("Only count amethyst below a Y line (surface geodes are natural; deep ones are usually farmed).").defaultValue(false).visible(amethyst::get).build());
    private final Setting<Integer> amethystMinDepth = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-max-y").description("Only count amethyst at or below this Y.").defaultValue(0).min(-64).max(120).sliderRange(-64, 60).visible(() -> amethyst.get() && amethystRequireDeep.get()).build());
    private final Setting<Boolean> rotatedDeepslate = sgTypes.add(new BoolSetting.Builder().name("rotated-deepslate").description("Deepslate placed on a horizontal axis — natural deepslate is always vertical, so this means someone placed it.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> bamboo = sgTypes.add(new BoolSetting.Builder().name("bamboo").description("Flag chunks with tall/dense bamboo — a bamboo farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> beeNest = sgTypes.add(new BoolSetting.Builder().name("bee-nest").description("Flag chunks with several bee nests/hives grouped together — a bee/honey farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> dripstone = sgTypes.add(new BoolSetting.Builder().name("dripstone").description("Pointed dripstone / dripstone blocks — dripstone farms.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> sculk = sgTypes.add(new BoolSetting.Builder().name("sculk").description("Sculk sensors/catalysts/shriekers — deep-dark harvesting or XP setups.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> sugarCane = sgTypes.add(new BoolSetting.Builder().name("sugar-cane").description("Flag chunks with lots of sugar cane — a sugar-cane farm.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> containers = sgTypes.add(new BoolSetting.Builder().name("containers").description("Chests/barrels/shulkers/furnaces packed into a chunk (storage density).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> obsidian = sgTypes.add(new BoolSetting.Builder().name("obsidian").description("Obsidian (bases/anchors/portals).").defaultValue(false).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> moss = sgTypes.add(new BoolSetting.Builder().name("moss").description("Moss blocks/carpet (lush-cave harvest / bonemeal farms).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> azalea = sgTypes.add(new BoolSetting.Builder().name("azalea").description("Azalea / flowering azalea (lush caves above ground = base garden).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> glowLichen = sgTypes.add(new BoolSetting.Builder().name("glow-lichen").description("Flag chunks with lots of glow lichen — harvested lush caves / a base garden.").defaultValue(false).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> sporeBlossom = sgTypes.add(new BoolSetting.Builder().name("spore-blossom").description("Flag chunks with spore blossoms — a decorated lush-cave base above ground.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> bigDripleaf = sgTypes.add(new BoolSetting.Builder().name("big-dripleaf").description("Flag chunks with big dripleaf plants — a lush-cave farm/base.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> glowInk = sgTypes.add(new BoolSetting.Builder().name("glow-ink").description("Dropped glow ink sacs piling up (glow squid farm).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> egg = sgTypes.add(new BoolSetting.Builder().name("egg").description("Dropped eggs (chicken farm) - uses half the sensitivity (stronger signal).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> turtleScute = sgTypes.add(new BoolSetting.Builder().name("turtle-scute").description("Dropped turtle scutes (turtle farm).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> armadilloScute = sgTypes.add(new BoolSetting.Builder().name("armadillo-scute").description("Dropped armadillo scutes (armadillo farm).").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> cactus = sgTypes.add(new BoolSetting.Builder()
        .name("cactus").description("Cactus grown to full height — like sugar cane, it only stacks up when the chunk stays loaded, so tall cactus means someone is active nearby.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> sweetBerries = sgTypes.add(new BoolSetting.Builder()
        .name("sweet-berries").description("Flag chunks where sweet-berry bushes have overgrown from long player presence.").defaultValue(true).visible(() -> preset.get() == TypePreset.Custom).build());
    private final Setting<Boolean> indirectDetection = sgIndirect.add(new BoolSetting.Builder()
        .name("score-unseen-chunks").description("Also score chunks from entity-spawn / sound / chunk-load packets, so a chunk can be flagged even when you can't see its blocks. Runs alongside the block counting.").defaultValue(false).build());
    private final Setting<Integer> entityScore = sgIndirect.add(new IntSetting.Builder()
        .name("entity-score").description("Points added per entity-spawn packet.").defaultValue(2).min(1).max(10).visible(indirectDetection::get).build());
    private final Setting<Integer> soundScore = sgIndirect.add(new IntSetting.Builder()
        .name("sound-score").description("Points added per sound packet.").defaultValue(1).min(1).max(10).visible(indirectDetection::get).build());
    private final Setting<Integer> scoreDecayTicks = sgIndirect.add(new IntSetting.Builder()
        .name("score-decay-ticks").description("How often scores drop by 1 (0 = never).").defaultValue(200).min(0).max(1000).visible(indirectDetection::get).build());
    private final Setting<Boolean> indirectOnlyUnseen = sgIndirect.add(new BoolSetting.Builder()
        .name("only-unseen-chunks").description("Only score chunks that aren't loaded on your client. The point of indirect detection is finding activity you can't see, so chunks already rendering around you are skipped.").defaultValue(true).visible(indirectDetection::get).build());
    private final Setting<Boolean> indirectBeam = sgIndirect.add(new BoolSetting.Builder()
        .name("indirect-beam").description("Draw a vertical beam on chunks flagged this way, so you can spot them from a distance.").defaultValue(true).visible(indirectDetection::get).build());
    private final Setting<Boolean> indirectFloor = sgIndirect.add(new BoolSetting.Builder()
        .name("indirect-floor").description("Shade the whole chunk footprint on chunks flagged this way.").defaultValue(false).visible(indirectDetection::get).build());
    private final Setting<SettingColor> indirectBeamColor = sgIndirect.add(new ColorSetting.Builder()
        .name("indirect-beam-color").description("Colour of the beam.").defaultValue(new SettingColor(255, 130, 0, 180)).visible(indirectBeam::get).build());
    private final Setting<SettingColor> indirectFloorColor = sgIndirect.add(new ColorSetting.Builder()
        .name("indirect-floor-color").description("Colour of the chunk shading.").defaultValue(new SettingColor(255, 130, 0, 45)).visible(indirectFloor::get).build());

    private final Setting<Integer> flagScore = sgIndirect.add(new IntSetting.Builder()
        .name("flag-score").description("Score a chunk needs before it's flagged this way.").defaultValue(10).min(1).max(100).visible(() -> customThresholds.get() && indirectDetection.get()).build());

    private final java.util.Set<Long> flaggedNow = ConcurrentHashMap.newKeySet();   // chunks already counted toward the lock
    private final java.util.Map<Long, Integer> indirectScore = new ConcurrentHashMap<>();
    private int decayTick;
    private long lastChunkData;

    private final Setting<Integer> sensitivity = sgTypes.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.")
        .defaultValue(6).range(1, 20).sliderRange(1, 20)
        .build());

    private final Setting<Integer> topY = sgRender.add(new IntSetting.Builder()
        .name("box-y").description("Height to draw the chunk box at. Defaults to sea level so boxes sit where you can actually see them.")
        .defaultValue(63).min(-64).max(320).sliderRange(0, 320).build());

    private final Setting<Boolean> coveredHoles = sgTypes.add(new BoolSetting.Builder()
        .name("covered-holes")
        .description("Count plugged-up shafts as one of the signals for a suspicious chunk. This is a lighter, chunk-level version of hole-finder++ — that module marks each hole exactly, this one just adds them to the evidence here. Both can run at once.")
        .defaultValue(false).build());
    private final Setting<Boolean> geodeScan = sgAmethyst.add(new BoolSetting.Builder()
        .name("whole-geode-scan")
        .description("Group connected amethyst into whole geodes instead of only counting blocks. Uses the same scanner as geode-finder++, so a chunk flags on the size of the geode in it rather than on how much amethyst happens to be scattered about.")
        .defaultValue(false).build());
    private final Setting<Integer> geodeThreshold = sgAmethyst.add(new IntSetting.Builder()
        .name("geode-threshold")
        .description("How many connected amethyst blocks make a geode worth flagging the chunk for.")
        .defaultValue(12).min(1).max(100).sliderRange(4, 40).visible(geodeScan::get).build());

    private final Setting<Integer> chunkRange = sgTypes.add(new IntSetting.Builder()
        .name("range")
        .description("How far out to look for and show flagged chunks, measured in chunks around you. 0 = only the chunk you stand in, 8 = a 17x17 chunk area. Bigger = see more but heavier.")
        .defaultValue(8).min(0).max(32).sliderRange(0, 16).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("The colour of the filled/shaded part of the box.").defaultValue(new SettingColor(255, 200, 0, 40)).build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw a line from you to each flagged chunk.").defaultValue(false).build());
    private final Setting<Boolean> chatAlert = sgTypes.add(new BoolSetting.Builder()
        .name("chat").description("Print a chat message when a new chunk is flagged.").defaultValue(false).build());
    private final Setting<SettingColor> amethystColor = sgRender.add(new ColorSetting.Builder()
        .name("amethyst-color").description("Colour used for chunks flagged because of amethyst, so a geode being farmed stands out from the other finds.")
        .defaultValue(new SettingColor(190, 120, 255, 90)).build());
    private final Setting<SettingColor> extremeColor = sgRender.add(new ColorSetting.Builder()
        .name("extreme-color").description("Colour for chunks with extreme overgrowth.").defaultValue(new SettingColor(255, 40, 40, 200)).visible(customThresholds::get).build());
    private final Setting<SettingColor> lockedColor = sgRender.add(new ColorSetting.Builder()
        .name("locked-color").description("Colour for chunks locked as confirmed bases.").defaultValue(new SettingColor(255, 0, 255, 200)).visible(baseLock::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("The colour of the box outline.").defaultValue(new SettingColor(255, 200, 0, 220)).build());

    // chunkLong -> per-category counts.
    private final Map<Long, int[]> counts = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> kelpFarms = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> longGrowth = new ConcurrentHashMap<>();
    private final java.util.Set<Long> highInhabited = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> highScanner = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> extremeChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> susScored = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, Integer> sourceHistory = new ConcurrentHashMap<>();
    private final java.util.Set<Long> baseChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, Integer> packetAmethystCounts = new ConcurrentHashMap<>();
    private final java.util.Set<Long> packetAmethystChunks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> announced = ConcurrentHashMap.newKeySet(); // covered-hole fill blocks to draw blue
    private final Set<Long> scanned = ConcurrentHashMap.newKeySet();
    private java.util.concurrent.ExecutorService scanner;
    private static final int MAX_PENDING = 512;
    private record Queued(WorldChunk chunk, long arrived) {}
    private final java.util.ArrayDeque<Queued> pending = new java.util.ArrayDeque<>();

    public SusChunkFinder() {
        super(shama.addon.ShamaAddon.HUNT, "sus-chunk-finder++", "Finds chunks where plants and blocks have overgrown far past natural amounts — a sign someone has been living or AFKing nearby keeping the area loaded. Not a farm finder.");
    }

    @Override
    public void onActivate() {
        counts.clear(); kelpFarms.clear(); longGrowth.clear(); highInhabited.clear(); highScanner.clear(); extremeChunks.clear(); susScored.clear(); sourceHistory.clear(); baseChunks.clear(); packetAmethystCounts.clear(); packetAmethystChunks.clear(); announced.clear(); scanned.clear();
        scanner = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "shama-suschunk"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
    }

    @Override
    public void onDeactivate() {
        if (scanner != null) { scanner.shutdownNow(); scanner = null; }
        synchronized (pending) { pending.clear(); }
        counts.clear(); kelpFarms.clear(); longGrowth.clear(); highInhabited.clear(); highScanner.clear(); extremeChunks.clear(); susScored.clear(); sourceHistory.clear(); baseChunks.clear(); packetAmethystCounts.clear(); packetAmethystChunks.clear(); announced.clear(); scanned.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (playerWasHere.get() && event.chunk() instanceof WorldChunk wc) {
            long now = System.currentTimeMillis();
            if (prevChunkAt > 0 && now - prevChunkAt <= traceInstantMs.get()) {
                if (++traceStreak >= traceRun.get()) traceChunks.add(wc.getPos().toLong());
            } else traceStreak = 0;
            prevChunkAt = now;
        }
        if (!(event.chunk() instanceof WorldChunk chunk)) return;
        long key = zoneKey(chunk.getPos().x, chunk.getPos().z);
        if (!scanned.add(key)) return;                 // once per chunk
        // Queue it rather than submitting immediately. Flying fast or RTPing delivers hundreds of
        // chunks at once; dumping them all on the pool at once starves the CPU and the whole game
        // hitches even though the work is off-thread.
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING) pending.pollFirst();   // drop the oldest, keep up with movement
            pending.addLast(new Queued(chunk, System.currentTimeMillis()));
        }
    }

    /** Feed a few queued chunks to the scan pool each tick so load spikes are spread out. */
    @EventHandler
    private void onScanPump(TickEvent.Post event) {
        java.util.concurrent.ExecutorService s = scanner;
        if (s == null || s.isShutdown()) return;
        int budget = scanRate.get();
        while (budget-- > 0) {
            WorldChunk c;
            synchronized (pending) {
                Queued head = pending.peekFirst();
                if (head == null) break;
                // the queue is oldest-first, so if the head is not ready none behind it are either
                if (System.currentTimeMillis() - head.arrived() < (long) (chunkDelay.get() * 1000)) break;
                c = pending.pollFirst().chunk();
            }
            if (c == null) break;
            s.submit(() -> { try { analyze(c); } catch (Throwable ignored) {} });
        }
    }

    /** Full per-chunk block analysis — runs on a background thread. */
    /**
     * Plugged shafts, counted as one more signal for this chunk. hole-finder++ marks each hole
     * exactly; this is the cheaper chunk-level version, and both can run together.
     */
    private int countCoveredHoles(WorldChunk chunk) {
        int found = 0;
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY() + 1, top = chunk.getTopYInclusive() - 1;
        // depth follows sensitivity, same rule hole-finder++ uses
        double t = (Math.max(1, Math.min(20, sensitivity.get())) - 1) / 19.0;
        int depth = (int) Math.round(5 + t * 15);
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            for (int y = bottom; y <= top; y++) {
                String p = shama.addon.util.BlockPaths.of(chunk.getBlockState(m.set(bx + x, y, bz + z)).getBlock());
                boolean fill = p.equals("cobblestone") || p.equals("mossy_cobblestone") || p.equals("cobbled_deepslate")
                    || p.equals("obsidian") || p.equals("crying_obsidian")
                    || p.equals("dirt") || p.equals("coarse_dirt") || p.equals("rooted_dirt") || p.equals("grass_block");
                if (!fill) continue;
                boolean deepEnough = true;
                for (int d = 1; d <= depth; d++) {
                    if (y - d < bottom || !chunk.getBlockState(m.set(bx + x, y - d, bz + z)).isAir()) { deepEnough = false; break; }
                }
                if (deepEnough) found++;
            }
        return found;
    }

    private void analyze(WorldChunk chunk) {
        int[] c = new int[NAMES.length];
        if (coveredHoles.get()) c[22] = countCoveredHoles(chunk);
        // whole-geode grouping, shared with geode-finder++
        if (geodeScan.get() && mc.world != null) {
            try {
                var geodes = shama.addon.util.AmethystScan.findGeodes(mc.world, chunk, geodeThreshold.get(),
                    shama.addon.util.AmethystScan.MIN_Y, shama.addon.util.AmethystScan.MAX_Y);
                for (var g : geodes) c[4] = Math.max(c[4], g.size());
            } catch (Throwable ignored) {}
        }
        // The section index is tracked with a counter. It used to be recomputed per BLOCK with
        // Arrays.asList(getSectionArray()).indexOf(section) — one list allocation and a linear
        // scan for every one of the ~98,000 blocks in a chunk, which is what made loading chunks
        // while moving stutter so badly.
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) continue;
            int sectionBaseY = bottomY + (si << 4);
            for (int y = 0; y < 16; y++) {
                int worldY = sectionBaseY + y;
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState bs = section.getBlockState(x, y, z);
                        if (bs.isAir()) continue;
                        count(bs, c, worldY);
                    }
            }
        }
        counts.put(zoneKey(chunk.getPos().x, chunk.getPos().z), c);
        kelpFarms.put(zoneKey(chunk.getPos().x, chunk.getPos().z), isKelpFarm(chunk));
        longGrowth.put(zoneKey(chunk.getPos().x, chunk.getPos().z), hasLongGrowth(chunk));
        if (chunk.getInhabitedTime() >= (long) inhabitedMinutes.get() * 60L * 20L) highInhabited.add(zoneKey(chunk.getPos().x, chunk.getPos().z));
        else highInhabited.remove(zoneKey(chunk.getPos().x, chunk.getPos().z));
        if (scannerScore.get()) {
            int sc = computeScannerScore(chunk);
            if (sc >= scaled(scannerThreshold, 6)) highScanner.add(zoneKey(chunk.getPos().x, chunk.getPos().z)); else highScanner.remove(zoneKey(chunk.getPos().x, chunk.getPos().z));
        }
        long ck = zoneKey(chunk.getPos().x, chunk.getPos().z);
        // growth-finder weighted overgrowth score, from counts we already have (vines x0.5 + dripstone x0.75)
        if (suspicionScore.get()) {
            int sus = (int) Math.round((c[1] + c[3]) * 0.5 + c[8] * 0.75);
            if (sus >= (sensitivity.get() * 2)) susScored.add(ck); else susScored.remove(ck);
        } else susScored.remove(ck);
        // growth-finder extreme: a column at or past extreme-length
        if (hasGrowthOfLength(chunk, extremeLength.get())) extremeChunks.add(ck); else extremeChunks.remove(ck);
        if (counts.size() > 6000) { counts.clear(); kelpFarms.clear(); longGrowth.clear(); highInhabited.clear(); highScanner.clear(); extremeChunks.clear(); susScored.clear(); sourceHistory.clear(); baseChunks.clear(); packetAmethystCounts.clear(); packetAmethystChunks.clear(); announced.clear(); scanned.clear(); } // safety cap on very long sessions
    }

    /** Their full-kelp-chunk detection: flag only if kelpPlantsFound - fullKelpPlants <= 1 (almost every column capped/full = farm). */
    private boolean isKelpFarm(WorldChunk chunk) {
        int columns = 0, maxed = 0;
        java.util.Set<Long> processed = new java.util.HashSet<>();
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY(), top = chunk.getTopYInclusive();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = bottom; y <= top; y++) {
                if (!isKelp(chunk, bx + x, y, bz + z)) continue;
                if (processed.contains(m.set(bx + x, y, bz + z).asLong())) continue;
                // walk down to the base of the column
                int baseY = y;
                while (baseY - 1 >= bottom && isKelp(chunk, bx + x, baseY - 1, bz + z)) baseY--;
                if (processed.contains(m.set(bx + x, baseY, bz + z).asLong())) continue;
                // walk up to the top of the column, marking processed
                int topY = baseY, cy = baseY;
                while (cy <= top && isKelp(chunk, bx + x, cy, bz + z)) {
                    processed.add(m.set(bx + x, cy, bz + z).asLong());
                    topY = cy;
                    cy++;
                }
                columns++;
                // "maxed" = the column grew all the way to the ocean surface (their KelpEsp: tops at Y 62).
                // Wild ocean kelp is random heights; a chunk kept loaded by nearby players grows it all to max.
                if (topY >= kelpSurfaceY.get()) maxed++;
            }
        }
        // enough kelp, and at most `exception-amount` columns NOT yet grown to max
        return columns >= sensitivity.get() && (columns - maxed) <= exceptionAmount.get();
    }

    private boolean isGrowable(String p) {
        // only vines that grow LONG from chunk-loading — their length method is the anti-false-positive signal
        return p.equals("vine") || p.startsWith("cave_vines")
            || p.startsWith("twisting_vines") || p.startsWith("weeping_vines");
    }

    /** Their GrowthFinder maxVineLength method: any growable column at least growth-length tall = grown from chunk-loading over time. */
    private boolean hasLongGrowth(WorldChunk chunk) { return hasGrowthOfLength(chunk, scaled(growthLength, 25)); }

    private boolean hasGrowthOfLength(WorldChunk chunk, int gl) {
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int bottom = chunk.getBottomY(), top = chunk.getTopYInclusive();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int run = 0;
            for (int y = bottom; y <= top; y++) {
                String p = shama.addon.util.BlockPaths.of(chunk.getBlockState(m.set(bx + x, y, bz + z)).getBlock());
                if (isGrowable(p)) { if (++run >= gl) return true; } else run = 0;
            }
        }
        return false;
    }

    private boolean isKelp(WorldChunk chunk, int x, int y, int z) {
        if (y < chunk.getBottomY() || y > chunk.getTopYInclusive()) return false;
        String p = shama.addon.util.BlockPaths.of(chunk.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).getBlock());
        return p.equals("kelp") || p.equals("kelp_plant");
    }

    /** Count dropped glow-ink/egg/turtle-scute/armadillo-scute items per chunk each tick (they pile up under farms). */
    private int itemScanTick;

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!packetAmethyst.get() || mc.world == null) return;
        if (!(event.packet instanceof BlockUpdateS2CPacket p)) return;
        String path = shama.addon.util.BlockPaths.of(p.getState().getBlock());
        if (!path.contains("amethyst")) return;
        long key = new ChunkPos(p.getPos()).toLong();
        int n = packetAmethystCounts.merge(key, 1, Integer::sum);
        if (n >= hitsNeeded(packetAmethystThreshold)) packetAmethystChunks.add(key);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) return;
        if (itemScanTick++ % 10 != 0) return;   // only sweep dropped items ~2x/sec, not every tick
        if (mc.player != null) {                 // prune flagged data outside the chunk range
            int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get() + 2;
            counts.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > r || Math.abs(new ChunkPos(k).z - pcz) > r);
            kelpFarms.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > r || Math.abs(new ChunkPos(k).z - pcz) > r);
            longGrowth.keySet().removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > r || Math.abs(new ChunkPos(k).z - pcz) > r);
            highInhabited.removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > r || Math.abs(new ChunkPos(k).z - pcz) > r);
            scanned.removeIf(k -> Math.abs(new ChunkPos(k).x - pcx) > r || Math.abs(new ChunkPos(k).z - pcz) > r);
        }
        // reset item indices on every tracked chunk, then recount live item entities
        for (int[] c : counts.values()) { c[18] = 0; c[19] = 0; c[20] = 0; c[21] = 0; }
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity ie)) continue;
            String path = Registries.ITEM.getId(ie.getStack().getItem()).getPath();
            int idx;
            if (path.equals("glow_ink_sac")) idx = 18;
            else if (path.equals("egg")) idx = 19;
            else if (path.equals("turtle_scute")) idx = 20;
            else if (path.equals("armadillo_scute")) idx = 21;
            else continue;
            long key = new ChunkPos(e.getBlockPos()).toLong();
            int[] c = counts.get(key);
            if (c != null) c[idx] += ie.getStack().getCount();
        }
    }

    /** Filled-hole detection: a 2-tall air pocket whose floor is solid and whose walls are obsidian/cobblestone (PvP hole / trapped pit). */
    private int computeScannerScore(net.minecraft.world.chunk.WorldChunk chunk) {
        int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
        int score = 0, amethyst = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int run = 0;
            for (int y = chunk.getBottomY(); y <= chunk.getTopYInclusive(); y++) {
                var st = chunk.getBlockState(m.set(bx + x, y, bz + z));
                String p2 = shama.addon.util.BlockPaths.of(st.getBlock());
                if (p2.equals("vine") || p2.startsWith("cave_vines")) { run++; if (run == 8) score += 2; }   // long vine columns
                else run = 0;
                if (p2.equals("amethyst_cluster")) amethyst++;
                if (p2.equals("beehive") || p2.equals("bee_nest")) { if (st.contains(net.minecraft.state.property.Properties.HONEY_LEVEL) && st.get(net.minecraft.state.property.Properties.HONEY_LEVEL) >= 5) score += 2; }
            }
        }
        if (amethyst >= 3) score += 2;
        return score;
    }






    /** Block/item color used to highlight a chunk by which category triggered (their-block-color idea). */
    private Color triggerColor(int idx, int alpha) {
        return switch (idx) {
            case 18 -> new Color(94, 211, 194, alpha);   // glow ink sac teal
            case 19 -> new Color(235, 225, 195, alpha);   // egg cream
            case 20 -> new Color(120, 185, 95, alpha);    // turtle scute green
            case 21 -> new Color(150, 110, 75, alpha);    // armadillo scute brown
            case 22 -> new Color(70, 45, 100, alpha);     // filled-hole obsidian purple
            default -> null;
        };
    }

    @EventHandler
    private void onIndirectPacket(PacketEvent.Receive event) {
        if (!indirectDetection.get() || mc.world == null) return;
        if (event.packet instanceof EntitySpawnS2CPacket p) {
            int cx = (int) Math.floor(p.getX()) >> 4, cz = (int) Math.floor(p.getZ()) >> 4;
            if (!seenChunk(cx, cz)) indirectScore.merge(ChunkPos.toLong(cx, cz), entityScore.get(), Integer::sum);
        }
        else if (event.packet instanceof PlaySoundS2CPacket p) {
            int cx = (int) Math.floor(p.getX()) >> 4, cz = (int) Math.floor(p.getZ()) >> 4;
            if (!seenChunk(cx, cz)) indirectScore.merge(ChunkPos.toLong(cx, cz), soundScore.get(), Integer::sum);
        }
        else if (event.packet instanceof ChunkDataS2CPacket p) {
            long now = System.currentTimeMillis();
            if (now - lastChunkData < 30) indirectScore.merge(ChunkPos.toLong(p.getChunkX(), p.getChunkZ()), 1, Integer::sum);
            lastChunkData = now;
        }
    }

    /** True when the chunk is loaded client-side, i.e. you can already see whatever happened there. */
    private boolean seenChunk(int cx, int cz) {
        if (!indirectOnlyUnseen.get() || mc.world == null) return false;
        try { return mc.world.getChunkManager().isChunkLoaded(cx, cz); }
        catch (Throwable t) { return false; }
    }

    @EventHandler
    private void onIndirectDecay(TickEvent.Post event) {
        if (!indirectDetection.get() || scoreDecayTicks.get() <= 0) return;
        if (++decayTick % scoreDecayTicks.get() != 0) return;
        indirectScore.replaceAll((k, v) -> v - 1);
        indirectScore.values().removeIf(v -> v <= 0);
    }


    private void count(BlockState bs, int[] c, int worldY) {
        String p = shama.addon.util.BlockPaths.of(bs.getBlock());
        if (p.equals("kelp") || p.equals("kelp_plant")) c[0]++;
        else if (p.equals("vine")) c[1]++;
        else if (p.equals("cocoa")) c[2]++;
        else if (p.equals("sweet_berry_bush")) c[23]++;
        else if (p.equals("cactus")) c[24]++;
        else if (p.equals("cave_vines") || p.equals("cave_vines_plant")) c[3]++;
        else if (p.contains("amethyst")) {
            boolean ok;
            if (p.equals("amethyst_block")) ok = amethystIncludeBlock.get();
            else if (p.equals("budding_amethyst")) ok = amethystIncludeBudding.get();
            else if (p.equals("amethyst_cluster")) ok = true;
            else ok = !amethystFullyGrownOnly.get();                 // small/medium/large buds
            if (ok && (!amethystRequireDeep.get() || worldY <= amethystMinDepth.get())) c[4]++;
        }
        else if (p.equals("deepslate") && bs.contains(Properties.AXIS) && bs.get(Properties.AXIS) != Direction.Axis.Y) c[5]++;
        else if (p.equals("bamboo") || p.equals("bamboo_sapling")) c[6]++;
        else if (p.equals("bee_nest") || p.equals("beehive")) c[7]++;
        else if (p.equals("pointed_dripstone") || p.equals("dripstone_block")) c[8]++;
        else if (p.startsWith("sculk")) c[9]++; // sculk, sculk_sensor, sculk_catalyst, sculk_shrieker, sculk_vein
        else if (p.equals("sugar_cane")) c[10]++;
        else if (p.equals("moss_block") || p.equals("moss_carpet")) c[13]++;
        else if (p.equals("azalea") || p.equals("flowering_azalea") || p.endsWith("azalea_leaves")) c[14]++;
        else if (p.equals("glow_lichen")) c[15]++;
        else if (p.equals("spore_blossom")) c[16]++;
        else if (p.equals("big_dripleaf") || p.equals("big_dripleaf_stem") || p.equals("small_dripleaf")) c[17]++;
        else if (p.endsWith("chest") || p.equals("barrel") || p.endsWith("shulker_box") || p.equals("furnace") || p.equals("blast_furnace") || p.equals("smoker") || p.equals("hopper") || p.equals("dispenser") || p.equals("dropper")) c[11]++;
        else if (p.equals("obsidian") || p.equals("crying_obsidian")) c[12]++;
    }

    /** Growables: plants and blocks that only pile up while a chunk stays loaded. */
    private static final java.util.Set<Integer> GROWABLES = java.util.Set.of(0, 1, 2, 3, 6, 8, 9, 10, 13, 14, 15, 16, 17, 23, 24);
    /** Storage, player-placed blocks and dropped-item signals. */
    private static final java.util.Set<Integer> CONTAINERS = java.util.Set.of(4, 5, 7, 11, 12, 18, 19, 20, 21, 22);

    private boolean enabled(int i) {
        switch (preset.get()) {
            case All -> { return true; }
            case Growables -> { return GROWABLES.contains(i); }
            case Containers -> { return CONTAINERS.contains(i); }
            default -> { }        // Custom: fall through to the tickboxes
        }
        return switch (i) {
            case 0 -> kelp.get();
            case 1 -> vines.get();
            case 2 -> cocoa.get();
            case 3 -> caveVines.get();
            case 4 -> amethyst.get();
            case 23 -> sweetBerries.get();
            case 24 -> cactus.get();
            case 5 -> rotatedDeepslate.get();
            case 6 -> bamboo.get();
            case 7 -> beeNest.get();
            case 8 -> dripstone.get();
            case 9 -> sculk.get();
            case 10 -> sugarCane.get();
            case 11 -> containers.get();
            case 12 -> obsidian.get();
            case 13 -> moss.get();
            case 14 -> azalea.get();
            case 15 -> glowLichen.get();
            case 16 -> sporeBlossom.get();
            case 17 -> bigDripleaf.get();
            case 18 -> glowInk.get();
            case 19 -> egg.get();
            case 20 -> turtleScute.get();
            case 21 -> armadilloScute.get();
            default -> coveredHoles.get();      // 22 = covered holes, kept here as a chunk-level signal
        };
    }

    /** Beam / floor overlay for chunks caught by indirect scoring (merged from packet-debug). */
    private void renderIndirect(Render3DEvent event) {
        if (!indirectDetection.get() || indirectScore.isEmpty()) return;
        if (!indirectBeam.get() && !indirectFloor.get()) return;
        int need = scaled(flagScore, 10);
        SettingColor bc = indirectBeamColor.get(), fc = indirectFloorColor.get();
        for (var e : indirectScore.entrySet()) {
            if (e.getValue() < need) continue;
            ChunkPos cp = new ChunkPos(e.getKey());
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            if (indirectFloor.get())
                event.renderer.box(x0, topY.get(), z0, x0 + 16, topY.get() + 0.3, z0 + 16,
                    fc, new Color(fc.r, fc.g, fc.b, 160), ShapeMode.Both, 0);
            if (indirectBeam.get())
                event.renderer.box(x0 + 7, topY.get(), z0 + 7, x0 + 9, topY.get() + 80, z0 + 9,
                    new Color(bc.r, bc.g, bc.b, 40), bc, ShapeMode.Both, 0);
        }
    }

    /** Chunks somebody else was holding open, drawn in their own colour. */
    private void renderTraces(Render3DEvent event) {
        if (!playerWasHere.get() || traceChunks.isEmpty() || mc.player == null) return;
        SettingColor tc = traceColor.get();
        Color fill = new Color(tc.r, tc.g, tc.b, tc.a);
        Color line = new Color(tc.r, tc.g, tc.b, Math.min(255, tc.a + 140));
        int pcx = mc.player.getChunkPos().x, pcz = mc.player.getChunkPos().z, r = chunkRange.get();
        double y = topY.get();
        for (long k : traceChunks) {
            ChunkPos cp = new ChunkPos(k);
            if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;
            event.renderer.box(cp.getStartX(), y, cp.getStartZ(), cp.getStartX() + 16, y + 1, cp.getStartZ() + 16,
                fill, line, shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        renderTraces(event);
        renderIndirect(event);
        if (mc.player == null || counts.isEmpty()) return;
        int sens = sensitivity.get();
        double y = topY.get();
        Color fill = sideColor.get(), line = lineColor.get();

        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            boolean flag = false;
            int trigger = -1;
            for (int i = 0; i < c.length; i++) {
                if (!enabled(i)) continue;
                if (i == 0 && fullKelpOnly.get()) { if (Boolean.TRUE.equals(kelpFarms.get(e.getKey()))) { flag = true; trigger = 0; break; } continue; }
                if (COUNT_SUPERSEDED.contains(i)) continue; // vines/cave-vines: length method governs, count would false-positive
                int t = (i == 19) ? Math.max(1, sens / 2) : sens; // egg uses half sensitivity
                if (EXCEPTIONAL.contains(i)) t += exceptionAmount.get(); // natural types need more before flagging
                if (i == 4) t = hitsNeeded(amethystMinClusters) + (EXCEPTIONAL.contains(4) ? exceptionAmount.get() : 0); // amethyst has its own threshold
                if (c[i] >= t) { flag = true; trigger = i; break; }
            }
            if (!flag && (vines.get() || caveVines.get())
                    && Boolean.TRUE.equals(longGrowth.get(e.getKey()))) { flag = true; trigger = 1; }
            if (!flag && inhabitedTime.get() && highInhabited.contains(e.getKey())) { flag = true; trigger = -1; }
            if (!flag && scannerScore.get() && highScanner.contains(e.getKey())) { flag = true; trigger = -1; }
            if (!flag && indirectDetection.get() && indirectScore.getOrDefault(e.getKey(), 0) >= scaled(flagScore, 10)) { flag = true; trigger = -1; }
            if (!flag && suspicionScore.get() && susScored.contains(e.getKey())) { flag = true; trigger = -1; }
            // count each chunk's flag ONCE per flag-cycle (this loop runs every frame, so a raw
            // counter here would lock everything within a second)
            if (flag && baseLock.get()) {
                if (flaggedNow.add(e.getKey()) && sourceHistory.merge(e.getKey(), 1, Integer::sum) >= lockAfter.get())
                    baseChunks.add(e.getKey());
            } else if (!flag) flaggedNow.remove(e.getKey());
            if (!flag && packetAmethyst.get() && packetAmethystChunks.contains(e.getKey())) { flag = true; trigger = 4; }
            if (!flag && baseLock.get() && baseChunks.contains(e.getKey())) flag = true;   // locked bases stay shown
            if (flag && announced.add(e.getKey()) && chatAlert.get()) {   // always mark seen; only print if enabled
                ChunkPos acp = new ChunkPos(e.getKey());
                shama.addon.util.Chat.info("[SusChunkFinder] suspicious chunk at %d, %d%s", acp.x, acp.z, trigger >= 0 ? " (" + NAMES[trigger] + ")" : "");
            }
            if (!flag) continue;

            ChunkPos cp = new ChunkPos(e.getKey());
            double x0 = cp.x * 16, z0 = cp.z * 16;
            if (Math.abs(cp.x - mc.player.getChunkPos().x) > chunkRange.get() || Math.abs(cp.z - mc.player.getChunkPos().z) > chunkRange.get()) continue;
            Color tf = triggerColor(trigger, 60), tl = triggerColor(trigger, 220);
            if (trigger == 4 || packetAmethystChunks.contains(e.getKey())) {   // amethyst: its own colour
                var ac = amethystColor.get();
                tf = new Color(ac.r, ac.g, ac.b, 60); tl = new Color(ac.r, ac.g, ac.b, 230);
            }
            if (baseLock.get() && baseChunks.contains(e.getKey())) {
                var lc = lockedColor.get();
                tf = new Color(lc.r, lc.g, lc.b, 60); tl = lc;
            } else if (extremeChunks.contains(e.getKey())) {
                var ec = extremeColor.get();
                tf = new Color(ec.r, ec.g, ec.b, 60); tl = ec;
            }
            event.renderer.box(x0, y, z0, x0 + 16, y + 1, z0 + 16, tf != null ? tf : fill, tl != null ? tl : line, shapeMode.get(), 0);
            if (tracers.get()) {
                var cc = meteordevelopment.meteorclient.utils.render.RenderUtils.center;
                event.renderer.line(cc.x, cc.y, cc.z, x0 + 8, y, z0 + 8, tl != null ? tl : line);
            }
            // highlight the covered-hole fill blocks in blue
        }
    }

    @Override
    public String getInfoString() {
        int sens = sensitivity.get(), n = 0;
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            boolean matched = false;
            for (int i = 0; i < c.length; i++) {
                if (!enabled(i)) continue;
                if (i == 0 && fullKelpOnly.get()) { if (Boolean.TRUE.equals(kelpFarms.get(e.getKey()))) { matched = true; break; } continue; }
                int t = (i == 19) ? Math.max(1, sens / 2) : sens;
                if (COUNT_SUPERSEDED.contains(i)) continue;
                if (EXCEPTIONAL.contains(i)) t += exceptionAmount.get();
                if (i == 4) t = hitsNeeded(amethystMinClusters) + (EXCEPTIONAL.contains(4) ? exceptionAmount.get() : 0);
                if (c[i] >= t) { matched = true; break; }
            }
            if (!matched && (vines.get() || caveVines.get())
                    && Boolean.TRUE.equals(longGrowth.get(e.getKey()))) matched = true;
            if (!matched && inhabitedTime.get() && highInhabited.contains(e.getKey())) matched = true;
            if (!matched && scannerScore.get() && highScanner.contains(e.getKey())) matched = true;
            if (!matched && indirectDetection.get() && indirectScore.getOrDefault(e.getKey(), 0) >= scaled(flagScore, 10)) matched = true;
            if (matched) n++;
        }
        return n + " sus";
    }
}
