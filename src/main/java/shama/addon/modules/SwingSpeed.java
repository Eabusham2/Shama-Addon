package shama.addon.modules;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import java.lang.reflect.Field;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Swing Speed — controls how fast your arm swings.
 *   Custom : a fixed swing duration in ticks (lower = faster).
 *   Auto   : each swing lasts exactly as long as it takes to mine the block you're
 *            looking at (1 / break-speed), or your attack-cooldown time when you're not
 *            aimed at a block (interact/attack) — so the animation always matches the
 *            real action instead of a flat number.
 */
public class SwingSpeed extends Module {
    public enum Mode { Speed, Custom, Auto }
    public enum AutoIdle { AttackCooldown, CustomTicks }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Speed = a multiplier on the normal swing. Custom = fixed ticks. Auto = time to mine the block you look at.").defaultValue(Mode.Speed).build());
    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder()
        .name("speed").description("Their SwingSpeed multiplier: duration = round(vanilla / speed). Higher = faster.")
        .defaultValue(1.0).range(0.1, 2.0).sliderRange(0.1, 2.0).decimalPlaces(2).visible(() -> mode.get() == Mode.Speed).build());
    private final Setting<AutoIdle> autoIdle = sg.add(new EnumSetting.Builder<AutoIdle>()
        .name("auto-idle").description("In Auto, when you are NOT aimed at a block. Custom ticks keeps the swing fast and steady; attack cooldown ties it to your weapon's recharge, which slows the animation right down between hits.")
        .defaultValue(AutoIdle.CustomTicks).visible(() -> mode.get() == Mode.Auto).build());
    private final Setting<Integer> customTicks = sg.add(new IntSetting.Builder()
        .name("swing-ticks").description("Swing duration in ticks (lower = faster). Vanilla is 6.")
        .defaultValue(3).min(1).max(200).sliderRange(1, 20)
        .visible(() -> mode.get() == Mode.Custom || (mode.get() == Mode.Auto && autoIdle.get() == AutoIdle.CustomTicks)).build());
    private final Setting<Boolean> autoInteract = sg.add(new BoolSetting.Builder()
        .name("auto-interact")
        .description("In Auto mode, swing fast whenever you use or right-click ANY item — fireworks, ender pearls, bows, fishing rods, food, buckets and so on — instead of timing the swing to your attack cooldown.")
        .defaultValue(true).visible(() -> mode.get() == Mode.Auto).build());
    private final Setting<Integer> interactTicks = sg.add(new IntSetting.Builder()
        .name("interact-ticks")
        .description("How fast the swing is when you use an item in Auto mode (lower = faster).")
        .defaultValue(3).min(1).max(20).sliderRange(1, 8)
        .visible(() -> mode.get() == Mode.Auto && autoInteract.get()).build());
    private final Setting<Integer> maxTicks = sg.add(new IntSetting.Builder()
        .name("auto-cap").description("Clamp auto swings to at most this many ticks (very hard blocks like obsidian).")
        .defaultValue(60).min(1).max(400).sliderRange(6, 200).visible(() -> mode.get() == Mode.Auto).build());

    private int interactCooldown;   // ticks left where we still treat things as an interact swing

    public SwingSpeed() { super(shama.addon.ShamaAddon.PLAYER, "swing-speed++", "Controls how fast your arm swings — a set speed, a fixed duration, or auto-timed to whatever you're doing (mining a block, attacking, or using any item like fireworks)."); }

    // A right-click use of any item (fireworks, pearls, snowballs, bows, food, buckets, spyglass, etc.)
    // sends this packet — even the instantaneous ones — so this catches every interact type.
    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof PlayerInteractItemC2SPacket) interactCooldown = 4;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (interactCooldown > 0) interactCooldown--;
    }

    private boolean interacting() {
        // continuous-use items (bow draw, food, shield, spyglass, fishing) report isUsingItem;
        // instantaneous ones (fireworks, pearls, snowballs) are caught by the packet cooldown above.
        return mc.player != null && (interactCooldown > 0 || mc.player.isUsingItem());
    }

    /** Called by HandSwingDurationMixin with the vanilla-computed duration. */
    /**
     * The mixin on getHandSwingDuration is declared with require = 0, so if that method is named
     * differently in this version it fails silently and nothing happens. This driver does the same
     * job from the tick loop by advancing the swing counter itself, so the module works either way.
     * The field is found by signature (an int named handSwingTicks) and cached.
     */
    private static Field swingField;
    private static boolean swingFieldChecked;
    private double swingCarry;

    private static Field swingDriver(Object player) {
        if (!swingFieldChecked) {
            swingFieldChecked = true;
            for (Class<?> c = player.getClass(); c != null && swingField == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    // At runtime the game is remapped to intermediary, so the Yarn name
                    // "handSwingTicks" is not what the field is actually called — it is
                    // "field_6252". Checking both is why this now works outside a dev environment.
                    if (f.getType() != int.class) continue;
                    String n = f.getName();
                    if (n.equals("handSwingTicks") || n.equals("field_6252")) {
                        f.setAccessible(true); swingField = f; break;
                    }
                }
            }
        }
        return swingField;
    }

    @EventHandler
    private void onSwingTick(TickEvent.Post event) {
        if (mc.player == null || !mc.player.handSwinging) { swingCarry = 0; return; }
        Field f = swingDriver(mc.player);
        if (f == null) return;
        try {
            int vanilla = 6;                       // vanilla swing length; only the ratio matters here
            int target = Math.max(1, getSwingDuration(vanilla));
            if (target >= vanilla) return;         // slower or equal: vanilla already handles it
            // advance the counter by the extra amount needed to finish in `target` ticks
            swingCarry += (double) vanilla / target - 1.0;
            int extra = (int) swingCarry;
            if (extra <= 0) return;
            swingCarry -= extra;
            f.setInt(mc.player, f.getInt(mc.player) + extra);
        } catch (Throwable ignored) {}
    }

    public int getSwingDuration(int vanilla) {
        return switch (mode.get()) {
            case Speed -> { float s = speed.get().floatValue(); if (s <= 0f) s = 0.1f; yield Math.max(1, Math.round(vanilla / s)); }
            case Custom -> customTicks.get();
            case Auto -> computeAuto(vanilla);
        };
    }

    private int computeAuto(int vanilla) {
        if (mc.player == null || mc.world == null) return vanilla;
        // Using / right-clicking any item -> snap the swing fast so the animation matches the interact.
        if (autoInteract.get() && interacting()) return clamp(interactTicks.get());
        // Aimed at a block -> make the swing last exactly as long as breaking that block takes.
        // getBlockBreakingSpeed is the fraction broken per tick, so 1/speed = ticks to break.
        if (mc.crosshairTarget instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
            float s = mc.player.getBlockBreakingSpeed(mc.world.getBlockState(bh.getBlockPos()));
            if (s > 0) return clamp((int) Math.ceil(1.0f / s));   // (0 for unbreakable like bedrock -> falls through)
        }
        // Not aimed at a block -> chosen fallback
        return switch (autoIdle.get()) {
            case CustomTicks -> customTicks.get();
            case AttackCooldown -> {
                float perTick = mc.player.getAttackCooldownProgressPerTick();
                yield perTick > 0 ? clamp((int) Math.ceil(1.0f / perTick)) : vanilla;
            }
        };
    }

    private int clamp(int t) { return Math.max(1, Math.min(maxTicks.get(), t)); }

    @Override public String getInfoString() { return mode.get().name().toLowerCase(); }
}
