package shama.addon.modules;

/*
 * OreSim++ — ore-from-seed simulator.
 * Ported to Yarn mappings from Meteor Rejects (GPL-3.0):
 *   https://github.com/AntiCope/meteor-rejects
 * which itself ported it from Atomic: https://gitlab.com/0x151/atomic
 * The "Mojang code" vein section is Minecraft's own OreFeature placement,
 * translated to Yarn. Block selection, rendering, and the registry-accurate
 * approach all follow the rejects/Nora Tweaks design.
 */

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import shama.addon.oresim.Ore;
import shama.addon.oresim.OreVersion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OreSim extends Module {
    private final Map<Long, Map<Ore, Set<Vec3d>>> chunkRenderers = new ConcurrentHashMap<>();
    private Long worldSeed = null;
    private Map<RegistryKey<Biome>, List<Ore>> oreConfig;

    public enum AirCheck { ON_LOAD, RECHECK, OFF }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<OreVersion> version = sgGeneral.add(new EnumSetting.Builder<OreVersion>()
        .name("mc-version")
        .description("Which Minecraft version's ore generation to simulate. Current = the running game (1.21.x), registry-accurate like Nora/Rejects. Older versions restore the cross-version pick; validate them in singleplayer.")
        .defaultValue(OreVersion.CURRENT)
        .build());

    private final Setting<Boolean> useServerRegistry = sgGeneral.add(new BoolSetting.Builder()
        .name("use-server-registry")
        .description("Off (default): rebuild vanilla ore generation locally on the client, so Current stays seed-exact even on servers that don't share worldgen data (DonutSMP etc.). On: read the server's synced registry instead (only useful on modded servers with custom ore placement).")
        .defaultValue(false)
        .visible(() -> version.get() == OreVersion.CURRENT)
        .build());

    private final Setting<Boolean> autoSeed = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-seed")
        .description("Use the current singleplayer world's seed automatically. Off = type a seed (for servers).")
        .defaultValue(true)
        .build());

    private final Setting<String> seedText = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("World seed (numbers or text). Used when auto-seed is off.")
        .defaultValue("")
        .visible(() -> !autoSeed.get())
        .build());

    private final Setting<Integer> horizontalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Range of chunks to render around you.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 16)
        .build());

    private final Setting<AirCheck> airCheck = sgGeneral.add(new EnumSetting.Builder<AirCheck>()
        .name("air-check-mode")
        .description("Checks for air at simulated ore positions to drop exposed ones.")
        .defaultValue(AirCheck.RECHECK)
        .build());

    private final Setting<Integer> recheckInterval = sgGeneral.add(new IntSetting.Builder()
        .name("recheck-interval")
        .description("Ticks between line-of-sight rechecks. On these, a box on a block you can actually see (in your FOV, with an exposed face and no obstruction) that isn't ore gets removed. Mined ores clear every tick regardless. 20 ticks = 1s.")
        .defaultValue(200)
        .min(20)
        .sliderRange(20, 600)
        .visible(() -> airCheck.get() == AirCheck.RECHECK)
        .build());

    private final Setting<Boolean> baritone = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone")
        .description("Expose the simulated ore positions as Baritone mining goals so #mine / Baritone can path to them. Requires Baritone (built into Meteor).")
        .defaultValue(false)
        .build());

    // ---- Render ----
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> colorByOre = sgRender.add(new BoolSetting.Builder()
        .name("color-by-ore")
        .description("Color-code each box by ore type (diamond cyan, gold yellow, redstone red, etc.). Off = use the single fill/line colors below for every ore.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Draw outlines, filled sides, or both.")
        .defaultValue(ShapeMode.Lines)
        .build());

    private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Fill opacity used when color-by-ore is on (and shape-mode includes sides).")
        .defaultValue(40)
        .range(0, 255)
        .sliderRange(0, 255)
        .visible(colorByOre::get)
        .build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Box fill color (used when color-by-ore is off).")
        .defaultValue(new SettingColor(255, 255, 255, 40))
        .visible(() -> !colorByOre.get())
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Box outline color (used when color-by-ore is off).")
        .defaultValue(new SettingColor(255, 255, 255, 200))
        .visible(() -> !colorByOre.get())
        .build());

        private final Setting<Integer> glow = sgGeneral.add(new IntSetting.Builder()
        .name("glow")
        .description("How strongly each find glows through terrain. 0 is off; higher makes the outline brighter and more solid so it stands out at distance.")
        .defaultValue(0).min(0).max(100).sliderRange(0, 100).build());

    // Sorted list of simulated ore positions, refreshed each tick when baritone
    // is on. Public so a MineProcess hook (or Baritone command) can consume it.
    public final List<BlockPos> oreGoals = new ArrayList<>();

    // Counts ticks so the expensive line-of-sight pass runs only every recheck-interval.
    private int validateCounter = 0;

    public OreSim() {
        super(shama.addon.ShamaAddon.HUNT, "ore-sim++", "Predicts where ores are from the world seed, accurate to vanilla generation. Enter a seed or let it detect one.");
        SettingGroup sgOres = settings.createGroup("Ores");
        Ore.oreSettings.forEach(sgOres::add);
    }

    private long resolveSeed() {
        if (autoSeed.get()) {
            if (mc.getServer() != null && mc.getServer().getOverworld() != null)
                return mc.getServer().getOverworld().getSeed();
            return 0;
        }
        String s = seedText.get().trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return s.hashCode(); }
    }

    public boolean baritone() {
        return isActive() && baritone.get() && meteordevelopment.meteorclient.pathing.BaritoneUtils.IS_AVAILABLE;
    }

    /** Snapshot of the current simulated ore goals, nearest first. Called by the
     *  Baritone MineProcess mixin to feed positions into auto-mining. */
    public List<BlockPos> getBaritoneGoals() {
        return new ArrayList<>(oreGoals);
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (!baritone() || mc.player == null || oreConfig == null) return;

        // Rebuild the goal list from currently-simulated chunks, nearest first.
        oreGoals.clear();
        for (Map<Ore, Set<Vec3d>> chunk : chunkRenderers.values()) {
            for (Map.Entry<Ore, Set<Vec3d>> entry : chunk.entrySet()) {
                if (!entry.getKey().active.get()) continue;
                for (Vec3d v : entry.getValue()) {
                    oreGoals.add(BlockPos.ofFloored(v.x, v.y, v.z));
                }
            }
        }
        BlockPos p = mc.player.getBlockPos();
        oreGoals.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(p)));
    }

    @Override
    public void onActivate() {
        reload();
    }

    @Override
    public void onDeactivate() {
        chunkRenderers.clear();
        oreConfig = null;
        oreGoals.clear();
    }

    private void reload() {
        try {
            long seed = resolveSeed();
            if (seed == 0) {
                error("No seed. Turn on auto-seed in singleplayer, or type a seed.");
                toggle();
                return;
            }
            worldSeed = seed;
            oreConfig = Ore.getRegistry(PlayerUtils.getDimension(), version.get(), useServerRegistry.get());
            if (oreConfig == null || oreConfig.isEmpty()) {
                oreConfig = null;
                error("No ore data available here. Pick a specific mc-version (those work off the biome registry).");
                if (isActive()) toggle();
                return;
            }
            chunkRenderers.clear();
            if (mc.world != null) {
                for (Chunk chunk : meteordevelopment.meteorclient.utils.Utils.chunks(false)) {
                    calculateChunk(chunk);
                }
            }
        } catch (Exception e) {
            // CRITICAL: onActivate() runs inside Meteor's game-join handler. An
            // uncaught exception here disconnects the client and locks it out of
            // the server. Swallow it, disable cleanly, and report instead.
            oreConfig = null;
            chunkRenderers.clear();
            error("OreSim couldn't load here; disabling. (" + e.getClass().getSimpleName() + ")");
            if (isActive()) toggle();
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        calculateChunk(event.chunk());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || oreConfig == null) return;

        int chunkX = mc.player.getChunkPos().x;
        int chunkZ = mc.player.getChunkPos().z;
        int rangeVal = horizontalRadius.get();

        for (int range = 0; range <= rangeVal; range++) {
            for (int x = -range + chunkX; x <= range + chunkX; x++)
                renderChunk(x, chunkZ + range - rangeVal, event);
            for (int x = -range + 1 + chunkX; x < range + chunkX; x++)
                renderChunk(x, chunkZ - range + rangeVal + 1, event);
        }
    }

    private void renderChunk(int x, int z, Render3DEvent event) {
        long key = ChunkPos.toLong(x, z);
        Map<Ore, Set<Vec3d>> chunk = chunkRenderers.get(key);
        if (chunk == null) return;
        ShapeMode mode = shapeMode.get();
        boolean perOre = colorByOre.get();
        for (Map.Entry<Ore, Set<Vec3d>> entry : chunk.entrySet()) {
            Ore ore = entry.getKey();
            if (!ore.active.get()) continue;
            Color side, line;
            if (perOre) {
                Color c = ore.color;
                line = new Color(c.r, c.g, c.b, 255);
                side = new Color(c.r, c.g, c.b, fillOpacity.get());
            } else {
                line = lineColor.get();
                side = fillColor.get();
            }
            for (Vec3d pos : entry.getValue()) {
                if ((glow.get() > 0)) {
                    Color halo = new Color(line.r, line.g, line.b, 45);
                    event.renderer.box(pos.x - 0.15, pos.y - 0.15, pos.z - 0.15,
                        pos.x + 1.15, pos.y + 1.15, pos.z + 1.15,
                        halo, new Color(0, 0, 0, 0), ShapeMode.Sides, 0);
                }
                event.renderer.box(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, side, line, mode, 0);
            }
        }
    }

    /** Air removal runs every tick (mining clears a box instantly). The heavier
     *  line-of-sight removal — a box on a block you can actually see (in your FOV,
     *  exposed face, unobstructed) that isn't ore — runs only every recheck-interval
     *  ticks. Blocks still buried in stone are kept; predicting unseen ore is the point.
     *  RECHECK mode only (default). Only loaded chunks near you are checked. */
    @EventHandler
    private void onValidateTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (airCheck.get() == AirCheck.OFF || mc.world == null || mc.player == null) return;
        ClientWorld w = mc.world;
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int r = horizontalRadius.get() + 1;

        boolean doRay = airCheck.get() == AirCheck.RECHECK
            && (validateCounter++ % Math.max(1, recheckInterval.get()) == 0);
        Vec3d eye = doRay ? mc.player.getEyePos() : null;
        Vec3d look = doRay ? mc.player.getRotationVec(1.0F) : null;

        for (Map.Entry<Long, Map<Ore, Set<Vec3d>>> chunkEntry : chunkRenderers.entrySet()) {
            ChunkPos cp = new ChunkPos(chunkEntry.getKey());
            if (Math.abs(cp.x - pcx) > r || Math.abs(cp.z - pcz) > r) continue;   // far away
            if (w.getChunk(cp.x, cp.z, ChunkStatus.FULL, false) == null) continue; // not loaded
            for (Set<Vec3d> positions : chunkEntry.getValue().values()) {
                positions.removeIf(p -> {
                    BlockPos bp = BlockPos.ofFloored(p.x, p.y, p.z);
                    net.minecraft.block.BlockState bs = w.getBlockState(bp);
                    if (bs.isAir()) return true;            // mined/empty -> remove (every tick)
                    if (!doRay) return false;               // visibility only on ray ticks
                    if (isOreBlock(bs)) return false;       // confirmed ore -> keep
                    if (!exposed(w, bp)) return false;      // no open face -> can't be seen -> keep
                    if (!inFov(eye, look, bp)) return false;// not in view -> keep
                    return rayVisible(w, eye, bp);          // in view & unobstructed & not ore -> remove
                });
            }
        }
    }

    private boolean isOreBlock(net.minecraft.block.BlockState bs) {
        String path = shama.addon.util.BlockPaths.of(bs.getBlock());
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private boolean exposed(ClientWorld w, BlockPos p) {
        for (Direction d : Direction.values())
            if (!w.getBlockState(p.offset(d)).isOpaqueFullCube()) return true;
        return false;
    }

    /** Block center is within ~the view cone (in front of the camera). */
    private boolean inFov(Vec3d eye, Vec3d look, BlockPos p) {
        Vec3d to = Vec3d.ofCenter(p).subtract(eye);
        if (to.lengthSquared() < 1.0E-4) return true;
        return to.normalize().dotProduct(look) > 0.4D;
    }

    /** A clear line of sight exists from the eye to the block (the first solid the
     *  ray hits is the block itself, not something in front of it). */
    private boolean rayVisible(ClientWorld w, Vec3d eye, BlockPos p) {
        net.minecraft.util.hit.BlockHitResult hit = w.raycast(new net.minecraft.world.RaycastContext(
            eye, Vec3d.ofCenter(p),
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            mc.player));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && hit.getBlockPos().equals(p);
    }

    private void calculateChunk(Chunk chunk) {
        if (chunk == null || mc.world == null || oreConfig == null || worldSeed == null) return;

        ChunkPos chunkPos = chunk.getPos();
        long key = chunkPos.toLong();
        if (chunkRenderers.containsKey(key)) return;

        ClientWorld world = mc.world;

        Set<RegistryKey<Biome>> biomeKeys = new HashSet<>();
        ChunkPos.stream(chunkPos, 1).forEach(pos -> {
            Chunk c = world.getChunk(pos.x, pos.z, ChunkStatus.BIOMES, false);
            if (c == null) return;
            for (ChunkSection section : c.getSectionArray())
                section.getBiomeContainer().forEachValue(b -> b.getKey().ifPresent(biomeKeys::add));
        });

        Set<Ore> ores = biomeKeys.stream()
            .flatMap(b -> getOresForBiome(b).stream())
            .collect(Collectors.toSet());

        int chunkX = chunkPos.x << 4;
        int chunkZ = chunkPos.z << 4;
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(0));
        long populationSeed = random.setPopulationSeed(worldSeed, chunkX, chunkZ);

        Map<Ore, Set<Vec3d>> orePositions = new HashMap<>();
        for (Ore ore : ores) {
            HashSet<Vec3d> positions = new HashSet<>();
            random.setDecoratorSeed(populationSeed, ore.index, ore.step);
            int repeat = ore.count.get(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1.0F && random.nextFloat() >= 1.0F / ore.rarity) continue;
                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.hardcoded ? ore.sampleY(random) : ore.heightProvider.get(random, ore.heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                RegistryKey<Biome> biome = chunk.getBiomeForNoiseGen(x, y, z).getKey().orElse(null);
                if (biome != null && !getOresForBiome(biome).contains(ore)) continue;

                if (ore.scattered) positions.addAll(generateHidden(world, random, origin, ore.size));
                else positions.addAll(generateNormal(world, random, origin, ore.size, ore.discardOnAirChance));
            }
            if (!positions.isEmpty()) orePositions.put(ore, positions);
        }
        if (!orePositions.isEmpty()) chunkRenderers.put(key, orePositions);
    }

    private List<Ore> getOresForBiome(RegistryKey<Biome> biomeKey) {
        if (oreConfig == null) return Collections.emptyList();
        List<Ore> ores = oreConfig.get(biomeKey);
        if (ores != null) return ores;
        return oreConfig.values().stream().findAny().orElse(Collections.emptyList());
    }

    // ==================== Mojang code (Yarn-mapped) ====================

    private List<Vec3d> generateNormal(ClientWorld world, ChunkRandom random, BlockPos blockPos, int veinSize, float discardOnAir) {
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) veinSize / 8.0F;
        int padding = MathHelper.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double startX = blockPos.getX() + Math.sin(angle) * spread;
        double endX = blockPos.getX() - Math.sin(angle) * spread;
        double startZ = blockPos.getZ() + Math.cos(angle) * spread;
        double endZ = blockPos.getZ() - Math.cos(angle) * spread;
        double startY = blockPos.getY() + random.nextInt(3) - 2;
        double endY = blockPos.getY() + random.nextInt(3) - 2;
        int minX = blockPos.getX() - MathHelper.ceil(spread) - padding;
        int minY = blockPos.getY() - 2 - padding;
        int minZ = blockPos.getZ() - MathHelper.ceil(spread) - padding;
        int sizeXZ = 2 * (MathHelper.ceil(spread) + padding);
        int sizeY = 2 * (2 + padding);

        for (int x = minX; x <= minX + sizeXZ; x++) {
            for (int z = minZ; z <= minZ + sizeXZ; z++) {
                if (minY <= world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)) {
                    return generateVein(world, random, veinSize, startX, endX, startZ, endZ, startY, endY, minX, minY, minZ, sizeXZ, sizeY, discardOnAir);
                }
            }
        }
        return new ArrayList<>();
    }

    private List<Vec3d> generateVein(ClientWorld world, ChunkRandom random, int veinSize, double startX, double endX, double startZ, double endZ, double startY, double endY, int minX, int minY, int minZ, int sizeXZ, int sizeY, float discardOnAir) {
        BitSet bitSet = new BitSet(sizeXZ * sizeY * sizeXZ);
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        double[] buf = new double[veinSize * 4];
        List<Vec3d> positions = new ArrayList<>();

        for (int i = 0; i < veinSize; i++) {
            float t = (float) i / (float) veinSize;
            double x = MathHelper.lerp(t, startX, endX);
            double y = MathHelper.lerp(t, startY, endY);
            double z = MathHelper.lerp(t, startZ, endZ);
            double scale = random.nextDouble() * veinSize / 16.0D;
            double radius = ((MathHelper.sin((float) Math.PI * t) + 1.0F) * scale + 1.0D) / 2.0D;
            buf[i * 4] = x; buf[i * 4 + 1] = y; buf[i * 4 + 2] = z; buf[i * 4 + 3] = radius;
        }

        for (int i = 0; i < veinSize - 1; i++) {
            if (buf[i * 4 + 3] <= 0.0D) continue;
            for (int j = i + 1; j < veinSize; j++) {
                if (buf[j * 4 + 3] <= 0.0D) continue;
                double dx = buf[i * 4] - buf[j * 4];
                double dy = buf[i * 4 + 1] - buf[j * 4 + 1];
                double dz = buf[i * 4 + 2] - buf[j * 4 + 2];
                double dr = buf[i * 4 + 3] - buf[j * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0D) buf[j * 4 + 3] = -1.0D; else buf[i * 4 + 3] = -1.0D;
                }
            }
        }

        for (int i = 0; i < veinSize; i++) {
            double radius = buf[i * 4 + 3];
            if (radius < 0.0D) continue;
            double cx = buf[i * 4], cy = buf[i * 4 + 1], cz = buf[i * 4 + 2];
            int x0 = Math.max(MathHelper.floor(cx - radius), minX);
            int y0 = Math.max(MathHelper.floor(cy - radius), minY);
            int z0 = Math.max(MathHelper.floor(cz - radius), minZ);
            int x1 = Math.max(MathHelper.floor(cx + radius), x0);
            int y1 = Math.max(MathHelper.floor(cy + radius), y0);
            int z1 = Math.max(MathHelper.floor(cz + radius), z0);

            for (int x = x0; x <= x1; x++) {
                double nx = ((double) x + 0.5D - cx) / radius;
                if (nx * nx >= 1.0D) continue;
                for (int y = y0; y <= y1; y++) {
                    double ny = ((double) y + 0.5D - cy) / radius;
                    if (nx * nx + ny * ny >= 1.0D) continue;
                    for (int z = z0; z <= z1; z++) {
                        double nz = ((double) z + 0.5D - cz) / radius;
                        if (nx * nx + ny * ny + nz * nz >= 1.0D) continue;
                        int idx = x - minX + (y - minY) * sizeXZ + (z - minZ) * sizeXZ * sizeY;
                        if (bitSet.get(idx)) continue;
                        bitSet.set(idx);
                        mutable.set(x, y, z);
                        if (y < world.getBottomY() || y > world.getBottomY() + world.getHeight()) continue;
                        if (airCheck.get() != AirCheck.OFF && !world.getBlockState(mutable).isOpaqueFullCube()) continue;
                        if (shouldPlace(world, mutable, discardOnAir, random)) positions.add(new Vec3d(x, y, z));
                    }
                }
            }
        }
        return positions;
    }

    private boolean shouldPlace(ClientWorld world, BlockPos pos, float discardOnAir, ChunkRandom random) {
        if (discardOnAir == 0 || (discardOnAir != 1.0F && random.nextFloat() >= discardOnAir)) return true;
        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(pos.offset(direction)).isOpaqueFullCube() && discardOnAir != 1.0F)
                return false;
        }
        return true;
    }

    private List<Vec3d> generateHidden(ClientWorld world, ChunkRandom random, BlockPos origin, int size) {
        List<Vec3d> positions = new ArrayList<>();
        int limit = random.nextInt(size + 1);
        for (int i = 0; i < limit; i++) {
            int range = Math.min(i, 7);
            int x = randomCoord(random, range) + origin.getX();
            int y = randomCoord(random, range) + origin.getY();
            int z = randomCoord(random, range) + origin.getZ();
            BlockPos pos = new BlockPos(x, y, z);
            if (airCheck.get() != AirCheck.OFF && !world.getBlockState(pos).isOpaqueFullCube()) continue;
            if (shouldPlace(world, pos, 1.0F, random)) positions.add(new Vec3d(x, y, z));
        }
        return positions;
    }

    private int randomCoord(ChunkRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * size);
    }

    @Override
    public String getInfoString() {
        int n = 0;
        for (var m : chunkRenderers.values()) for (var s : m.values()) n += s.size();
        return n + " ores";
    }
}
