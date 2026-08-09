package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deep Activity — flags hidden bases by watching block activity below Y 0 in chunks you can't
 * see. Each block update under the Y level counts toward that chunk; hitting the minimum
 * highlights it. A NEW dropped item/block appearing below Y 0 (one that wasn't there a moment
 * ago) bypasses the minimum and flags instantly. To avoid false flags, the burst of updates an
 * anti-xray chunk sends when it reveals on approach is ignored (a settle window after the chunk
 * loads, plus per-chunk rate limiting). The highlight stays until you're close enough to render
 * the chunk, and each new flag fires a title popup + chat alert (+ optional sound).
 */
public class DeepActivity extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Integer> sensitivity = sg.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.")
        .defaultValue(3).min(1).max(20).sliderRange(1, 20).build());

    private final Setting<Boolean> customThresholds = sg.add(new BoolSetting.Builder()
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
        return Math.max(1, (int) Math.round(base * (sensitivity.get() / 3.0)));
    }

    private int hitsNeeded(Setting<Integer> setting) {
        return customThresholds.get() ? setting.get() : sensitivity.get();
    }

    private final SettingGroup sgR = settings.createGroup("Render");

    private final Setting<Integer> yLevel = sg.add(new IntSetting.Builder()
        .name("y-level").description("Only count block activity below this Y.").defaultValue(0).min(-64).max(64).sliderRange(-64, 16).build());
    private final Setting<Boolean> ignoreSelf = sg.add(new BoolSetting.Builder()
        .name("ignore-self").description("Ignore block changes you cause yourself, so your own mining doesn't flag the chunk you're standing in.")
        .defaultValue(true).build());
    private final Setting<Integer> selfRadius = sg.add(new IntSetting.Builder()
        .name("self-radius").description("Block changes closer than this to you count as yours and are ignored.")
        .defaultValue(6).min(1).max(64).sliderRange(2, 24).visible(ignoreSelf::get).build());
    private final Setting<Integer> zoneSize = sg.add(new IntSetting.Builder()
        .name("scan-zone-size").description("Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.")
        .defaultValue(1).min(1).max(6).sliderRange(1, 6).build());
    private final Setting<Integer> minUpdates = sg.add(new IntSetting.Builder()
        .name("min-updates").description("Block updates in a chunk (below the Y level) before it's highlighted.").defaultValue(3).min(1).max(50).sliderRange(1, 20).visible(customThresholds::get).build());
    private final Setting<Boolean> droppedItems = sg.add(new BoolSetting.Builder()
        .name("dropped-items").description("A newly-appeared dropped item/block below the Y level flags the chunk instantly, ignoring the minimum.").defaultValue(true).build());
    private final Setting<Double> settleSeconds = sg.add(new DoubleSetting.Builder()
        .name("ignore-after-load").description("Ignore a chunk's block updates for this long after it loads — skips the anti-xray reveal burst.").defaultValue(3).min(0).sliderRange(0, 10).decimalPlaces(1).build());
    private final Setting<Integer> burstLimit = sg.add(new IntSetting.Builder()
        .name("updates-per-second-cap").description("Max block updates counted per chunk per second — high so real farms count fully; only absurd bursts get capped.").defaultValue(200).min(10).max(2000).sliderRange(50, 500).build());
    private final Setting<Double> clearRange = sg.add(new DoubleSetting.Builder()
        .name("forget-when-close").description("Clear a chunk's highlight once you're this close (it's rendered now).").defaultValue(48).min(8).sliderRange(16, 128).build());

    // ===== merged activity-finder: raw block-update positions (any Y) =====
    // ===== amethyst: growth stage reads as activity — a farmed geode is stripped of grown clusters =====
    private final SettingGroup sgAmethyst = settings.createGroup("Amethyst");
    private final Setting<Boolean> amethystHighlight = sgAmethyst.add(new BoolSetting.Builder()
        .name("amethyst-highlight")
        .description("Colour amethyst by how far it has grown. A geode nobody touches fills up with fully-grown clusters; one being harvested is stripped back to bare budding blocks, because the grown ones keep getting taken. That difference tells you somebody is working it.")
        .defaultValue(false).build());
    private final Setting<Boolean> amethystBudding = sgAmethyst.add(new BoolSetting.Builder()
        .name("show-budding")
        .description("Include budding amethyst — the block crystals grow out of. It cannot be mined, so a geode down to bare budding blocks is a farmed one.")
        .defaultValue(true).visible(amethystHighlight::get).build());
    private final Setting<Boolean> amethystBlocks = sgAmethyst.add(new BoolSetting.Builder()
        .name("show-blocks")
        .description("Include plain amethyst blocks too. Geodes are full of them, so this gets noisy.")
        .defaultValue(false).visible(amethystHighlight::get).build());
    private final Setting<Integer> amethystRange = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-range")
        .description("How far out to look for amethyst, in blocks.")
        .defaultValue(48).min(8).max(160).sliderRange(16, 96).visible(amethystHighlight::get).build());
    private final Setting<Integer> amethystScanTicks = sgAmethyst.add(new IntSetting.Builder()
        .name("amethyst-scan-ticks")
        .description("Ticks between amethyst sweeps.")
        .defaultValue(40).min(5).max(200).sliderRange(10, 100).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amBudding = sgAmethyst.add(new ColorSetting.Builder()
        .name("budding-color").description("Colour for budding amethyst.")
        .defaultValue(new SettingColor(255, 90, 220, 220)).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amSmall = sgAmethyst.add(new ColorSetting.Builder()
        .name("small-color").description("Colour for a small bud — just started.")
        .defaultValue(new SettingColor(120, 80, 200, 200)).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amMedium = sgAmethyst.add(new ColorSetting.Builder()
        .name("medium-color").description("Colour for a medium bud.")
        .defaultValue(new SettingColor(160, 90, 230, 210)).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amLarge = sgAmethyst.add(new ColorSetting.Builder()
        .name("large-color").description("Colour for a large bud — nearly grown.")
        .defaultValue(new SettingColor(200, 110, 245, 220)).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amGrown = sgAmethyst.add(new ColorSetting.Builder()
        .name("grown-color").description("Colour for a fully-grown cluster, the one worth breaking.")
        .defaultValue(new SettingColor(240, 150, 255, 235)).visible(amethystHighlight::get).build());
    private final Setting<SettingColor> amBlock = sgAmethyst.add(new ColorSetting.Builder()
        .name("block-color").description("Colour for plain amethyst blocks.")
        .defaultValue(new SettingColor(150, 110, 190, 120)).visible(amethystBlocks::get).build());

    /** {x, y, z, stage} — stage 0 budding, 1 small, 2 medium, 3 large, 4 grown, 5 plain block. */
    private final java.util.List<int[]> amethyst = new java.util.ArrayList<>();
    private int amethystTick;

    private final SettingGroup sgMobs = settings.createGroup("Mobs");
    private final Setting<Boolean> mobCluster = sgMobs.add(new BoolSetting.Builder()
        .name("mob-cluster")
        .description("Flag places where the same mob piles up far past natural numbers — an enderman swarm in the End, or a packed mob farm anywhere else. Works even when the farm itself is out of sight, because the mobs are still sent to you.")
        .defaultValue(true).build());
    private final Setting<Integer> mobMin = sgMobs.add(new IntSetting.Builder()
        .name("min-mobs").description("How many of one kind must be packed together before it counts.")
        .defaultValue(14).min(3).max(200).sliderRange(5, 60).visible(() -> customThresholds.get() && mobCluster.get()).build());
    private final Setting<Integer> mobRadius = sgMobs.add(new IntSetting.Builder()
        .name("cluster-radius").description("How close together they must be, in blocks.")
        .defaultValue(12).min(2).max(64).sliderRange(4, 32).visible(mobCluster::get).build());
    private final Setting<Boolean> mobIgnoreY = sgMobs.add(new BoolSetting.Builder()
        .name("mobs-at-any-height").description("Check for mob clusters at any height. Enderman farms in the End sit high up, so the usual below-Y rule would miss them.")
        .defaultValue(true).visible(mobCluster::get).build());
    private final Setting<Integer> mobScanTicks = sgMobs.add(new IntSetting.Builder()
        .name("mob-scan-ticks").description("Ticks between mob sweeps.")
        .defaultValue(40).min(5).max(200).sliderRange(10, 100).visible(mobCluster::get).build());

    private int mobTick;

    private final Setting<Boolean> farmGuard = sg.add(new BoolSetting.Builder()
        .name("farm-guard")
        .description("Stop processing a zone once it floods you with updates. Farms fire thousands of block changes a second, and handling every one is what makes the game stutter near them. The zone is still flagged — it just stops being re-counted.")
        .defaultValue(true).build());
    private final Setting<Integer> farmCap = sg.add(new IntSetting.Builder()
        .name("updates-per-zone-cap")
        .description("Updates from one zone in a single tick before it's treated as a farm and skipped.")
        .defaultValue(400).min(50).max(5000).sliderRange(100, 2000).visible(farmGuard::get).build());

    private final Map<Long, Integer> tickLoad = new ConcurrentHashMap<>();   // zone -> updates this tick
    private final java.util.Set<Long> farmZones = ConcurrentHashMap.newKeySet();

    private final SettingGroup sgDrops = settings.createGroup("Dropped Items");
    private final Setting<Integer> dropBurst = sgDrops.add(new IntSetting.Builder()
        .name("drop-burst")
        .description("How many items have to appear at once before it counts. A player breaking a stash drops a pile in one go; one or two items on their own are usually just a mob death or something that came in with the chunk.")
        .defaultValue(15).min(1).max(200).sliderRange(2, 60).build());
    private final Setting<Integer> dropWindow = sgDrops.add(new IntSetting.Builder()
        .name("drop-window-ticks")
        .description("How long that pile has to land in, in ticks.")
        .defaultValue(20).min(2).max(200).sliderRange(5, 60).visible(droppedItems::get).build());
    private final Setting<Boolean> ignoreOnLoad = sgDrops.add(new BoolSetting.Builder()
        .name("ignore-on-chunk-load")
        .description("Ignore items that show up in the same moment a chunk loads. Those were already lying there — the server is just telling you about them now, and without this every chunk full of old drops looks like fresh activity.")
        .defaultValue(true).visible(droppedItems::get).build());
    private final Setting<Integer> loadGraceTicks = sgDrops.add(new IntSetting.Builder()
        .name("load-grace-ticks")
        .description("How long after a chunk arrives to keep ignoring its items.")
        .defaultValue(40).min(5).max(200).sliderRange(10, 100).visible(ignoreOnLoad::get).build());

    private final Map<Long, Integer> dropCount = new ConcurrentHashMap<>();   // zone -> drops this window
    private int dropTimer;

    private final SettingGroup sgLock = settings.createGroup("Lock");
    private final Setting<Boolean> baseLock = sgLock.add(new BoolSetting.Builder()
        .name("base-lock").description("Once a zone has flagged several times it's almost certainly a base, not a passer-by. Lock it so it stays highlighted even after the activity stops and you move away.")
        .defaultValue(false).build());
    private final Setting<Integer> lockAfter = sgLock.add(new IntSetting.Builder()
        .name("lock-after").description("How many separate flags a zone needs before it locks.")
        .defaultValue(3).min(2).max(20).sliderRange(2, 10).visible(baseLock::get).build());
    private final Setting<SettingColor> lockedColor = sgLock.add(new ColorSetting.Builder()
        .name("locked-color").description("Colour used for locked zones.").defaultValue(new SettingColor(255, 60, 60, 90)).visible(baseLock::get).build());

    private final SettingGroup sgRaw = settings.createGroup("Raw Positions");
    private final Setting<Boolean> rawPositions = sgRaw.add(new BoolSetting.Builder().name("raw-positions").description("Box every individual block-update position (any Y), fading out over time. Shows distant players building/mining live.").defaultValue(false).build());
    private final Setting<Boolean> rawRespectY = sgRaw.add(new BoolSetting.Builder()
        .name("raw-below-y-only").description("Only box raw positions that are below the Y level above. Turn off to see updates at any height.")
        .defaultValue(true).visible(rawPositions::get).build());
    private final Setting<Integer> fadeTicks = sgRaw.add(new IntSetting.Builder().name("fade-ticks").description("How long each position stays visible, in ticks.").defaultValue(100).min(10).max(600).sliderRange(20, 300).visible(rawPositions::get).build());
    private final Setting<SettingColor> rawColor = sgRaw.add(new ColorSetting.Builder().name("update-box-color").description("Colour of the raw update boxes.").defaultValue(new SettingColor(0, 255, 255, 200)).visible(rawPositions::get).build());

    // ===== merged mining-monitor: break-rate + tunnel-shape alerts =====
    private final SettingGroup sgMining = settings.createGroup("Mining Alerts");
    private final Setting<Boolean> miningAlerts = sgMining.add(new BoolSetting.Builder().name("mining-alerts").description("Warn in chat when someone else is breaking blocks fast nearby.").defaultValue(false).build());
    private final Setting<Integer> breaksPerWindow = sgMining.add(new IntSetting.Builder().name("breaks-per-window").description("How many blocks must break inside the window to warn.").defaultValue(6).min(1).max(100).sliderRange(2, 30).visible(() -> customThresholds.get() && miningAlerts.get()).build());
    private final Setting<Integer> miningWindow = sgMining.add(new IntSetting.Builder().name("mining-window-ticks").description("Length of the counting window, in ticks.").defaultValue(40).min(5).max(400).sliderRange(10, 120).visible(miningAlerts::get).build());
    private final Setting<Integer> miningMinDistance = sgMining.add(new IntSetting.Builder().name("min-distance").description("Ignore breaks closer than this to you (your own mining).").defaultValue(6).min(0).max(64).sliderRange(0, 32).visible(miningAlerts::get).build());
    private final Setting<Boolean> miningRespectY = sgMining.add(new BoolSetting.Builder()
        .name("mining-below-y-only").description("Only count breaks that happen below the Y level above. Without this, someone clearing trees on the surface trips the mining warning.")
        .defaultValue(true).visible(miningAlerts::get).build());
    private final Setting<Boolean> tunnelShape = sgMining.add(new BoolSetting.Builder().name("tunnel-shape").description("Only warn when the breaks form a long, narrow, flat run (a dug tunnel) rather than scattered digging or a vertical shaft.").defaultValue(false).visible(miningAlerts::get).build());
    private final Setting<Integer> minTunnelLength = sgMining.add(new IntSetting.Builder().name("min-tunnel-length").description("How long the run must be to count as a tunnel.").defaultValue(8).min(3).max(64).sliderRange(4, 32).visible(() -> customThresholds.get() && tunnelShape.get()).build());

    private final java.util.Map<Long, Integer> rawHits = new ConcurrentHashMap<>();   // pos -> ticks left
    private final java.util.List<BlockPos> miningBreaks = new java.util.ArrayList<>();
    private final Map<Long, Integer> lockHits = new ConcurrentHashMap<>();   // zone -> times flagged
    private final java.util.Set<Long> lockedZones = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> everAlerted = ConcurrentHashMap.newKeySet();   // zones already announced
    private int miningCount, miningTimer;
    private BlockPos miningLast;

    private final Setting<Boolean> popup = sg.add(new BoolSetting.Builder().name("popup").description("Show a popup on screen. Off by default — the chat line is usually enough and popups get intrusive when several chunks trip at once.").defaultValue(false).build());
    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder().name("chat").description("Print a message in chat.").defaultValue(true).build());
    private final Setting<Boolean> sound = sg.add(new BoolSetting.Builder().name("sound").description("Play a sound alert.").defaultValue(true).build());

    private final Setting<Double> renderDistance = sgR.add(new DoubleSetting.Builder().name("render-distance").description("How far away (in blocks) things are still drawn.").defaultValue(256).min(64).sliderRange(128, 4096).build());
    private final Setting<SettingColor> lineColor = sgR.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(255, 60, 60, 220)).build());
    private final Setting<SettingColor> fillColor = sgR.add(new ColorSetting.Builder().name("fill-color").description("Colour of the filled part of the box.").defaultValue(new SettingColor(255, 60, 60, 45)).build());

    private final Map<Long, Integer> counts = new ConcurrentHashMap<>();
    private final Map<Long, Long> flagged = new ConcurrentHashMap<>();   // chunkKey -> flag time
    private final Map<Long, Long> chunkLoad = new ConcurrentHashMap<>(); // chunkKey -> load time (reveal window)
    private final Map<Long, long[]> burstWindow = new ConcurrentHashMap<>(); // chunkKey -> [windowStartMs, countThisSecond, countedBigBurst]
    private final Map<Long, Long> bigBurst = new ConcurrentHashMap<>();      // chunkKey -> times it hit the burst cap
    private final Set<Integer> seenItems = new HashSet<>();              // dropped-item ids seen last scan
    private boolean itemsBaselined;
    private int tick;

    public DeepActivity() { super(shama.addon.ShamaAddon.HUNT, "deep-activity++", "Everything block-update based, each its own tick: hidden chunk activity below a Y line, raw update positions anywhere, and mining-rate / tunnel alerts."); }

    @Override public void onActivate() { counts.clear(); flagged.clear(); chunkLoad.clear(); burstWindow.clear(); bigBurst.clear(); seenItems.clear(); dropCount.clear(); itemsBaselined = false; }
    @Override public void onDeactivate() { everAlerted.clear(); rawHits.clear(); miningBreaks.clear(); miningCount = 0; miningTimer = 0; counts.clear(); flagged.clear(); chunkLoad.clear(); burstWindow.clear(); bigBurst.clear(); seenItems.clear(); lockHits.clear(); lockedZones.clear(); tickLoad.clear(); farmZones.clear(); amethyst.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        chunkLoad.put(event.chunk().getPos().toLong(), System.currentTimeMillis());
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.world == null) return;
        // Mining and explosions arrive as BULK section updates, not single block updates — without this,
        // raw positions and mining alerts never saw anyone breaking blocks.
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket bulk) {
            bulk.visitUpdates((bp, state) -> handleUpdate(bp.toImmutable(), state.isAir()));
            return;
        }
        if (!(event.packet instanceof BlockUpdateS2CPacket p)) return;
        handleUpdate(p.getPos().toImmutable(), p.getState().isAir());
    }

    /** True when this change is close enough to you that it's almost certainly your own doing. */
    private boolean isSelf(BlockPos pos) {
        if (!ignoreSelf.get() || mc.player == null) return false;
        double r = selfRadius.get();
        return mc.player.getBlockPos().getSquaredDistance(pos) <= r * r;
    }

    private void handleUpdate(BlockPos pos, boolean nowAir) {
        if (isSelf(pos)) return;                                  // your own mining shouldn't flag you
        // merged activity-finder: remember update positions (optionally only below the Y line)
        if (rawPositions.get() && (!rawRespectY.get() || pos.getY() < yLevel.get()))
            rawHits.put(pos.asLong(), fadeTicks.get());
        // merged mining-monitor: count breaks (block -> air) that aren't your own
        if (miningAlerts.get() && nowAir && mc.player != null
            && (!miningRespectY.get() || pos.getY() < yLevel.get())
            && mc.player.getBlockPos().getSquaredDistance(pos) >= (double) miningMinDistance.get() * miningMinDistance.get()) {
            miningCount++; miningLast = pos;
            if (tunnelShape.get()) { miningBreaks.add(miningLast); if (miningBreaks.size() > 512) miningBreaks.remove(0); }
        }
        if (pos.getY() >= yLevel.get()) return;
        long ck = zoneKey(pos.getX() >> 4, pos.getZ() >> 4);
        if (farmGuard.get()) {
            if (farmZones.contains(ck)) return;                       // known farm: already flagged, skip the work
            if (tickLoad.merge(ck, 1, Integer::sum) > farmCap.get()) { farmZones.add(ck); return; }
        }
        long now = System.currentTimeMillis();

        Long loaded = chunkLoad.get(ck);
        if (loaded != null && now - loaded < (long) (settleSeconds.get() * 1000)) return; // inside anti-xray reveal window -> ignore

        // per-second burst cap: farms (lots of legit updates) count fully; only absurd bursts are capped
        long[] w = burstWindow.computeIfAbsent(ck, k -> new long[]{now, 0, 0});
        if (now - w[0] >= 1000) { w[0] = now; w[1] = 0; w[2] = 0; }
        if (w[1] >= burstLimit.get()) {
            // this chunk hit the cap this second (a big burst). One reveal is fine, but if the same
            // spot bursts repeatedly it's real activity (a farm), so flag after it happens >2 times.
            if (w[2] == 0) {
                w[2] = 1;
                long bb = bigBurst.merge(ck, 1L, Long::sum);
                if (bb > 2) flag(ck, "repeated burst");
            }
            return;
        }
        w[1]++;

        int c = counts.merge(ck, 1, Integer::sum);
        if (c >= hitsNeeded(minUpdates)) flag(ck, "activity");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickLoad.clear();   // per-tick counter for the farm guard
        if (++dropTimer >= Math.max(2, dropWindow.get())) { dropTimer = 0; dropCount.clear(); }
        if (rawPositions.get()) { rawHits.replaceAll((k, v) -> v - 1); rawHits.values().removeIf(v -> v <= 0); }
        else rawHits.clear();
        if (miningAlerts.get()) {
            if (++miningTimer >= miningWindow.get()) {
                if (miningCount >= hitsNeeded(breaksPerWindow) && miningLast != null && chat.get() && (!tunnelShape.get() || isTunnelShape()))
                    shama.addon.util.Chat.warning("[DeepActivity] mining near (%d, %d, %d) — %d breaks%s",
                        miningLast.getX(), miningLast.getY(), miningLast.getZ(), miningCount, tunnelShape.get() ? " (tunnel)" : "");
                miningCount = 0; miningTimer = 0; miningBreaks.clear();
            }
        } else { miningCount = 0; miningTimer = 0; miningBreaks.clear(); }
        if (mc.world == null || mc.player == null) return;

        // newly-appeared dropped items / falling blocks below Y0 -> instant flag
        if (droppedItems.get() && tick++ % 5 == 0) {
            Set<Integer> current = new HashSet<>();
            for (Entity e : mc.world.getEntities()) {
                if (e.getY() >= yLevel.get()) continue;
                if (isSelf(e.getBlockPos())) continue;
                if (!(e instanceof ItemEntity) && !(e instanceof FallingBlockEntity)) continue;
                current.add(e.getId());
                if (itemsBaselined && !seenItems.contains(e.getId())) {
                    long zk = zoneKey(e.getBlockPos().getX() >> 4, e.getBlockPos().getZ() >> 4);
                    // items that arrive with a freshly loaded chunk were already on the ground
                    if (ignoreOnLoad.get()) {
                        Long loaded = chunkLoad.get(ChunkPos.toLong(e.getBlockPos().getX() >> 4, e.getBlockPos().getZ() >> 4));
                        if (loaded != null && mc.world.getTime() - loaded < loadGraceTicks.get()) continue;
                    }
                    if (dropCount.merge(zk, 1, Integer::sum) >= dropBurst.get()) {
                        dropCount.remove(zk);
                        flag(zk, dropBurst.get() + " items dropped at once");
                    }
                }
            }
            seenItems.clear();
            seenItems.addAll(current);
            itemsBaselined = true; // first scan just records the baseline, doesn't flag pre-existing items
        }

        // clear highlights for chunks we've now come close enough to render
        double clearSq = clearRange.get() * clearRange.get();
        long now = System.currentTimeMillis();
        flagged.entrySet().removeIf(en -> {
            ChunkPos cp = new ChunkPos(en.getKey());
            double dx = cp.x * 16 + 8 - mc.player.getX(), dz = cp.z * 16 + 8 - mc.player.getZ();
            if (dx * dx + dz * dz <= clearSq) { counts.remove(en.getKey()); burstWindow.remove(en.getKey()); bigBurst.remove(en.getKey()); return true; } // rendered now
            return now - en.getValue() > 600000L;                                           // stale after 10 min
        });
    }

    /** Collapse a chunk coord into its scan zone, so a wide base counts as one area. */
    private long zoneKey(int cx, int cz) {
        int n = Math.max(1, zoneSize.get());
        return ChunkPos.toLong(Math.floorDiv(cx, n) * n, Math.floorDiv(cz, n) * n);
    }

    private void flag(long ck, String reason) {
        if (flagged.putIfAbsent(ck, System.currentTimeMillis()) != null) return; // already flagged
        if (baseLock.get() && lockHits.merge(ck, 1, Integer::sum) >= lockAfter.get()) lockedZones.add(ck);
        if (!everAlerted.add(ck)) return;      // alerted about this zone once already — never nag again
        ChunkPos cp = new ChunkPos(ck);
        if (chat.get()) shama.addon.util.Chat.warning("[DeepActivity] %s below Y0 at chunk (%d, %d)", reason, cp.x, cp.z);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 25, 8);
            mc.inGameHud.setTitle(Text.literal("Hidden Activity").formatted(Formatting.RED));
            mc.inGameHud.setSubtitle(Text.literal(reason + " @ chunk " + cp.x + ", " + cp.z).formatted(Formatting.YELLOW));
        }
        if (sound.get() && mc.getSoundManager() != null) {
            try {
                if (mc.player != null) mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.9f);
            } catch (Throwable ignored) {}
        }
    }

    private boolean isTunnelShape() {
        if (miningBreaks.size() < scaled(minTunnelLength, 8)) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos b : miningBreaks) {
            minX = Math.min(minX, b.getX()); maxX = Math.max(maxX, b.getX());
            minY = Math.min(minY, b.getY()); maxY = Math.max(maxY, b.getY());
            minZ = Math.min(minZ, b.getZ()); maxZ = Math.max(maxZ, b.getZ());
        }
        int spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;
        if (spanY > 3) return false;                                   // vertical shaft, not a tunnel
        return Math.max(spanX, spanZ) + 1 >= scaled(minTunnelLength, 8) && Math.min(spanX, spanZ) <= 2;
    }

    /** Mob sweep: many of one type packed together means a farm, even if you can't see it. */
    @EventHandler
    private void onMobTick(TickEvent.Post event) {
        if (!mobCluster.get() || mc.world == null || mc.player == null) return;
        if (mobTick++ % Math.max(1, mobScanTicks.get()) != 0) return;

        java.util.Map<net.minecraft.entity.EntityType<?>, java.util.List<net.minecraft.util.math.Vec3d>> byType = new java.util.HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof net.minecraft.entity.mob.MobEntity)) continue;
            if (!mobIgnoreY.get() && e.getY() >= yLevel.get()) continue;
            byType.computeIfAbsent(e.getType(), k -> new java.util.ArrayList<>())
                  .add(new net.minecraft.util.math.Vec3d(e.getX(), e.getY(), e.getZ()));
        }
        double r = mobRadius.get(), r2 = r * r;
        int need = hitsNeeded(mobMin);
        for (var en : byType.entrySet()) {
            var list = en.getValue();
            if (list.size() < need) continue;
            for (net.minecraft.util.math.Vec3d centre : list) {
                int n = 0;
                for (net.minecraft.util.math.Vec3d other : list)
                    if (centre.squaredDistanceTo(other) <= r2) n++;
                if (n >= need) {
                    flag(zoneKey((int) centre.x >> 4, (int) centre.z >> 4),
                         n + "x " + en.getKey().getName().getString());
                    break;
                }
            }
        }
    }

    private int amethystStage(String path) {
        return switch (path) {
            case "budding_amethyst" -> 0;
            case "small_amethyst_bud" -> 1;
            case "medium_amethyst_bud" -> 2;
            case "large_amethyst_bud" -> 3;
            case "amethyst_cluster" -> 4;
            case "amethyst_block" -> 5;
            default -> -1;
        };
    }

    @EventHandler
    private void onAmethystTick(TickEvent.Post event) {
        if (!amethystHighlight.get() || mc.world == null || mc.player == null) { amethyst.clear(); return; }
        if (amethystTick++ % Math.max(1, amethystScanTicks.get()) != 0) return;

        amethyst.clear();
        int r = amethystRange.get();
        BlockPos me = mc.player.getBlockPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r; dy++) {
            int x = me.getX() + dx, y = me.getY() + dy, z = me.getZ() + dz;
            var st = mc.world.getBlockState(m.set(x, y, z));
            if (st.isAir()) continue;
            // matched with the supplied AmethystESP check so every module agrees what counts
            if (!shama.addon.util.AmethystScan.isAmethystLike(st)) continue;
            int stage = amethystStage(shama.addon.util.BlockPaths.of(st.getBlock()));
            if (stage < 0) continue;
            if (stage == 0 && !amethystBudding.get()) continue;
            if (stage == 5 && !amethystBlocks.get()) continue;
            amethyst.add(new int[]{x, y, z, stage});
        }
    }

    @EventHandler
    private void onAmethystRender(Render3DEvent event) {
        if (!amethystHighlight.get() || amethyst.isEmpty()) return;
        for (int[] a : amethyst) {
            SettingColor c = switch (a[3]) {
                case 0 -> amBudding.get();
                case 1 -> amSmall.get();
                case 2 -> amMedium.get();
                case 3 -> amLarge.get();
                case 4 -> amGrown.get();
                default -> amBlock.get();
            };
            Color line = new Color(c.r, c.g, c.b, c.a);
            Color fill = new Color(c.r, c.g, c.b, Math.min(120, c.a / 3));
            event.renderer.box(a[0], a[1], a[2], a[0] + 1, a[1] + 1, a[2] + 1, fill, line, ShapeMode.Both, 0);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        // merged activity-finder: fading boxes on raw update positions
        if (rawPositions.get() && !rawHits.isEmpty()) {
            SettingColor rc = rawColor.get();
            for (var en : rawHits.entrySet()) {
                BlockPos bp = BlockPos.fromLong(en.getKey());
                float f = en.getValue() / (float) Math.max(1, fadeTicks.get());
                Color line = new Color(rc.r, rc.g, rc.b, (int) (rc.a * f));
                Color fill = new Color(rc.r, rc.g, rc.b, (int) (40 * f));
                event.renderer.box(bp.getX(), bp.getY(), bp.getZ(), bp.getX() + 1, bp.getY() + 1, bp.getZ() + 1, fill, line, ShapeMode.Both, 0);
            }
        }
        if (flagged.isEmpty() || mc.player == null || mc.world == null) return;
        double maxSq = renderDistance.get() * renderDistance.get();
        Color line = lineColor.get(), fill = fillColor.get();
        for (Long key : flagged.keySet()) {
            ChunkPos cp = new ChunkPos(key);
            double x0 = cp.x * 16, z0 = cp.z * 16;
            double dx = x0 + 8 - mc.player.getX(), dz = z0 + 8 - mc.player.getZ();
            if (dx * dx + dz * dz > maxSq) continue;
            event.renderer.box(x0, mc.world.getBottomY(), z0, x0 + 16, yLevel.get(), z0 + 16, fill, line, ShapeMode.Both, 0);
        }
        // locked zones stay drawn even once the activity stops and the flag expires
        if (baseLock.get()) {
            SettingColor lc = lockedColor.get();
            Color ll = new Color(lc.r, lc.g, lc.b, 220);
            for (long key : lockedZones) {
                ChunkPos cp = new ChunkPos(key);
                double x0 = cp.getStartX(), z0 = cp.getStartZ();
                int n = Math.max(1, zoneSize.get());
                event.renderer.box(x0, mc.world.getBottomY(), z0, x0 + 16 * n, yLevel.get(), z0 + 16 * n,
                    lc, ll, ShapeMode.Both, 0);
            }
        }
    }

    @Override public String getInfoString() { return flagged.isEmpty() ? null : flagged.size() + " chunks"; }
}
