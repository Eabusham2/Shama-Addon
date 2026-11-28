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
        .min(0)
        .max(2)
        .sliderRange(0, 1)
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
    public String targetName = "";
    public PlayerEntity followed;
    public int startAge;
    public boolean walkingPhase;
    public boolean atContact;
    public int crouchTicks;
    public double legitPhase;
    public boolean hasStartedLegit;

    public Bang() {
        super(shama.addon.ShamaAddon.MISC, "bang++",
            "Hover behind or in front of a player and swing to touch them. Normal mode locks on; Legit mode walks there first.");
    }

    @Override
    public void onActivate() {
        // Enabling the module alone does NOT start banging; need .bang <player>.
        followed = null;
        startAge = 0;
        walkingPhase = false;
        atContact = false;
        crouchTicks = 0;
        legitPhase = 0;
        hasStartedLegit = false;
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

        if (mode.get() == Mode.Legit) {
            if (!walkingPhase && !hasStartedLegit) {
                // Walk phase first (Baritone), once
                hasStartedLegit = true;
                startWalkingPhase();
            } else if (walkingPhase) {
                checkArrival();
            }
        } else {
            // Normal mode: exactly restore original — face target, steady head, no limb animation
            float yaw = followed.getBodyYaw() + (position.get() == Position.Front ? 180f : 0f); // front: turn around to face target; back: match target (original bang-1)
            mc.player.setYaw(yaw);
            mc.player.setHeadYaw(yaw);
            mc.player.setBodyYaw(yaw);
            mc.player.setPitch(0);
            mc.player.limbAnimator.reset();
        }
    }

    private PlayerEntity findTarget() {
        if (followed != null && !followed.isRemoved() && followed.getEntityWorld() == mc.world) return followed;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.getName().getString().equalsIgnoreCase(targetName)) return p;
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
            legitPhase = 0;
            Chat.info("[bang] Arrived. Starting animation.");
        }
        return false;
    }

    /** Current swing extension 0..1 based on module speed. */
    public double extension() {
        if (followed == null) return 0;
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

/**
     * Legit mode: generate vanilla PlayerInput — walking + crouching, no custom pose,
     * no hover-lock. Same logic as original; usable with a vanilla macro + good setup.
     *
     *   walk toward a spot moving 0.6↔1.3 blocks behind their facing
     *   strafe left/right, jump on obstacle, sprint when far
     *   crouch alternating half-time when touching
     */
    public Optional<PlayerInput> legitInput() {
        if (mode.get() != Mode.Legit || walkingPhase) return Optional.empty();

        float yaw = followed.getYaw();
        mc.player.setYaw(yaw);
        Vec3d facing = Vec3d.fromPolar(0, yaw);
        double dx = followed.getX() - mc.player.getX();
        double dz = followed.getZ() - mc.player.getZ();
        int sign = position.get() == Position.Back ? -1 : 1;

        // Walk toward a spot that moves between 0.6 and 1.3 blocks in front/behind their facing.
        double behind = 0.95 + 0.35 * Math.cos(2 * Math.PI * legitPhase);
        double forward = sign * (dx * facing.x + dz * facing.z - behind);
        double left = dx * facing.z - dz * facing.x;
        boolean close = mc.player.squaredDistanceTo(followed) < 4;
        boolean crouch = legitCrouch.get() && close && legitPhase >= 0.5;
        legitPhase = (legitPhase + speed.get() / 20) % 1;

        boolean jump = mc.player.horizontalCollision && mc.player.isOnGround();
        return Optional.of(new PlayerInput(
            forward > 0.08, forward < -0.08, left > 0.08, left < -0.08,
            jump, crouch, !close && forward > 0.08
        ));
    }

    @Override
    public String getInfoString() {
        if (targetName.isBlank()) return null;
        return mode.get().name() + " " + position.get().name() + " " + targetName;
    }
}