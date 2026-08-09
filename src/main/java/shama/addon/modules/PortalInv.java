package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * Portal Inv — lets you open and use your inventory during nether/end portal transit.
 * Normally the "Downloading terrain" screen locks you out while the destination loads;
 * with this on, pressing your inventory key during that screen opens the survival
 * inventory over it (the load keeps running in the background), or it opens automatically
 * if auto-open is enabled. Handy for re-gearing / totem swaps the instant you arrive.
 */
public class PortalInv extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> autoOpen = sg.add(new BoolSetting.Builder()
        .name("auto-open")
        .description("Open the inventory the moment portal transit starts, without pressing a key.")
        .defaultValue(false)
        .build()
    );

    public PortalInv() {
        super(shama.addon.ShamaAddon.PLAYER, "portal-inv++", "Access your inventory during portal transit loading.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        boolean transit = mc.currentScreen != null && mc.currentScreen.getClass().getSimpleName().contains("DownloadingTerrain"); // terrain-load lockout (name-checked so mapping changes can't break the build)
        boolean inPortal = mc.currentScreen == null && inNetherPortal();          // purple portal view, nothing open yet
        if (!transit && !inPortal) return;
        if (autoOpen.get() || invKeyDown()) {
            mc.setScreen(new InventoryScreen(mc.player));
        }
    }

    /** True when the player is standing inside a nether portal block (the purple-view state). */
    private boolean inNetherPortal() {
        if (mc.world == null || mc.player == null) return false;
        if (mc.world.getBlockState(mc.player.getBlockPos()).isOf(Blocks.NETHER_PORTAL)) return true;
        BlockPos eye = BlockPos.ofFloored(mc.player.getEyePos());
        return mc.world.getBlockState(eye).isOf(Blocks.NETHER_PORTAL);
    }

    private boolean invKeyDown() {
        try {
            int code = InputUtil.fromTranslationKey(mc.options.inventoryKey.getBoundKeyTranslationKey()).getCode();
            return GLFW.glfwGetKey(mc.getWindow().getHandle(), code) == GLFW.GLFW_PRESS;
        } catch (Exception e) {
            return false;
        }
    }
}
