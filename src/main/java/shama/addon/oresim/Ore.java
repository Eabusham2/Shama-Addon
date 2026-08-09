package shama.addon.oresim;

/*
 * Ore registry reader — ported to Yarn mappings from Meteor Rejects' Ore.java.
 *   Original (GPL-3.0): https://github.com/AntiCope/meteor-rejects
 *   Rejects ported it from Atomic: https://gitlab.com/0x151/atomic
 * This reads the REAL ore placement configs from the vanilla registry, so each
 * ore's index/step/count/height/rarity/size match actual generation. That's
 * what makes the sim accurate instead of an approximation.
 */

import shama.addon.mixin.CountPlacementModifierAccessor;
import shama.addon.mixin.HeightRangePlacementModifierAccessor;
import shama.addon.mixin.RarityFilterPlacementModifierAccessor;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.Dimension;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.intprovider.IntProvider;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.gen.HeightContext;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.feature.util.PlacedFeatureIndexer;
import net.minecraft.world.gen.feature.FeatureConfig;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.OrePlacedFeatures;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.heightprovider.HeightProvider;
import net.minecraft.world.gen.placementmodifier.CountPlacementModifier;
import net.minecraft.world.gen.placementmodifier.HeightRangePlacementModifier;
import net.minecraft.world.gen.placementmodifier.PlacementModifier;
import net.minecraft.world.gen.placementmodifier.RarityFilterPlacementModifier;

import java.util.*;

public class Ore {

    private static final Setting<Boolean> coal     = new BoolSetting.Builder().name("Coal").build();
    private static final Setting<Boolean> iron     = new BoolSetting.Builder().name("Iron").build();
    private static final Setting<Boolean> gold     = new BoolSetting.Builder().name("Gold").build();
    private static final Setting<Boolean> redstone = new BoolSetting.Builder().name("Redstone").build();
    private static final Setting<Boolean> diamond  = new BoolSetting.Builder().name("Diamond").build();
    private static final Setting<Boolean> lapis    = new BoolSetting.Builder().name("Lapis").build();
    private static final Setting<Boolean> copper   = new BoolSetting.Builder().name("Copper").build();
    private static final Setting<Boolean> emerald  = new BoolSetting.Builder().name("Emerald").build();
    private static final Setting<Boolean> quartz   = new BoolSetting.Builder().name("Quartz").build();
    private static final Setting<Boolean> debris   = new BoolSetting.Builder().name("Ancient Debris").build();
    public static final List<Setting<Boolean>> oreSettings = new ArrayList<>(Arrays.asList(
        coal, iron, gold, redstone, diamond, lapis, copper, emerald, quartz, debris));

    public static Map<RegistryKey<Biome>, List<Ore>> getRegistry(Dimension dimension) {
        // Use the LIVE world registries. registry.get(key) returns the value
        // directly, sidestepping RegistryEntry.value() entirely.
        var world = MinecraftClient.getInstance().world;
        if (world == null) return null;
        var drm = world.getRegistryManager();
        // Servers (SMP/anarchy like DonutSMP) usually DON'T sync the worldgen
        // placed_feature registry to the client. If it's missing, return null so
        // the caller can fall back, instead of throwing inside the game-join
        // handler (which disconnects the client and locks it out of the server).
        net.minecraft.registry.Registry<PlacedFeature> placedReg;
        net.minecraft.registry.Registry<Biome> biomeReg;
        try {
            placedReg = drm.getOrThrow(RegistryKeys.PLACED_FEATURE);
            biomeReg = drm.getOrThrow(RegistryKeys.BIOME);
        } catch (Exception e) {
            return null;
        }

        // Biome VALUES (not entries) so the indexer function needs no .value().
        List<Biome> biomeList = new ArrayList<>();
        for (Biome b : biomeReg) biomeList.add(b);
        return build(biomeList, b -> biomeReg.getKey(b).orElse(null), key -> placedReg.get(key), dimension);
    }

