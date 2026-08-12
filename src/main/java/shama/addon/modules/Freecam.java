package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Freecam++ — detached free-flying camera. Position is stepped once per tick and the
 * camera mixin interpolates it by tick-delta every frame, so motion is smooth at any FPS
 * (the old version set the camera straight to the per-tick position, which is what made it
 * jitter). The mouse steers the camera; your body stays put unless you enable rotate.
 */
public class Freecam extends Module {
    public enum Click {
        /** Do nothing at all — clicking while the camera is out is almost always a misfire. */
        Ignore,
        /** Mine and use where your real body is looking, not where the camera points. */
        RealBody
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgExtra = settings.createGroup("Extras");

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Camera fly speed (blocks per tick).").defaultValue(0.5).min(0.05).sliderRange(0.1, 5.0).build());

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Multiplies how fast you rise and fall against your normal speed. Under 1 makes it easier to hold a height while you look around; over 1 gets you up and down a shaft quickly.")
        .defaultValue(1.2).min(0.5).max(4).sliderRange(0.5, 4).decimalPlaces(2).build());

    private final Setting<Boolean> verticalKeys = sgGeneral.add(new BoolSetting.Builder()
        .name("vertical-affects-keys")
        .description("Apply the multiplier to jump and sneak, so tapping up or down moves you at the boosted rate.")
        .defaultValue(true).build());

    private final Setting<Boolean> verticalLook = sgGeneral.add(new BoolSetting.Builder()
        .name("vertical-affects-look")
        .description("Apply it to the up-and-down part of flying where you look, too. Leave this off if you want flying forward to feel exactly as before and only the jump and sneak keys to be quicker.")
        .defaultValue(false).build());

