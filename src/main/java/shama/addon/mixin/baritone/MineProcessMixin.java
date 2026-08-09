package shama.addon.mixin.baritone;

/*
 * Baritone MineProcess hook — ported from Nora Tweaks' reflection-based mixin
 * (itself derived from Meteor Rejects, GPL-3.0). Injects OreSim++'s simulated
 * ore positions into Baritone's mining so it paths to ores it can't see.
 *
 * Reflection finds the knownOreLocations field instead of @Shadow-ing an
 * obfuscated name, so it works across baritone-unoptimized / -meteor / -api.
 * Lives in a NON-REQUIRED mixin config so it silently no-ops when Baritone is
 * absent or shaped differently — it can never crash the game on launch.
 */

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import shama.addon.modules.OreSim;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

@Mixin(targets = "baritone.process.MineProcess", remap = false, priority = 2000)
public class MineProcessMixin {
    @Unique private static Field cachedField;
    @Unique private static boolean fieldLookupAttempted = false;

    @Inject(
        method = {"rescan", "a(Ljava/util/List;Lbaritone/pathing/movement/CalculationContext;)V"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void onRescan(List<BlockPos> already, CalculationContext context, CallbackInfo ci) {
        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) return;

        List<BlockPos> goals = oreSim.getBaritoneGoals();
        if (goals.isEmpty()) return;

        if (setKnownOreLocations(this, goals)) ci.cancel();
    }

    @Unique
    private static boolean setKnownOreLocations(Object instance, List<BlockPos> locations) {
        try {
            Field field = getKnownOreLocationsField(instance.getClass());
            if (field != null) {
                field.set(instance, locations);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Unique
    private static Field getKnownOreLocationsField(Class<?> clazz) {
        if (fieldLookupAttempted) return cachedField;
        fieldLookupAttempted = true;

        try {
            cachedField = clazz.getDeclaredField("knownOreLocations");
            cachedField.setAccessible(true);
            return cachedField;
        } catch (NoSuchFieldException ignored) {}

        Field firstList = null, firstListBlockPos = null;
        for (Field field : clazz.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                if (firstList == null) { firstList = field; firstList.setAccessible(true); }
                Type gt = field.getGenericType();
                if (gt instanceof ParameterizedType pt) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0) {
                        String tn = args[0].getTypeName();
                        if (tn.contains("BlockPos") || tn.contains("class_2338")) {
                            firstListBlockPos = field;
                            firstListBlockPos.setAccessible(true);
                            break;
                        }
                    }
                }
            }
        }
        cachedField = firstListBlockPos != null ? firstListBlockPos : firstList;
        return cachedField;
    }

    @Redirect(
        method = "*",
        at = @At(value = "INVOKE", target = "Lbaritone/api/utils/BlockOptionalMetaLookup;has(Lnet/minecraft/world/level/block/state/BlockState;)Z"),
        remap = false,
        require = 0
    )
    private static boolean onPruneStream(BlockOptionalMetaLookup instance, BlockState blockState) {
        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) return instance.has(blockState);
        return !blockState.isAir();
    }
}
