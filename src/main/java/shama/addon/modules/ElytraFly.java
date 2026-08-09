package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * ElytraFly++ — elytra flight with their real mode set (collision-safe name; Meteor's own
 * full elytra-fly with autopilot/chest-swap/replenish is built into Meteor if you want that).
 * Modes:
 *   Vanilla  - manual velocity glide; jump/sneak for altitude, fall-multiplier for descent.
 *   Pitch40  - the real dive/climb oscillation between +37.72 and -54.77 pitch, bounded by
 *              your upper/lower Y bounds, with configurable up/down rotation speeds.
 *   Packet   - Vanilla control plus a periodic on-ground packet to dodge airtime kicks.
 *   Bounce   - bounces off the ground/walls to stay airborne without rockets.
 */
public class ElytraFly extends Module {
    public enum Mode { Vanilla, Pitch40, Packet, Bounce }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgP40 = settings.createGroup("Pitch40");
    private final SettingGroup sgExtra = settings.createGroup("Extras");

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>().name("mode").description("Which mode to use.").defaultValue(Mode.Vanilla).build());
    private final Setting<Double> speed = sg.add(new DoubleSetting.Builder().name("horizontal-speed").description("Glide speed (Vanilla/Packet).").defaultValue(1.0).min(0).sliderRange(0.2, 3)
        .visible(() -> mode.get() == Mode.Vanilla || mode.get() == Mode.Packet).build());
    private final Setting<Double> verticalSpeed = sg.add(new DoubleSetting.Builder().name("vertical-speed").description("Up/down speed on jump/sneak (Vanilla/Packet).").defaultValue(0.5).min(0).sliderRange(0, 2)
        .visible(() -> mode.get() == Mode.Vanilla || mode.get() == Mode.Packet).build());
    private final Setting<Double> fallMultiplier = sg.add(new DoubleSetting.Builder().name("fall-multiplier").description("How fast you sink when not holding jump (Vanilla/Packet).").defaultValue(0.01).min(0).sliderRange(0, 0.2)
        .visible(() -> mode.get() == Mode.Vanilla || mode.get() == Mode.Packet).build());
    private final Setting<Boolean> sprint = sg.add(new BoolSetting.Builder().name("sprint").description("Keep sprinting while flying.").defaultValue(true).build());

    private final Setting<Double> p40upper = sgP40.add(new DoubleSetting.Builder().name("upper-bounds").description("Climb until you reach this Y, then dive.").defaultValue(120).sliderRange(0, 320).visible(() -> mode.get() == Mode.Pitch40).build());
    private final Setting<Double> p40lower = sgP40.add(new DoubleSetting.Builder().name("lower-bounds").description("Dive until you reach this Y, then climb.").defaultValue(70).sliderRange(-64, 320).visible(() -> mode.get() == Mode.Pitch40).build());
    private final Setting<Double> p40rotUp = sgP40.add(new DoubleSetting.Builder().name("rotation-speed-up").description("How fast it pitches up when climbing.").defaultValue(3).min(0.1).sliderRange(0.5, 8).visible(() -> mode.get() == Mode.Pitch40).build());
    private final Setting<Double> p40rotDown = sgP40.add(new DoubleSetting.Builder().name("rotation-speed-down").description("How fast it pitches back down when diving.").defaultValue(3).min(0.1).sliderRange(0.5, 8).visible(() -> mode.get() == Mode.Pitch40).build());

    private final Setting<Boolean> autoTakeOff = sgExtra.add(new BoolSetting.Builder().name("auto-take-off").description("Start gliding automatically when you hold jump in the air with an elytra.").defaultValue(false).build());
    private final Setting<Boolean> autoHover = sgExtra.add(new BoolSetting.Builder().name("auto-hover").description("Hold altitude when you're not pressing anything (Vanilla/Packet).").defaultValue(false).build());
    private final Setting<Boolean> noCrash = sgExtra.add(new BoolSetting.Builder().name("no-crash").description("Cut horizontal speed before you fly into a wall.").defaultValue(false).build());
    private final Setting<Integer> crashLookAhead = sgExtra.add(new IntSetting.Builder().name("crash-look-ahead").description("Blocks ahead to check for no-crash.").defaultValue(6).min(1).max(20).sliderRange(2, 12).visible(noCrash::get).build());
    private final Setting<Boolean> autoFirework = sgExtra.add(new BoolSetting.Builder()
        .name("auto-firework")
        .description("Fire a rocket by itself whenever you drop below the speed below, so a long flight keeps going without you holding it up. Rockets come from your hotbar and it only fires while you are actually gliding.")
        .defaultValue(false).build());
    private final Setting<Double> minSpeed = sgExtra.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Speed you have to fall under before another rocket goes off, in blocks a second.")
        .defaultValue(20.0).min(1).max(120).sliderRange(5, 60).decimalPlaces(1)
        .visible(autoFirework::get).build());
    private final Setting<Integer> minFireY = sgExtra.add(new IntSetting.Builder()
        .name("min-y")
        .description("Do not fire below this height — no point burning rockets while you are still climbing out of a hole.")
        .defaultValue(80).min(-64).max(320).sliderRange(0, 200)
        .visible(autoFirework::get).build());
    private final Setting<Integer> fireworkDelay = sgExtra.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Ticks to wait between rockets.")
        .defaultValue(40).min(5).max(200).sliderRange(10, 100)
        .visible(autoFirework::get).build());

    private final Setting<Boolean> boost = sgExtra.add(new BoolSetting.Builder().name("boost").description("Extra forward burst while holding sprint, no rockets.").defaultValue(false).build());
    private final Setting<Double> boostAmount = sgExtra.add(new DoubleSetting.Builder().name("boost-amount").description("How much extra speed each boost adds.").defaultValue(1.5).min(1).sliderRange(1, 4).visible(boost::get).build());

    private boolean pitchingDown = true;
    private float p40pitch = 37.72f;
    private int packetTimer;

    public ElytraFly() {
        super(shama.addon.ShamaAddon.MOVEMENT, "elytra-fly++", "Enhanced elytra flight with extra control and boost options.");
    }

    @Override public void onActivate() { pitchingDown = true; p40pitch = 37.72f; packetTimer = 0; }

    private int fireworkTimer;

    /** Keep the glide going by firing a rocket when you slow down. */
    private void autoRocket() {
        if (!autoFirework.get() || mc.player == null || !mc.player.isGliding()) return;
        if (mc.player.getY() < minFireY.get()) return;
        if (++fireworkTimer < fireworkDelay.get()) return;

        double v = mc.player.getVelocity().length() * 20.0;      // blocks per second
        if (v >= minSpeed.get()) return;
        fireworkTimer = 0;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() != net.minecraft.item.Items.FIREWORK_ROCKET) continue;
            int prev = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(i);
            mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
            mc.player.getInventory().setSelectedSlot(prev);
            return;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        autoRocket();
        if (mc.player == null || mc.options == null) return;

        // auto take-off: fire the start-gliding command when holding jump in the air with an elytra
        if (autoTakeOff.get() && !mc.player.isGliding() && mc.options.jumpKey.isPressed()
            && !mc.player.isOnGround() && hasElytra() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }
        if (!mc.player.isGliding()) return;
        if (sprint.get()) mc.player.setSprinting(true);

        switch (mode.get()) {
            case Pitch40 -> doPitch40();                       // vanilla physics glides along the pitch
            case Bounce -> {
                if (mc.player.isOnGround() || mc.player.horizontalCollision) {
                    Vec3d v = mc.player.getVelocity();
                    mc.player.setVelocity(v.x, 0.42, v.z);
                }
            }
            case Vanilla, Packet -> {
                manualControl();
                if (mode.get() == Mode.Packet && mc.getNetworkHandler() != null && ++packetTimer >= 40) {
                    packetTimer = 0;
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
                }
            }
        }
    }

    private void manualControl() {
        Vec3d v = mc.player.getVelocity();
        double rad = Math.toRadians(mc.player.getYaw());
        Vec3d f = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
        Vec3d r = new Vec3d(Math.cos(rad), 0, Math.sin(rad));
        double mx = 0, mz = 0;
        if (mc.options.forwardKey.isPressed()) { mx += f.x; mz += f.z; }
        if (mc.options.backKey.isPressed()) { mx -= f.x; mz -= f.z; }
        if (mc.options.rightKey.isPressed()) { mx += r.x; mz += r.z; }
        if (mc.options.leftKey.isPressed()) { mx -= r.x; mz -= r.z; }
        boolean moving = mx != 0 || mz != 0;

        double vy;
        if (mc.options.jumpKey.isPressed()) vy = verticalSpeed.get();
        else if (mc.options.sneakKey.isPressed()) vy = -verticalSpeed.get();
        else if (autoHover.get() && !moving) vy = 0;
        else vy = -fallMultiplier.get();

        if (noCrash.get() && willCrash(mx, mz)) { mx = 0; mz = 0; moving = false; }

        double eff = speed.get() * (boost.get() && mc.options.sprintKey.isPressed() ? boostAmount.get() : 1.0);
        mc.player.setVelocity(moving ? mx * eff : v.x, vy, moving ? mz * eff : v.z);
    }

    /** Real Pitch40: oscillate pitch between +37.72 (dive) and -54.77 (climb), flipped by Y bounds. */
    private void doPitch40() {
        if (pitchingDown && mc.player.getY() <= p40lower.get()) pitchingDown = false;
        else if (!pitchingDown && mc.player.getY() >= p40upper.get()) pitchingDown = true;

        if (!pitchingDown) {
            p40pitch -= randPitch(p40rotUp.get().floatValue(), 1.0f);
            if (p40pitch < -54.77f) { p40pitch = -54.77f; pitchingDown = true; }
        } else if (p40pitch < 37.72f) {
            p40pitch += randPitch(p40rotDown.get().floatValue(), 0.5f);
        }
        mc.player.setPitch(p40pitch);
    }

    private float randPitch(float base, float bound) { return (float) (base + bound * (Math.random() - 0.5)); }

    private boolean willCrash(double mx, double mz) {
        if ((mx == 0 && mz == 0) || mc.world == null) return false;
        Vec3d dir = new Vec3d(mx, 0, mz).normalize();
        Vec3d eye = mc.player.getEyePos();
        for (int i = 1; i <= crashLookAhead.get(); i++) {
            BlockPos p = BlockPos.ofFloored(eye.add(dir.multiply(i)));
            if (!mc.world.getBlockState(p).isAir()) return true;
        }
        return false;
    }

    private boolean hasElytra() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    @Override public String getInfoString() { return mode.get().name().toLowerCase(); }
}
