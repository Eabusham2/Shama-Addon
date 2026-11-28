package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import shama.addon.commands.BangCommand;
import shama.addon.util.BaritoneBridge;
import shama.addon.util.Chat;

import java.util.Optional;

import net.minecraft.util.PlayerInput;

/**
 * Bang — hover behind (or in front of) a player and swing in to touch them.
 *
 * Normal mode: instant hover-lock at a set distance, legs swing to contact.
 * Legit mode: Baritone walks you to the player, then you walk/crouch in and out
 * the old-fashioned way — looks like a real player doing it.
 */
public class Bang extends Module {
    public enum Mode { Normal, Legit }
    public enum Position { Back, Front }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Target");
    private final SettingGroup sgLegit = settings.createGroup("Legit Mode");

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Normal = instant hover lock with leg swing. Legit = Baritone walks to target first, then you walk/crouch manually.")
        .defaultValue(Mode.Normal)
        .build());

    public final Setting<Position> position = sgGeneral.add(new EnumSetting.Builder<Position>()
        .name("position")
        .description("Whether the animation happens behind or in front of the target.")
        .defaultValue(Position.Back)
        .build());

    public final Setting<Double> height = sgGeneral.add(new DoubleSetting.Builder()
        .name("height")
        .description("Vertical offset from the target's feet. 0.2 puts you at their waist.")
        .defaultValue(0.2)
        .min(-2)
        .max(2)
        .sliderRange(-1, 1)
        .decimalPlaces(2)
        .build());

    public final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Extra horizontal distance from the target's hitbox. Added to the contact distance.")
        .defaultValue(0.18)
        .min(0)
        .max(3)
        .sliderRange(0, 1)
        .decimalPlaces(2)
        .build());

    public final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Swing cycles per second.")
        .defaultValue(1.0)
        .min(0.1)
        .max(10)
        .sliderRange(0.1, 5)
        .decimalPlaces(1)
        .build());

    public final Setting<String> target = sgTarget.add(new StringSetting.Builder()
        .name("player")
        .description("Who to bang. Set by .bang <player> <speed>.")
        .defaultValue("")
        .build());

    public final Setting<Boolean> legitWalk = sgLegit.add(new BoolSetting.Builder()
        .name("walk-to-target")
        .description("In Legit mode, use Baritone to walk to the target before starting the animation.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Legit)
        .build());

    public final Setting<Integer> legitRange = sgLegit.add(new IntSetting.Builder()
        .name("walk-range")
        .description("How close Baritone gets before the manual animation takes over.")
        .defaultValue(2)
        .min(1)
        .max(6)
        .sliderRange(1, 4)
        .visible(() -> mode.get() == Mode.Legit && legitWalk.get())
        .build());

    public final Setting<Boolean> legitCrouch = sgLegit.add(new BoolSetting.Builder()
        .name("crouch-at-contact")
        .description("In Legit mode, crouch at the moment of contact.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Legit)
        .build());

    // Runtime state (public for BangCommand access)
    public PlayerEntity followed;
    public int startAge;
    public boolean walkingPhase;
    public boolean atContact;
    public int crouchTicks;

    public Bang() {
        super(shama.addon.ShamaAddon.MISC, "bang++",
            "Hover behind or in front of a player and swing to touch them. Normal mode locks on; Legit mode walks there first.");
    }

    @Override
    public void onActivate() {
        if (target.get().isBlank()) {
            Chat.error("[bang] No target set. Use .bang <player> [speed] to start.");
            toggle();
            return;
        }
        followed = null;
        startAge = 0;
        walkingPhase = false;
        atContact = false;
        crouchTicks = 0;
    }

    @Override
    public void onDeactivate() {
        if (walkingPhase && BaritoneUtils.IS_AVAILABLE) {
            BaritoneBridge.stop();
            BaritoneBridge.restoreWalkOnly();
        }
        followed = null;
        BangCommand.INSTANCE.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Resolve target each tick in case they left/rejoined
        followed = findTarget();
        if (followed == null) return;

        BangCommand.INSTANCE.setModule(this);
        BangCommand.INSTANCE.setTarget(followed);
        BangCommand.INSTANCE.setSpeed(speed.get());

        if (mode.get() == Mode.Legit && !walkingPhase) {
            startWalkingPhase();
        }
    }

    private PlayerEntity findTarget() {
        if (followed != null && !followed.isRemoved() && followed.getEntityWorld() == mc.world) return followed;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.getName().getString().equalsIgnoreCase(target.get())) return p;
        }
        return null;
    }

    private void startWalkingPhase() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            Chat.error("[bang] Legit mode requires Baritone.");
            toggle();
            return;
        }
        walkingPhase = true;
        BaritoneBridge.walkOnly();
        Vec3d targetPos = getAnchorPos(followed);
        BaritoneBridge.goNear((int) targetPos.x, (int) targetPos.y, (int) targetPos.z, legitRange.get());
        Chat.info("[bang] Walking to %s...", followed.getName().getString());
    }

    /** Called by BangCommand each tick when module is active. */
    public boolean isWalkingPhase() {
        return walkingPhase && mode.get() == Mode.Legit;
    }

    /** Called by BangCommand to check if we've arrived at the target. */
    public boolean checkArrival() {
        if (!walkingPhase) return true;
        double dist = mc.player.distanceTo(followed);
        if (dist <= legitRange.get() + 1.5) {
            walkingPhase = false;
            if (BaritoneUtils.IS_AVAILABLE) {
                BaritoneBridge.stop();
                BaritoneBridge.restoreWalkOnly();
            }
            startAge = mc.player.age;
            atContact = false;
            crouchTicks = 0;
            Chat.info("[bang] Arrived. Starting animation.");
        }
        return false;
    }

    /** Current swing extension 0..1 based on module speed. */
    public double extension() {
        double cycles = Math.max(0, mc.player.age - startAge) * speed.get() / 20;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * cycles);
    }

    /** Anchor position relative to target (feet). */
    public Vec3d getAnchorPos(PlayerEntity t) {
        double contact = (mc.player.getWidth() + t.getWidth()) * 0.5;
        double behind = contact + distance.get();
        float yaw = t.getBodyYaw();
        int sign = position.get() == Position.Back ? -1 : 1;
        Vec3d facing = Vec3d.fromPolar(0, yaw);
        return t.getEntityPos().add(sign * behind * facing.x, height.get(), sign * behind * facing.z);
    }

    /** Legit mode: generate PlayerInput for walking/crouching. */
    public Optional<PlayerInput> legitInput() {
        if (mode.get() != Mode.Legit || walkingPhase) return Optional.empty();

        Vec3d anchor = getAnchorPos(followed);
        double dx = anchor.x - mc.player.getX();
        double dz = anchor.z - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        boolean forward = horiz > 0.15;
        boolean crouch = false;

        // At contact distance: crouch for a few ticks
        if (horiz < 0.15 && !atContact) {
            atContact = true;
            crouchTicks = legitCrouch.get() ? 4 : 0;
        }
        if (crouchTicks > 0) {
            crouch = true;
            crouchTicks--;
        }
        // Leaving contact: stand up and reset
        if (atContact && horiz > 0.3) {
            atContact = false;
        }

        float yaw = followed.getBodyYaw();
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);
        mc.player.setPitch(0);

        return Optional.of(new net.minecraft.util.PlayerInput(
            forward, false, false, false, false, crouch, forward
        ));
    }

    @Override
    public String getInfoString() {
        if (target.get().isBlank()) return null;
        return mode.get().name() + " " + position.get().name() + " " + target.get();
    }
}