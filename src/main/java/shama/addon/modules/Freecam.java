package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
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

    private final Setting<Boolean> scrollRemembers = sgGeneral.add(new BoolSetting.Builder()
        .name("scroll-remembers")
        .description("Keep whatever you scrolled to for next time instead of snapping back to the slider value when you close the camera. Off means the scroll only lasts for that session.")
        .defaultValue(true).build());

    private final Setting<Click> clickAction = sgGeneral.add(new EnumSetting.Builder<Click>()
        .name("click-action")
        .description("What a mouse click does while the camera is out. Your body is not where the camera is, so a click used to swing at whatever happened to be in front of the camera, which is usually thin air. Ignore drops the click entirely; Real Body mines and uses where your actual character is looking, which is what you would get with the camera closed.")
        .defaultValue(Click.RealBody).build());

    private final Setting<Boolean> holdToMine = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-mine")
        .description("Hold left click to mine from your real character's crosshair, and stop the moment you let go. Off ignores left click entirely while the camera is out.")
        .defaultValue(true).visible(() -> clickAction.get() == Click.RealBody).build());

    private final Setting<Boolean> holdToUse = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-to-use")
        .description("Hold right click to use whatever you are holding, from your real character rather than the camera. Held is held, exactly as with the camera closed: food keeps being eaten until it is finished or you let go, a bow keeps drawing, a shield stays up, and rockets keep firing while you fly the camera around.")
        .defaultValue(true).visible(() -> clickAction.get() == Click.RealBody).build());

    private final Setting<Boolean> holdLadders = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-on-ladders")
        .description("Keep your character where it is on a ladder, vine or chain of cave vines while the camera is out, instead of sliding down it. Your body gets no keys while you fly the camera, and on a ladder no keys means slipping — this holds sneak for it there, the same thing you would do yourself. If you were climbing when you opened the camera it keeps climbing; scaffolding is left alone, since sneaking on it takes you down.")
        .defaultValue(true).build());
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
        .name("keep-inputs").description("Lock the attack/use you held on entry so you keep mining/using at your character's view.").defaultValue(false).build());
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
            // hand every key back in the state it is physically in, so whatever you are holding as the
            // camera closes carries straight on instead of needing to be pressed again
            for (KeyBinding k : new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey,
                mc.options.rightKey, mc.options.jumpKey, mc.options.sneakKey, mc.options.attackKey, mc.options.useKey}) {
                k.setPressed(mc.currentScreen == null && phys(k));
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


    private void moveCamera() {
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
        mc.options.sneakKey.setPressed(keepSneaking.get() || sSneak || holdOnLadder());
        mc.player.setSprinting(sSprint);
    }

    /** On a ladder or vine with nothing held that would climb it, so it would slide down. */
    private boolean holdOnLadder() {
        if (!holdLadders.get() || mc.player == null) return false;
        if (sForward || sBack || sLeft || sRight || sJump) return false;          // it is climbing; leave it
        if (!mc.player.isClimbing()) return false;
        return !mc.player.getBlockStateAtPos().isOf(net.minecraft.block.Blocks.SCAFFOLDING);
    }

    /** Throw away clicks the game has queued for this button, so they never reach it. */
    private static void drain(KeyBinding kb) {
        int guard = 64;
        while (kb.wasPressed() && guard-- > 0) { /* consumed */ }
    }

    /**
     * What your character does with the mouse buttons while the camera is out.
     *
     * The game already aims clicks from your character, not from the camera: the crosshair target is
     * worked out from your body's eyes and facing every tick. So the buttons are handed to the game
     * as they are rather than acted out here, and everything that depends on holding a button keeps
     * working exactly as it does with the camera closed — eating, drawing a bow, holding a shield,
     * mining a block through to the end, firing rockets.
     *
     * What is held back: a button you have switched off here, and a left click when nothing is in
     * front of your character, which would otherwise swing at thin air. Queued clicks are thrown
     * away in those cases too, since the game acts on the click count as well as on the held state.
     * This runs before the game reads the buttons each tick.
     */
    private void handleActions() {
        if (mc.options == null) return;
        KeyBinding atk = mc.options.attackKey, use = mc.options.useKey;
        boolean screen = mc.currentScreen != null;
        boolean real = mineAtCharacter.get() && clickAction.get() == Click.RealBody;

        boolean mineHeld = !screen && ((keepInputs.get() && sAttack) || (real && holdToMine.get() && phys(atk)));
        boolean useHeld  = !screen && ((keepInputs.get() && sUse)    || (real && holdToUse.get()  && phys(use)));

        HitResult target = mc.crosshairTarget;
        boolean atAir = target == null || target.getType() == HitResult.Type.MISS;

        if (mineHeld && !atAir) atk.setPressed(true);
        else { atk.setPressed(false); drain(atk); }

        if (useHeld) use.setPressed(true);
        else { use.setPressed(false); drain(use); }
    }

    /**
     * Whether the button bound to this key is physically down right now.
     *
     * Mouse buttons have to be asked for as mouse buttons: asking the keyboard about code 0 or 1
     * always says "not pressed", which is why holding a mouse button in the camera used to do
     * nothing at all.
     */
    private boolean phys(KeyBinding kb) {
        try {
            InputUtil.Key key = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
            long handle = mc.getWindow().getHandle();
            if (key.getCategory() == InputUtil.Type.MOUSE)
                return org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, key.getCode()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            return org.lwjgl.glfw.GLFW.glfwGetKey(handle, key.getCode()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Exception e) {
            return kb.isPressed();
        }
    }

    /** Wheel adjusts live fly speed (Meteor parity). Returns true if handled (so the hotbar scroll is cancelled). */
    public boolean consumeScroll(double amount) {
        if (!scrollSpeed.get() || amount == 0) return false;
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
