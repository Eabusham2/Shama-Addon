package shama.addon.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import shama.addon.util.BaritoneBridge;
import shama.addon.util.Chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mirror — copy another player's movement, so a teammate can take you somewhere while you are away.
 *
 * Mirror stays with them and does what they do: walks where they walked, jumps when they jump,
 * sneaks when they sneak, looks where they look, and flies when they fly — taking off with the
 * elytra and firing a rocket when they fire one. Shadow does the same a set time behind them.
 * Both follow the path they actually took rather than a straight line, so a ledge or lava pool they
 * went around is gone around by you too.
 *
 * Everything is done with your own keys and your own view, the way you would do it yourself:
 * nothing is sent that a player could not send, and nothing is done that the player you are
 * copying did not do. When they are too far off to copy directly, Baritone walks you back to them —
 * only walks: while mirror++ is on it is not allowed to break or place blocks, bucket-drop or
 * parkour-jump, and its own settings are put back when you stop.
 * Pressing a movement key yourself pauses the copying until you let go.
 *
 * Start it with .mirror <player> or .shadow <player> [seconds]; .mirror stop or .shadow stop ends it.
 */
public class Mirror extends Module {
    public enum Mode { Mirror, Shadow }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMove = settings.createGroup("Moving");
    private final SettingGroup sgFly = settings.createGroup("Flying");

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Mirror stays with the player and copies them as they go. Shadow copies them a set time behind, along the exact path they took.")
        .defaultValue(Mode.Mirror).build());
    public final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("player").description("Who to copy. Set by .mirror <player> and .shadow <player>.")
        .defaultValue("").build());
    public final Setting<Double> delay = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay").description("How far behind them Shadow follows, in seconds of their path.")
        .defaultValue(1.0).min(0.1).max(30).sliderRange(0.2, 10).decimalPlaces(1).visible(() -> mode.get() == Mode.Shadow).build());
    private final Setting<Boolean> pauseOnInput = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-you-move").description("Pressing a movement key yourself pauses the copying until you let go, so you can always take back control.")
        .defaultValue(true).build());
    private final Setting<Integer> lostAfter = sgGeneral.add(new IntSetting.Builder()
        .name("lost-after").description("Seconds the player can be out of sight before copying stops. Until then you carry on along their path to where they were last seen.")
        .defaultValue(30).range(5, 600).sliderRange(10, 120).build());

    private final Setting<Boolean> useBaritone = sgMove.add(new BoolSetting.Builder()
        .name("baritone-catch-up").description("When the spot you are copying is further away than catch-up-distance, let Baritone walk you to it, around walls and obstacles. It only walks: no breaking or placing blocks, no bucket drops, no parkour jumps, and your own Baritone settings come back when you stop. Off, you head for their path and walk it the whole way.")
        .defaultValue(true).build());
    private final Setting<Integer> catchUp = sgMove.add(new IntSetting.Builder()
        .name("catch-up-distance").description("How far away the spot you are copying can be before Baritone takes over to get you there. Copying resumes once you are back within half of this.")
        .defaultValue(10).range(3, 64).sliderRange(4, 32).visible(useBaritone::get).build());
    private final Setting<Boolean> copyLook = sgMove.add(new BoolSetting.Builder()
        .name("copy-look").description("Look where they looked when you are with them, moving sideways or backwards as needed like you would yourself. Off, you look where you are walking.")
        .defaultValue(true).build());
    private final Setting<Integer> turnSpeed = sgMove.add(new IntSetting.Builder()
        .name("turn-speed").description("The most your view turns in one tick, in degrees. Lower looks calmer; higher keeps up with sharp turns.")
        .defaultValue(30).range(5, 90).sliderRange(10, 60).build());

    private final Setting<Boolean> elytra = sgFly.add(new BoolSetting.Builder()
        .name("elytra").description("Fly when they fly: jump and open your elytra where they took off, and steer along their flight path. Needs an elytra on; without one Baritone walks underneath them.")
        .defaultValue(true).build());
    private final Setting<Boolean> rockets = sgFly.add(new BoolSetting.Builder()
        .name("copy-rockets").description("Fire a rocket where they fired one, from your offhand or hotbar, switching back afterwards. Only ever when they did.")
        .defaultValue(true).visible(elytra::get).build());

    public Mirror() {
        super(shama.addon.ShamaAddon.MOVEMENT, "mirror++",
            "Copy another player's movement — with them, or a set time behind along their path — walking, jumping, sneaking and flying with them. Start with .mirror <player> or .shadow <player> [seconds].");
    }

    // ---------------------------------------------------------------- what they did, tick by tick

    private record Sample(double x, double y, double z, float yaw, float pitch, boolean sneak, boolean sprint,
                          boolean glide, boolean ground, boolean rocket, long tick) {}

    private final List<Sample> trail = new ArrayList<>();
    private final Set<Integer> seenRockets = new HashSet<>();
    private int pointer;
    private long tick, lastSeenTick, lastRocketUsedTick = -100, consumedRocketTick = -1, lastGoalTick = -100;
    private boolean baritoneOn, jumpToggle;
    private int lastGoalX = Integer.MIN_VALUE, lastGoalY, lastGoalZ;
    private PlayerEntity followed;
    private ClientWorld lastWorld;

    @Override
    public void onActivate() {
        trail.clear(); seenRockets.clear();
        pointer = 0; tick = 0; lastSeenTick = 0; consumedRocketTick = -1; lastRocketUsedTick = -100; lastGoalTick = -100;
        baritoneOn = false; followed = null; lastGoalX = Integer.MIN_VALUE; lastWorld = mc.world;
        if (target.get().isBlank()) {
            Chat.error("[mirror] No player set. Use .mirror <player> or .shadow <player>.");
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (baritoneOn && BaritoneUtils.IS_AVAILABLE) BaritoneBridge.stop();
        if (BaritoneUtils.IS_AVAILABLE) BaritoneBridge.restoreWalkOnly();
        baritoneOn = false;
        handBackKeys();
        trail.clear();
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) { if (isActive()) toggle(); }

    @Override
    public String getInfoString() { return target.get().isBlank() ? null : mode.get().name() + " " + target.get(); }

    /** Start copying someone. Used by the commands. */
    public void copy(String name, Mode m, Double seconds) {
        target.set(name);
        mode.set(m);
        if (seconds != null) delay.set(seconds);
        if (isActive()) toggle();
        toggle();
    }

    // ---------------------------------------------------------------- keys

    private KeyBinding[] moveKeys() {
        return new KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey,
            mc.options.rightKey, mc.options.jumpKey, mc.options.sneakKey};
    }

    /** Physically held right now, read from the keyboard rather than the game's own state. */
    private boolean held(KeyBinding kb) {
        try {
            InputUtil.Key key = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
            long h = mc.getWindow().getHandle();
            if (key.getCategory() == InputUtil.Type.MOUSE)
                return org.lwjgl.glfw.GLFW.glfwGetMouseButton(h, key.getCode()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            return org.lwjgl.glfw.GLFW.glfwGetKey(h, key.getCode()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        } catch (Throwable t) { return false; }
    }

    private boolean youAreMoving() {
        if (mc.currentScreen != null) return false;
        for (KeyBinding k : moveKeys()) if (held(k)) return true;
        return false;
    }

    /** Give every key back in whatever state you are physically holding it. */
    private void handBackKeys() {
        if (mc.options == null) return;
        for (KeyBinding k : moveKeys()) k.setPressed(mc.currentScreen == null && held(k));
    }

    private void keys(boolean f, boolean b, boolean l, boolean r, boolean jump, boolean sneak) {
        mc.options.forwardKey.setPressed(f);
        mc.options.backKey.setPressed(b);
        mc.options.leftKey.setPressed(l);
        mc.options.rightKey.setPressed(r);
        mc.options.jumpKey.setPressed(jump);
        mc.options.sneakKey.setPressed(sneak);
    }

    /** Turn your view toward a direction, no faster than turn-speed a tick. */
    private void turnTo(float yaw, float pitch) {
        float max = turnSpeed.get();
        float dy = MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        float dp = pitch - mc.player.getPitch();
        mc.player.setYaw(mc.player.getYaw() + MathHelper.clamp(dy, -max, max));
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + MathHelper.clamp(dp, -max, max), -90, 90));
    }

    // ---------------------------------------------------------------- each tick

    private PlayerEntity findTarget() {
        if (followed != null && !followed.isRemoved() && followed.getEntityWorld() == mc.world) return followed;
        followed = null;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.getName().getString().equalsIgnoreCase(target.get())) { followed = p; break; }
        }
        return followed;
    }

    /** A rocket that has just appeared and belongs to them is one they fired. Yours never count. */
    private boolean firedRocket(PlayerEntity p) {
        boolean fired = false;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof FireworkRocketEntity fw) || e.age > 3 || !seenRockets.add(e.getId())) continue;
            Entity owner = fw.getOwner();
            if (owner == mc.player) continue;
            boolean theirs = owner != null ? owner == p
                // owner not known: theirs if it is on them and nearer them than you
                : fw.squaredDistanceTo(p) < 2.5 * 2.5 && fw.squaredDistanceTo(p) < fw.squaredDistanceTo(mc.player)
                  && tick - lastRocketUsedTick > 20;
            if (theirs) fired = true;
        }
        if (seenRockets.size() > 512) seenRockets.clear();
        return fired;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        // through a portal: the old path is in another dimension
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            trail.clear(); seenRockets.clear(); pointer = 0; followed = null;
            if (baritoneOn) { BaritoneBridge.stop(); baritoneOn = false; }
        }

        PlayerEntity t = findTarget();
        if (t != null) {
            lastSeenTick = tick;
            trail.add(new Sample(t.getX(), t.getY(), t.getZ(), t.getYaw(), t.getPitch(), t.isSneaking(), t.isSprinting(),
                t.isGliding(), t.isOnGround(), firedRocket(t), tick));
            if (trail.size() > 6000) { trail.subList(0, 2000).clear(); pointer = Math.max(0, pointer - 2000); }
        } else if (tick - lastSeenTick > lostAfter.get() * 20L) {
            Chat.warning("[mirror] Lost %s for %d seconds; stopped.", target.get(), lostAfter.get());
            toggle();
            return;
        }
        if (trail.isEmpty()) return;

        // you are steering yourself: stand aside until you let go
        if (pauseOnInput.get() && youAreMoving()) {
            if (baritoneOn) { BaritoneBridge.stop(); baritoneOn = false; }
            handBackKeys();
            return;
        }

        drive(pickGoal());
    }

    /**
     * The point of their path to head for. Shadow only uses points at least the delay old; Mirror can
     * use all of it. Either way the pointer moves along the path as you reach each point, and skips
     * ahead to whichever point you happen to be closest to, so a corner you cut is not walked back for.
     * Mirror jumps straight to where they are now when that is too far to walk their path to.
     */
    private Sample pickGoal() {
        int last = trail.size() - 1;
        boolean mirror = mode.get() == Mode.Mirror;
        boolean baritoneWalks = useBaritone.get() && BaritoneUtils.IS_AVAILABLE && !mc.player.isGliding()
            && !(trail.get(last).glide() && canFly());
        if (mirror && baritoneWalks && dist2(trail.get(last)) > (double) catchUp.get() * catchUp.get()) {
            pointer = last;
            return trail.get(last);
        }

        int newest = last;
        if (!mirror) {
            long cutoff = tick - Math.round(delay.get() * 20);
            while (newest > 0 && trail.get(newest).tick() > cutoff) newest--;
        }
        if (pointer > newest) pointer = newest;

        double best = dist2(trail.get(pointer));
        int bestI = pointer;
        for (int i = pointer + 1; i <= newest && i <= pointer + 200; i++) {
            double d = dist2(trail.get(i));
            if (d < best) { best = d; bestI = i; }
        }
        pointer = bestI;

        // aim a little ahead along the path: a block on foot, further the faster you fly
        double ahead = mc.player.isGliding() ? Math.max(4, mc.player.getVelocity().length() * 8) : 1;
        while (pointer < newest && dist2(trail.get(pointer)) < ahead * ahead) pointer++;
        return trail.get(pointer);
    }

    /** Elytra on and flying allowed. */
    private boolean canFly() {
        return elytra.get() && LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private double dist2(Sample s) {
        double dx = s.x() - mc.player.getX(), dy = s.y() - mc.player.getY(), dz = s.z() - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void drive(Sample goal) {
        double dx = goal.x() - mc.player.getX(), dy = goal.y() - mc.player.getY(), dz = goal.z() - mc.player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz), d3 = Math.sqrt(horiz * horiz + dy * dy);
        boolean gliding = mc.player.isGliding();
        boolean canFly = canFly();

        // a rocket they fired at this point of their path: fire one too
        if (rockets.get() && canFly && gliding) {
            for (int i = Math.max(0, pointer - 5); i <= pointer; i++) {
                Sample s = trail.get(i);
                if (s.rocket() && s.tick() > consumedRocketTick) { consumedRocketTick = s.tick(); fireRocket(); break; }
            }
        }

        // too far to copy directly: Baritone walks you there. If they are flying and you cannot,
        // it walks underneath them instead.
        boolean flyingWithoutYou = goal.glide() && !canFly;
        boolean baritoneOk = useBaritone.get() && BaritoneUtils.IS_AVAILABLE && !gliding && (!goal.glide() || flyingWithoutYou);
        double far = flyingWithoutYou ? horiz : d3;
        if (baritoneOk && (far > catchUp.get() || (baritoneOn && far > catchUp.get() / 2.0))) {
            int gx = MathHelper.floor(goal.x()), gy = MathHelper.floor(goal.y()), gz = MathHelper.floor(goal.z());
            boolean moved = Math.abs(gx - lastGoalX) + Math.abs(gy - lastGoalY) + Math.abs(gz - lastGoalZ) > 3;
            // a new goal when they have moved on, or now and then when Baritone has given up
            if (!baritoneOn || moved || (!BaritoneBridge.busy() && tick - lastGoalTick > 20)) {
                BaritoneBridge.walkOnly();
                if (flyingWithoutYou) BaritoneBridge.goXZ(gx, gz);
                else BaritoneBridge.goNear(gx, gy, gz, 1);
                lastGoalX = gx; lastGoalY = gy; lastGoalZ = gz; lastGoalTick = tick;
            }
            if (!baritoneOn) handBackKeys();
            baritoneOn = true;
            return;
        }
        if (baritoneOn) { BaritoneBridge.stop(); baritoneOn = false; }

        // flying
        if (canFly && (goal.glide() || gliding)) {
            if (!gliding) {
                // on the ground, jump; in the air, tap jump again to open the elytra
                if (mc.player.isOnGround()) keys(false, false, false, false, true, false);
                else { jumpToggle = !jumpToggle; keys(false, false, false, false, jumpToggle, false); }
                turnTo(goal.yaw(), goal.pitch());
                return;
            }
            keys(false, false, false, false, false, false);
            // steer at the point you are copying; when that is where they are now, a little ahead of them
            double ax = dx, ay = dy, az = dz;
            if (pointer == trail.size() - 1 && followed != null) {
                Vec3d v = followed.getVelocity();
                ax += v.x * 6; ay += v.y * 6; az += v.z * 6;
            }
            double h = Math.sqrt(ax * ax + az * az);
            float yaw = h < 0.5 ? goal.yaw() : (float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0);
            // hold the pitch they held at that point, corrected a little toward the point itself,
            // so you glide like they glided instead of climbing where they had a rocket and you do not
            float aim = (float) -Math.toDegrees(Math.atan2(ay, Math.max(0.1, h)));
            float pitch = goal.pitch() + MathHelper.clamp(aim - goal.pitch(), -15f, 15f);
            turnTo(yaw, MathHelper.clamp(pitch, -90f, 90f));
            return;
        }

        // on foot
        float moveYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        boolean close = horiz < 3;
        float lookYaw = copyLook.get() && close ? goal.yaw() : (horiz > 0.35 ? moveYaw : mc.player.getYaw());
        float lookPitch = copyLook.get() && close ? goal.pitch() : 0f;
        turnTo(lookYaw, lookPitch);

        boolean f = false, b = false, l = false, r = false;
        if (horiz > 0.35) {
            // which keys move you toward the spot, from the way you are facing now
            float rel = MathHelper.wrapDegrees(moveYaw - mc.player.getYaw());
            f = Math.abs(rel) < 67.5f;
            b = Math.abs(rel) > 112.5f;
            r = rel > 22.5f && rel < 157.5f;
            l = rel < -22.5f && rel > -157.5f;
        }
        boolean onGround = mc.player.isOnGround();
        boolean jump = onGround && horiz > 0.2 && ((dy > 0.6 && horiz < 3) || mc.player.horizontalCollision);
        if (onGround && !goal.ground() && dy > 0.2 && horiz < 2) jump = true;      // they jumped here: so do you
        if (mc.player.isTouchingWater() && dy > 0.2) jump = true;                   // swim up after them
        keys(f, b, l, r, jump, goal.sneak());
        mc.player.setSprinting(f && !goal.sneak() && (goal.sprint() || horiz > 4));
    }

    private void fireRocket() {
        if (mc.interactionManager == null) return;
        FindItemResult r = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!r.found()) return;
        if (r.isOffhand()) mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        else {
            InvUtils.swap(r.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        }
        lastRocketUsedTick = tick;
    }
}
