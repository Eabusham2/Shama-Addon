package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * Noclip — phase through blocks. Singleplayer / own worlds only.
 *
 * How it actually works (and why the old version bounced): just setting the
 * noClip field on a walking player isn't enough — the player tick re-applies
 * collision. The reliable way is to put the player into the FLYING state (the
 * same one creative flight uses) AND set noClip, then drive movement manually.
 * That's how the standalone noclip mods do it. On a server the server still
 * collides you, so this is for worlds you host.
 */
public class Noclip extends Module {
    public enum Mode {
        Flying, // put player in the FLYING ability state + noClip (most reliable phasing)
        Motion  // only noClip + manual velocity, never touches the ability flag
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Flying uses the creative-flight state to make phasing stick (most reliable). Motion only sets the noClip flag and drives velocity, never touching the ability flag — lighter, and some servers react to it differently.")
        .defaultValue(Mode.Flying)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal move speed (blocks/tick).")
        .defaultValue(0.3)
        .min(0.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    private final Setting<Double> vertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Up/down speed for jump/sneak (blocks/tick).")
        .defaultValue(0.3)
        .min(0.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    private final Setting<Boolean> fly = sgGeneral.add(new BoolSetting.Builder()
        .name("fly")
        .description("Hold your altitude and fly freely while phasing (no falling). Turn off to let gravity/auto-descend take over.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDescend = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-descend")
        .description("Sink straight down through blocks automatically, no need to hold sneak. Good for dropping through a floor. Ignored while fly is on unless you also press sneak.")
        .defaultValue(false)
        .build()
    );

    private boolean prevAllowFlying;
    private boolean prevFlying;
    private boolean prevNoGravity;

    public Noclip() {
        super(shama.addon.ShamaAddon.MOVEMENT, "noclip++", "Lets you move freely through blocks. Works where the server doesn't correct your position (own worlds and lenient servers).");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        // Remember state so we can cleanly restore on deactivate.
        prevAllowFlying = mc.player.getAbilities().allowFlying;
        prevFlying = mc.player.getAbilities().flying;
        prevNoGravity = mc.player.hasNoGravity();

        // Put the player into the flying state — this is what makes phasing stick
        // in Flying mode. Motion mode leaves the ability flag alone.
        if (mode.get() == Mode.Flying) {
            mc.player.getAbilities().allowFlying = true;
            mc.player.getAbilities().flying = true;
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        mc.player.noClip = false;
        mc.player.setNoGravity(prevNoGravity);
        mc.player.getAbilities().allowFlying = prevAllowFlying;
        mc.player.getAbilities().flying = prevFlying;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Keep phasing on: collision off, gravity off.
        mc.player.noClip = true;
        mc.player.setNoGravity(true);
        if (mode.get() == Mode.Flying) mc.player.getAbilities().flying = true;
        mc.player.setOnGround(false);

        // --- Horizontal movement from your actual keys + where you look ---
        double mx = 0, mz = 0;
        boolean f = mc.options.forwardKey.isPressed();
        boolean b = mc.options.backKey.isPressed();
        boolean l = mc.options.leftKey.isPressed();
        boolean r = mc.options.rightKey.isPressed();

        if (f || b || l || r) {
            float yaw = (float) Math.toRadians(mc.player.getYaw());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);

            double forward = (f ? 1 : 0) - (b ? 1 : 0);
            double strafe = (r ? 1 : 0) - (l ? 1 : 0);

            // forward is -sin/+cos, strafe is +cos/+sin in MC's coordinate space
            mx = (forward * -sin + strafe * cos);
            mz = (forward * cos + strafe * sin);

            double len = Math.sqrt(mx * mx + mz * mz);
            if (len > 0) {
                mx = mx / len * speed.get();
                mz = mz / len * speed.get();
            }
        }

        // --- Vertical ---
        double up = 0;
        boolean jump = mc.options.jumpKey.isPressed();
        boolean sneak = mc.options.sneakKey.isPressed();

        if (jump) up += vertical.get();
        if (sneak) up -= vertical.get();

        // auto-descend: sink without holding sneak. If fly is on, only auto-sink
        // when you're not actively flying up.
        if (autoDescend.get() && !jump) {
            if (!fly.get() || up == 0) up = -vertical.get();
        }

        // If fly is off and nothing pressed and not auto-descending, let it hold
        // (no gravity) so you don't rocket anywhere.
        mc.player.setVelocity(mx, up, mz);
    }

    @Override
    public String getInfoString() {
        return fly.get() ? "fly" : (autoDescend.get() ? "descend" : "manual");
    }
}