    private final Setting<Boolean> scrollSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-changes-speed")
        .description("Let the scroll wheel adjust the fly speed while the camera is out.")
        .defaultValue(true).build());

    private final Setting<Boolean> scrollRemembers = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-remembers")
        .description("Keep whatever you scrolled to for next time instead of snapping back to the slider value when you close the camera. Off means the scroll only lasts for that session.")
        .defaultValue(true).build());

    private final Setting<Click> clickAction = sgGeneral.add(new EnumSetting.Builder<Click>()
        .name("click-action")
        .description("What a mouse click does while the camera is out. Your body is not where the camera is, so a click used to swing at whatever happened to be in front of the camera, which is usually thin air. Ignore drops the click entirely; Real Body mines and uses where your actual character is looking, which is what you would get with the camera closed.")
        .defaultValue(Click.Ignore).build());

    private final Setting<Boolean> holdToMine = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-mine")
        .description("Mine only while the button is held, rather than latching on from one press. Off leaves the click held down until you press again, which is how it behaved before and is easy to forget about.")
        .defaultValue(true).visible(() -> clickAction.get() == Click.RealBody).build());
    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing").description("How much the camera eases into a start and out of a stop. A little takes the jerkiness off without making it feel like it is floating; 0 is instant, the way Meteor moves.").defaultValue(0.05).min(0).max(0.9).sliderRange(0, 0.5).decimalPlaces(2).build());
    private final Setting<Boolean> lookFly = sgGeneral.add(new BoolSetting.Builder()
        .name("fly-toward-crosshair")
        .description("Move along the direction you're actually looking, so looking down and holding forward takes you down. Off = stay level and only rise/fall with jump and sneak.")
        .defaultValue(true).build());
    private final Setting<Double> sensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("look-sensitivity").description("Mouse sensitivity for steering the camera.").defaultValue(0.15).min(0.01).sliderRange(0.05, 0.5).build());

    private final Setting<Boolean> scrollSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-speed").description("Scroll the mouse wheel to change fly speed live (like Meteor's freecam).").defaultValue(true).build());
    private final Setting<Double> scrollStep = sgGeneral.add(new DoubleSetting.Builder()
        .name("scroll-step").description("How much each scroll notch multiplies the speed.").defaultValue(1.1).min(1.01).sliderRange(1.05, 1.5).decimalPlaces(2).visible(scrollSpeed::get).build());

    private final Setting<Boolean> keepInputs = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-inputs").description("Lock the attack/use you held on entry so you keep mining/using at your character's view.").defaultValue(true).build());
    private final Setting<Boolean> mineAtCharacter = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-at-character").description("A mouse press in freecam mines/interacts where your CHARACTER looks, not the camera.").defaultValue(true).build());

    // Requested extras
    private final Setting<Boolean> reloadChunks = sgExtra.add(new BoolSetting.Builder()
        .name("reload-chunks").description("Reload all chunks when freecam toggles (fixes render gaps).").defaultValue(false).build());
    private final Setting<Boolean> showHands = sgExtra.add(new BoolSetting.Builder()
        .name("show-hands").description("Stay in first person so your hands/held item render at the camera. Off forces third person so you see your body.").defaultValue(true).build());
    private final Setting<Boolean> rotate = sgExtra.add(new BoolSetting.Builder()
        .name("rotate").description("Rotate your character's body to follow the camera direction.").defaultValue(false).build());
    private final Setting<Boolean> keepSneaking = sgExtra.add(new BoolSetting.Builder()
        .name("keep-sneaking").description("Hold the player sneaking the whole time so it can't walk off an edge.").defaultValue(false).build());
    private final Setting<Boolean> allowPathing = sgExtra.add(new BoolSetting.Builder()
        .name("allow-pathing").description("Don't lock movement keys, so Baritone / the player can still path while the camera flies free.").defaultValue(false).build());

    // Camera state (read by CameraMixin each frame).
    private Vec3d pos, prevPos;
    private float yaw, pitch;
    private boolean hasPos;
    private double scrollMult = 1.0;
    private Vec3d vel = Vec3d.ZERO;

    // Entry input snapshot.
    private boolean needInit;
    private boolean sForward, sBack, sLeft, sRight, sJump, sSneak, sSprint, sAttack, sUse;
    private Perspective prevPerspective;

    public Freecam() {
        super(shama.addon.ShamaAddon.MOVEMENT, "freecam++", "Smooth free-flying detached camera with pathing / rotate / sneak options.");
    }

    @Override
    public void onActivate() {
        hasPos = false;
        needInit = true;
        if (!scrollRemembers.get()) scrollMult = 1.0;   // otherwise carry it over
        vel = Vec3d.ZERO;
        prevPerspective = mc.options != null ? mc.options.getPerspective() : null;
        if (mc.options != null && !showHands.get()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        if (reloadChunks.get() && mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            for (KeyBinding k : new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey,
                mc.options.rightKey, mc.options.jumpKey, mc.options.sneakKey, mc.options.attackKey, mc.options.useKey}) {
                k.setPressed(false);
            }
            if (prevPerspective != null) mc.options.setPerspective(prevPerspective);
        }
        if (mc.player != null) mc.player.setSprinting(false);
        if (reloadChunks.get() && mc.worldRenderer != null) mc.worldRenderer.reload();
        hasPos = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (needInit) {
            pos = prevPos = mc.player.getEyePos();
            yaw = mc.player.getYaw();
            pitch = mc.player.getPitch();
            sForward = mc.options.forwardKey.isPressed();
            sBack = mc.options.backKey.isPressed();
            sLeft = mc.options.leftKey.isPressed();
            sRight = mc.options.rightKey.isPressed();
            sJump = mc.options.jumpKey.isPressed();
            sSneak = mc.options.sneakKey.isPressed();
            sSprint = mc.player.isSprinting();
            sAttack = mc.options.attackKey.isPressed();
            sUse = mc.options.useKey.isPressed();
            hasPos = true;
            needInit = false;
        }

        prevPos = pos;            // snapshot for per-frame interpolation
        moveCamera();
        lockBody();
        if (rotate.get()) { mc.player.setYaw(yaw); mc.player.setPitch(pitch); }
        handleActions();
    }

    /**
     * Decide what a click does while the camera is detached.
     *
     * The camera is not your body, so letting the click through untouched means swinging at whatever
     * is in front of the camera, which is nothing, most of the time.
     */
    private void handleClicks() {
        if (mc.options == null || mc.player == null) return;

        if (clickAction.get() == Click.Ignore) {
            mc.options.attackKey.setPressed(false);
            mc.options.useKey.setPressed(false);
            return;
        }

        // Real Body: the keys stay live so the game mines from your character's own crosshair.
        if (holdToMine.get()) {
            // follow the physical button rather than whatever state got latched
            mc.options.attackKey.setPressed(phys(mc.options.attackKey));
            mc.options.useKey.setPressed(phys(mc.options.useKey));
        }
    }

    private void moveCamera() {
        handleClicks();
        // Typing in chat or sitting in a GUI shouldn't drift the camera — raw key reads don't
        // know a screen is open, which made movement feel like it had a mind of its own.
        if (mc.currentScreen != null) { vel = Vec3d.ZERO; return; }
        double s = speed.get() * scrollMult;
        // Built with Vec3d.fromPolar, the same way Meteor does it. Hand-rolled sin/cos vectors are
        // where the sideways movement went wrong — the strafe axis came out mirrored.
        Vec3d forward = lookFly.get()
            ? Vec3d.fromPolar(pitch, yaw)          // follow the crosshair, pitch included
            : Vec3d.fromPolar(0, yaw);             // stay level
        Vec3d right = Vec3d.fromPolar(0, yaw + 90);
        // Build this tick's target velocity from the keys held right now.
        Vec3d target = Vec3d.ZERO;
        if (phys(mc.options.forwardKey)) target = target.add(forward);
        if (phys(mc.options.backKey)) target = target.subtract(forward);
        if (phys(mc.options.rightKey)) target = target.add(right);
        if (phys(mc.options.leftKey)) target = target.subtract(right);
        boolean keyUp = phys(mc.options.jumpKey), keyDown = phys(mc.options.sneakKey);
        if (keyUp) target = target.add(0, 1, 0);
        if (keyDown) target = target.subtract(0, 1, 0);
        if (target.lengthSquared() > 0) target = target.normalize();
        target = target.multiply(s);

        // The multiplier is applied after the direction is settled, so it changes how fast you rise
        // and fall without bending the direction you are actually heading.
        double vm = verticalSpeed.get();
        if (vm != 1.0) {
            boolean fromKeys = keyUp || keyDown;
            if ((fromKeys && verticalKeys.get()) || (!fromKeys && verticalLook.get()))
                target = new Vec3d(target.x, target.y * vm, target.z);
        }
        // Tiny acceleration ramp: ease velocity toward target (smoothing 0 = instant snap).
        double k = 1.0 - smoothing.get();
        vel = vel.add(target.subtract(vel).multiply(k));
        if (vel.lengthSquared() < 1.0e-6) vel = Vec3d.ZERO;
        pos = pos.add(vel);
    }

    private void lockBody() {
        if (allowPathing.get()) {
            // let Baritone / the player move freely; only enforce sneak if asked
            if (keepSneaking.get()) mc.options.sneakKey.setPressed(true);
            return;
        }
        mc.options.forwardKey.setPressed(sForward);
        mc.options.backKey.setPressed(sBack);
        mc.options.leftKey.setPressed(sLeft);
        mc.options.rightKey.setPressed(sRight);
        mc.options.jumpKey.setPressed(sJump);
        mc.options.sneakKey.setPressed(keepSneaking.get() || sSneak);
        mc.player.setSprinting(sSprint);
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
    }

    private void handleActions() {
        if (mc.interactionManager == null) return;
        boolean mine = (keepInputs.get() && sAttack) || (mineAtCharacter.get() && phys(mc.options.attackKey));
        boolean use  = (keepInputs.get() && sUse)    || (mineAtCharacter.get() && phys(mc.options.useKey));
        if (!mine && !use) return;

        HitResult hr = mc.player.raycast(4.5, 1.0f, false);
        if (hr.getType() != HitResult.Type.BLOCK || !(hr instanceof BlockHitResult bhr)) return;

        if (mine) {
            mc.interactionManager.updateBlockBreakingProgress(bhr.getBlockPos(), bhr.getSide());
            mc.player.swingHand(Hand.MAIN_HAND);
        } else {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private boolean phys(KeyBinding kb) {
        try {
            int code = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey()).getCode();
            return org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getHandle(), code) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Exception e) {
            return kb.isPressed();
        }
    }

    /** Wheel adjusts live fly speed (Meteor parity). Returns true if handled (so the hotbar scroll is cancelled). */
    public boolean consumeScroll(double amount) {
        if (!scrollSpeed.get() || amount == 0) return false;
        if (!scrollSpeed.get()) return;
        if (amount > 0) scrollMult *= scrollStep.get();
        else scrollMult /= scrollStep.get();
        scrollMult = MathHelper.clamp(scrollMult, 0.1, 10.0);
        return true;
    }

    public void applyLook(double dx, double dy) {
        yaw += (float) (dx * sensitivity.get());
        pitch += (float) (dy * sensitivity.get());
        pitch = MathHelper.clamp(pitch, -90f, 90f);
    }

    public boolean hasPos() { return hasPos; }
    /** Interpolated camera position for smooth per-frame rendering. */
    public Vec3d getRenderPos(float tickDelta) { return prevPos == null ? pos : prevPos.lerp(pos, tickDelta); }
    public float getFreecamYaw() { return yaw; }
    public float getFreecamPitch() { return pitch; }
}
