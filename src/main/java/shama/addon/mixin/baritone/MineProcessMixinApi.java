package shama.addon.mixin.baritone;

/*
 * baritone-api variant of the MineProcess hook (obfuscated builds).
 * Ported from Nora Tweaks. @Pseudo + non-required so it's skipped cleanly when
 * the target class isn't present.
 */

import baritone.api.utils.BlockOptionalMetaLookup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
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

@Pseudo
@Mixin(targets = {"baritone.em", "baritone.ek"}, remap = false, priority = 2000)
public class MineProcessMixinApi {
    @Unique private static Field cachedField;
    @Unique private static boolean fieldLookupAttempted = false;
    @Unique private static boolean isMineProcess = false;
    @Unique private static boolean isMineProcessChecked = false;

    @Inject(
        method = "a(Ljava/util/List;Lbaritone/ca;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void onRescan(CallbackInfo ci) {
        if (!checkIsMineProcess()) return;

        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) return;

        List<BlockPos> goals = oreSim.getBaritoneGoals();
        if (goals.isEmpty()) return;

        if (setKnownOreLocations(this, goals)) ci.cancel();
    }

    @Unique
    private boolean checkIsMineProcess() {
        if (isMineProcessChecked) return isMineProcess;
        isMineProcessChecked = true;

        for (Class<?> iface : this.getClass().getInterfaces()) {
            if (iface.getName().contains("MineProcess") || iface.getSimpleName().equals("cw")) {
                isMineProcess = true;
                return true;
            }
        }
        Class<?> parent = this.getClass().getSuperclass();
        while (parent != null && parent != Object.class) {
            for (Class<?> iface : parent.getInterfaces()) {
                if (iface.getName().contains("MineProcess")) {
                    isMineProcess = true;
                    return true;
                }
            }
            parent = parent.getSuperclass();
        }
        return false;
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