    // ---- Source B: rebuild vanilla worldgen locally on the client ----
    // Lets Current stay seed-exact even on servers that don't share the
    // placed_feature registry (DonutSMP etc.), like Nora/Rejects do. Wrapped so
    // any vanilla-API mismatch returns null (caller falls back) instead of crashing.
    private static RegistryWrapper.WrapperLookup VANILLA;

    /** Find the vanilla worldgen wrapper reflectively — the class name/location has
     *  moved between versions, so try the known ones and return null (caller falls
     *  back to the server registry / hardcoded path) if none are present. */
    private static RegistryWrapper.WrapperLookup loadVanilla() {
        // 1.21.11 (verified): BuiltinRegistries.createWrapperLookup() builds the full
        // builtin registry set (biomes + placed features) used for ore prediction.
        // Direct call so Loom remaps it; the server-registry path is the fallback if
        // this ever throws.
        try {
            return BuiltinRegistries.createWrapperLookup();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Map<RegistryKey<Biome>, List<Ore>> getRegistryLocal(Dimension dimension) {
        try {
            if (VANILLA == null) VANILLA = loadVanilla();
            if (VANILLA == null) return null;
            RegistryWrapper.Impl<Biome> biomeW = VANILLA.getOrThrow(RegistryKeys.BIOME);
            RegistryWrapper.Impl<PlacedFeature> placedW = VANILLA.getOrThrow(RegistryKeys.PLACED_FEATURE);

            List<Biome> biomeList = new ArrayList<>();
            Map<Biome, RegistryKey<Biome>> keyMap = new HashMap<>();
            biomeW.streamEntries().forEach(ref -> {
                Object v = entryValue(ref, Biome.class);
                if (v instanceof Biome b) { biomeList.add(b); keyMap.put(b, ref.registryKey()); }
            });
            if (biomeList.isEmpty()) return null;

            java.util.function.Function<RegistryKey<PlacedFeature>, PlacedFeature> placedGet = key -> {
                if (key == null) return null;
                try {
                    Object v = entryValue(placedW.getOrThrow(key), PlacedFeature.class);
                    return (v instanceof PlacedFeature pf) ? pf : null;
                } catch (Exception e) { return null; }
            };
            return build(biomeList, keyMap::get, placedGet, dimension);
        } catch (Throwable t) {
            return null; // any vanilla-API mismatch -> caller falls back, never crash
        }
    }

    // ---- Version + source dispatch (called by OreSim) ----
    public static Map<RegistryKey<Biome>, List<Ore>> getRegistry(Dimension dimension, OreVersion version, boolean useServerRegistry) {
        if (version == OreVersion.CURRENT) {
            Map<RegistryKey<Biome>, List<Ore>> primary = useServerRegistry ? getRegistry(dimension) : getRegistryLocal(dimension);
            if (primary != null && !primary.isEmpty()) return primary;
            // chosen source failed -> try the other, then the bundled modern table.
            Map<RegistryKey<Biome>, List<Ore>> secondary = useServerRegistry ? getRegistryLocal(dimension) : getRegistry(dimension);
            if (secondary != null && !secondary.isEmpty()) return secondary;
            return buildHardcoded(dimension, OreVersion.V1_20);
        }
        return buildHardcoded(dimension, version);
    }

    // ---- Shared builder: biomes + key/value lookups -> biome→ore map ----
    private static Map<RegistryKey<Biome>, List<Ore>> build(
            List<Biome> biomeList,
            java.util.function.Function<Biome, RegistryKey<Biome>> biomeKeyFn,
            java.util.function.Function<RegistryKey<PlacedFeature>, PlacedFeature> placedGet,
            Dimension dimension) {

        List<PlacedFeatureIndexer.IndexedFeatures> indexer = PlacedFeatureIndexer.collectIndexedFeatures(
            biomeList,
            biome -> biome.getGenerationSettings().getFeatures(),
            true
        );

        Map<PlacedFeature, Ore> featureToOre = new HashMap<>();
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_COAL_LOWER, 6, coal, new Color(47, 44, 54));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_COAL_UPPER, 6, coal, new Color(47, 44, 54));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_IRON_MIDDLE, 6, iron, new Color(236, 173, 119));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_IRON_SMALL, 6, iron, new Color(236, 173, 119));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_IRON_UPPER, 6, iron, new Color(236, 173, 119));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_GOLD, 6, gold, new Color(247, 229, 30));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_GOLD_LOWER, 6, gold, new Color(247, 229, 30));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_GOLD_EXTRA, 6, gold, new Color(247, 229, 30));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_GOLD_NETHER, 7, gold, new Color(247, 229, 30));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_GOLD_DELTAS, 7, gold, new Color(247, 229, 30));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_REDSTONE, 6, redstone, new Color(245, 7, 23));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_REDSTONE_LOWER, 6, redstone, new Color(245, 7, 23));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_DIAMOND, 6, diamond, new Color(33, 244, 255));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_DIAMOND_BURIED, 6, diamond, new Color(33, 244, 255));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_DIAMOND_LARGE, 6, diamond, new Color(33, 244, 255));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_DIAMOND_MEDIUM, 6, diamond, new Color(33, 244, 255));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_LAPIS, 6, lapis, new Color(8, 26, 189));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_LAPIS_BURIED, 6, lapis, new Color(8, 26, 189));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_COPPER, 6, copper, new Color(239, 151, 0));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_COPPER_LARGE, 6, copper, new Color(239, 151, 0));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_EMERALD, 6, emerald, new Color(27, 209, 45));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_QUARTZ_NETHER, 7, quartz, new Color(205, 205, 205));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_QUARTZ_DELTAS, 7, quartz, new Color(205, 205, 205));
        reg(featureToOre, indexer, placedGet, OrePlacedFeatures.ORE_DEBRIS_SMALL, 7, debris, new Color(209, 27, 245));

        Map<RegistryKey<Biome>, List<Ore>> biomeOreMap = new HashMap<>();
        for (Biome biome : biomeList) {
            RegistryKey<Biome> key = biomeKeyFn.apply(biome);
            if (key == null) continue;
            List<Ore> list = new ArrayList<>();
            for (var stepFeatures : biome.getGenerationSettings().getFeatures()) {
                for (var entry : stepFeatures) {
                    PlacedFeature pf = placedGet.apply(entry.getKey().orElse(null));
                    if (pf != null && featureToOre.containsKey(pf)) list.add(featureToOre.get(pf));
                }
            }
            biomeOreMap.put(key, list);
        }
        return biomeOreMap;
    }

    private static void reg(
        Map<PlacedFeature, Ore> map,
        List<PlacedFeatureIndexer.IndexedFeatures> indexer,
        java.util.function.Function<RegistryKey<PlacedFeature>, PlacedFeature> placedGet,
        RegistryKey<PlacedFeature> oreKey,
        int genStep,
        Setting<Boolean> active,
        Color color
    ) {
        PlacedFeature orePlacement = placedGet.apply(oreKey);
        if (orePlacement == null) return;
        int index = indexOf(indexer.get(genStep), orePlacement);
        if (index < 0) return;
        map.put(orePlacement, new Ore(orePlacement, genStep, index, active, color));
    }

    // The value accessor on RegistryEntry is unnamed in this Yarn build (shows as an
    // intermediary comp_* name), so we can't call it directly. Match it by RESULT
    // instead of by name: invoke each no-arg method and keep the one that actually
    // returns the value type. Name-independent and erasure-proof, so it survives Loom
    // remapping (same philosophy as indexOf() below).
    private static Object entryValue(Object entry, Class<?> valueType) {
        if (entry == null) return null;
        for (java.lang.reflect.Method m : entry.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) continue;
            try {
                Object r = m.invoke(entry);
                if (valueType.isInstance(r)) return r;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // The IndexedFeatures record's accessor names are remapped in this build, so
    // read its List<PlacedFeature> component by reflection and find the index.
    @SuppressWarnings("unchecked")
    private static int indexOf(PlacedFeatureIndexer.IndexedFeatures indexed, PlacedFeature target) {
        try {
            for (java.lang.reflect.Field f : indexed.getClass().getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    List<PlacedFeature> list = (List<PlacedFeature>) f.get(indexed);
                    int i = list.indexOf(target);
                    if (i >= 0) return i;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    public int step;
    public int index;
    public Setting<Boolean> active;
    public IntProvider count = ConstantIntProvider.create(1);
    public HeightProvider heightProvider;
    public HeightContext heightContext;
    public float rarity = 1;
    public float discardOnAirChance;
    public int size;
    public Color color;
    public boolean scattered;

    // --- Hardcoded (older-version) path ---
    public boolean hardcoded = false;   // true => use sampleY() instead of heightProvider
    public int hcMinY;
    public int hcMaxY;
    public boolean hcTrapezoid;          // true = triangle/trapezoid bias toward center, false = uniform

    /** Build an ore from explicit constants (older-version path). Height is sampled
     *  by sampleY() with plain math, so no Yarn HeightProvider/HeightContext is built. */
    public Ore(int step, int index, Setting<Boolean> active, Color color,
               int count, int minY, int maxY, boolean trapezoid,
               int size, float discardOnAir, float rarity, boolean scattered) {
        this.step = step;
        this.index = index;
        this.active = active;
        this.color = color;
        this.count = ConstantIntProvider.create(count);
        this.hcMinY = minY;
        this.hcMaxY = maxY;
        this.hcTrapezoid = trapezoid;
        this.size = size;
        this.discardOnAirChance = discardOnAir;
        this.rarity = rarity;
        this.scattered = scattered;
        this.hardcoded = true;
    }

    /** Sample a Y for the hardcoded path. Approximate (matches the old version's
     *  approach); the registry-accurate CURRENT path uses the real heightProvider. */
    public int sampleY(net.minecraft.util.math.random.ChunkRandom random) {
        int span = Math.max(0, hcMaxY - hcMinY);
        if (hcTrapezoid) {
            return hcMinY + (random.nextInt(span + 1) + random.nextInt(span + 1)) / 2;
        }
        return hcMinY + random.nextInt(span + 1);
    }

    private Ore(PlacedFeature feature, int step, int index, Setting<Boolean> active, Color color) {
        this.step = step;
        this.index = index;
        this.active = active;
        this.color = color;

        var world = MinecraftClient.getInstance().world;
        int bottom = world.getBottomY();
        int top = world.getTopYInclusive();
        HeightLimitView view = HeightLimitView.create(bottom, top - bottom + 1);
        // HeightContext only reads min/height from the view; generator can be null.
        this.heightContext = new HeightContext(null, view);

        for (PlacementModifier modifier : feature.placementModifiers()) {
            if (modifier instanceof CountPlacementModifier) {
                this.count = ((CountPlacementModifierAccessor) modifier).getCount();
            } else if (modifier instanceof HeightRangePlacementModifier) {
                this.heightProvider = ((HeightRangePlacementModifierAccessor) modifier).getHeight();
            } else if (modifier instanceof RarityFilterPlacementModifier) {
                this.rarity = ((RarityFilterPlacementModifierAccessor) modifier).getChance();
            }
        }

        // Read the OreFeatureConfig via the decorated-feature stream (avoids the
        // renamed RegistryEntry.value()).
        var configured = feature.getDecoratedFeatures().findFirst().orElse(null);
        FeatureConfig featureConfig = configured == null ? null : configured.config();
        if (featureConfig instanceof OreFeatureConfig ore) {
            this.discardOnAirChance = ore.discardOnAirChance;
            this.size = ore.size;
        } else {
            throw new IllegalStateException("config for " + feature + " is not OreFeatureConfig");
        }
    }

    // ==================== Older-version (hardcoded) path ====================

    public static Map<RegistryKey<Biome>, List<Ore>> getRegistry(Dimension dimension, OreVersion version) {
        return getRegistry(dimension, version, false);
    }

    private static Map<RegistryKey<Biome>, List<Ore>> buildHardcoded(Dimension dim, OreVersion ver) {
        Map<RegistryKey<Biome>, List<Ore>> map = new HashMap<>();

        // One real biome key as a single bucket. OreSim.getOresForBiome falls back
        // to this list for every biome, so these ores apply everywhere (approx).
        var world = MinecraftClient.getInstance().world;
        if (world == null) return map;
        net.minecraft.registry.Registry<Biome> biomeReg;
        try { biomeReg = world.getRegistryManager().getOrThrow(RegistryKeys.BIOME); }
        catch (Exception e) { return map; }
        RegistryKey<Biome> bucket = null;
        for (Biome b : biomeReg) { bucket = biomeReg.getKey(b).orElse(null); if (bucket != null) break; }
        if (bucket == null) return map;

        List<Ore> ores = new ArrayList<>();
        boolean nether = dim == Dimension.Nether;
        boolean end = dim == Dimension.End;
        if (end) { map.put(bucket, ores); return map; }

        int s = 6;   // UNDERGROUND_ORES step
        int[] i = {0}; // sequential index (approximate; not seed-exact for old versions)

        if (ver.era == OreVersion.Era.NEW) {
            if (nether) {
                ores.add(new Ore(s, i[0]++, gold,    new Color(247,229,30),  10, 10, 117, false, 9,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, quartz,  new Color(205,205,205), 16, 10, 117, false, 14, 0f,   1f, false));
                ores.add(new Ore(s, i[0]++, debris,  new Color(209,27,245),  1,  8,  24,  false, 3,  0f,   1f, true));
            } else {
                ores.add(new Ore(s, i[0]++, coal,     new Color(47,44,54),   20,  0,  192, true,  17, 0.7f, 1f, false));
                ores.add(new Ore(s, i[0]++, copper,   new Color(239,151,0),  16, -16, 112, true,  10, 0f,   1f, false));
                ores.add(new Ore(s, i[0]++, iron,     new Color(236,173,119),10, -24, 56,  true,  9,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, iron,     new Color(236,173,119),10, -64, 72,  false, 9,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, gold,     new Color(247,229,30),  4, -64, 32,  true,  9,  0.5f, 1f, false));
                ores.add(new Ore(s, i[0]++, redstone, new Color(245,7,23),    4, -64, 15,  false, 8,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, redstone, new Color(245,7,23),    4, -64, -32, true,  8,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, diamond,  new Color(33,244,255),  7, -64, 16,  false, 8,  0.7f, 1f, false));
                ores.add(new Ore(s, i[0]++, lapis,    new Color(8,26,189),    2, -32, 32,  true,  7,  0f,   1f, false));
                ores.add(new Ore(s, i[0]++, lapis,    new Color(8,26,189),    4, -64, 64,  false, 7,  0f,   1f, true));
                ores.add(new Ore(s, i[0]++, emerald,  new Color(27,209,45),   50, -16, 256, false, 3,  0f,   1f, true));
            }
        } else { // OLD distribution (<= 1.17)
            if (nether) {
                ores.add(new Ore(s, i[0]++, quartz,  new Color(205,205,205), 16, 10, 117, false, 14, 0f, 1f, false));
                ores.add(new Ore(s, i[0]++, gold,    new Color(247,229,30),  2,  10, 117, false, 9,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, debris,  new Color(209,27,245),  1,  8,  22,  false, 3,  0f, 1f, true));
            } else {
                ores.add(new Ore(s, i[0]++, coal,     new Color(47,44,54),   20, 0,  127, false, 17, 0f, 1f, false));
                ores.add(new Ore(s, i[0]++, iron,     new Color(236,173,119),20, 0,  63,  false, 9,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, gold,     new Color(247,229,30), 2,  0,  31,  false, 9,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, redstone, new Color(245,7,23),   8,  0,  15,  false, 8,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, diamond,  new Color(33,244,255), 1,  0,  15,  false, 8,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, lapis,    new Color(8,26,189),   1,  0,  30,  true,  7,  0f, 1f, false));
                ores.add(new Ore(s, i[0]++, emerald,  new Color(27,209,45),  11, 4,  31,  false, 1,  0f, 1f, true));
            }
        }

        map.put(bucket, ores);
        return map;
    }
}
