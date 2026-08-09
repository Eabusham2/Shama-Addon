package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import shama.addon.mixin.LivingEntityAccessor;

/**
 * Fast Bow++ — charge bows/crossbows fast and spam shots.
 *
 * Honesty up front: a bow's power is decided by the SERVER from how many ticks it
 * counted you drawing. A client can't forge that count, so a truly-instant full-power
 * shot is only possible where we control the server:
 *
 *   Full    - auto-release the instant the draw is genuinely full. Legit timing, so it
 *             works on every server and always fires full power. (Default.)
 *   Instant - singleplayer/LAN-host: force the integrated server's player to full draw
 *             and release => a real instant full-power shot. On a remote server there's
 *             no server player to touch, so it just releases early: fast, but weak on
 *             anything that validates draw time (vanilla and all anticheats do).
 *   Rapid   - fire as fast as the use-key lets you re-draw; each arrow is whatever charge
 *             it reached. Good for volume, not power.
 *   Desync  - send a RELEASE_USE_ITEM packet WITHOUT ending the client draw, so you can
 *             emit repeated shots from one draw. A genuine desync trick that some lenient
 *             servers mishandle; strict anticheat will flag or ignore it. Experimental.
 */
public class FastBow extends Module {
    public enum Mode { Full, Instant, Rapid, Desync }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Release strategy. Full = server-safe full power; Instant = fastest release where the server allows it; Rapid = max fire rate; Desync = experimental server trick.")
        .defaultValue(Mode.Full)
        .build()
    );

    private final Setting<Boolean> bows = sgGeneral.add(new BoolSetting.Builder()
        .name("bows").description("Affect bows (including modded).").defaultValue(true).build());

    private final Setting<Boolean> crossbows = sgGeneral.add(new BoolSetting.Builder()
        .name("crossbows").description("Affect crossbows.").defaultValue(true).build());

    private final Setting<Integer> chargeTime = sgGeneral.add(new IntSetting.Builder()
        .name("charge-time")
        .description("Instant mode on a REMOTE server: ticks to draw before releasing (0 = weakest/instant, 20 = full vanilla). Ignored where you control the server.")
        .defaultValue(0).min(0).sliderRange(0, 40)
        .visible(() -> mode.get() == Mode.Instant)
        .build()
    );

    private final Setting<Boolean> spam = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-repeat").description("Auto re-draw and fire again while you hold the use key.").defaultValue(false).build());

    private final Setting<Integer> spamDelay = sgGeneral.add(new IntSetting.Builder()
        .name("spam-delay").description("Ticks between shots for Rapid/Desync/spam.").defaultValue(1).min(0).sliderRange(0, 20).build());

    private int useTicks;
    private int cooldown;

    public FastBow() {
        super(shama.addon.ShamaAddon.COMBAT, "fast-bow++", "Draw bows and crossbows much faster than normal.");
    }

    @Override
    public void onActivate() {
        useTicks = 0;
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (cooldown > 0) cooldown--;

        if (mc.player.isUsingItem()) {
            ItemStack active = mc.player.getActiveItem();
            if (!applies(active)) { useTicks = 0; return; }
            useTicks++;

            switch (mode.get()) {
                case Full -> {
                    if (useTicks >= fullDraw(active)) release();
                }
                case Instant -> {
                    if (mc.getServer() != null) {      // singleplayer / LAN host: genuine instant full power
                        forceServerFull(active);
                        release();
                    } else if (useTicks >= Math.max(0, chargeTime.get())) {
                        release();                      // remote server: early release (weak unless server is lenient)
                    }
                }
                case Rapid -> {
                    if (cooldown <= 0) release();
                }
                case Desync -> {
                    if (cooldown <= 0) {                // fire without ending the client draw
                        sendRawRelease();
                        cooldown = Math.max(1, spamDelay.get());
                    }
                }
            }
        } else {
            useTicks = 0;
            if (spam.get() && cooldown <= 0 && mc.options.useKey.isPressed()) {
                Hand hand = rangedHand();
                if (hand != null) {
                    mc.interactionManager.interactItem(mc.player, hand);
                    cooldown = Math.max(1, spamDelay.get());
                }
            }
        }
    }

    /** Release the draw normally (real RELEASE_USE_ITEM via the interaction manager). */
    private void release() {
        mc.interactionManager.stopUsingItem(mc.player);
        useTicks = 0;
        cooldown = spamDelay.get();
    }

    /** Send a raw release packet without clearing the client-side draw (Desync). */
    private void sendRawRelease() {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN, 0));
    }

    /**
     * Where you control the server (own worlds / lenient servers): jump the server player's draw
     * counter to full so a full-power arrow is computed on release. No-op and null-safe otherwise.
     */
    private void forceServerFull(ItemStack stack) {
        if (mc.getServer() == null) return;
        ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
        if (sp == null || !sp.isUsingItem()) return;
        LivingEntityAccessor acc = (LivingEntityAccessor) sp;
        int cur = acc.shama$getItemUseTimeLeft();
        acc.shama$setItemUseTimeLeft(Math.max(1, cur - fullDraw(stack)));
    }

    private int fullDraw(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem ? 25 : 20; // vanilla full load / draw
    }

    private boolean applies(ItemStack stack) {
        if (stack == null) return false;
        if (stack.getItem() instanceof CrossbowItem) return crossbows.get();
        if (stack.getItem() instanceof RangedWeaponItem) return bows.get();
        return false;
    }

    private Hand rangedHand() {
        if (mc.player.getMainHandStack().getItem() instanceof RangedWeaponItem) return Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().getItem() instanceof RangedWeaponItem) return Hand.OFF_HAND;
        return null;
    }

    @Override
    public String getInfoString() {
        return mode.get().name().toLowerCase() + (mc.getServer() == null && mode.get() == Mode.Instant ? " (remote)" : "");
    }
}
