package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import shama.addon.util.Humanize;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wallbang — attacks a target within range through walls (no line-of-sight).
 *
 * Reality check: the SERVER decides if a hit lands. LOS-validating servers and
 * anti-cheats reject through-wall hits — this can't change that, only the server can.
 * Singleplayer / lenient servers only.
 *
 * The target-priority modes and Silent rotation are the useful, server-relevant knobs:
 * Silent aim sends look packets so the server sees you facing the target while your
 * camera stays put, which is what a server actually checks — without the tell of your
 * view snapping around.
 */
public class Wallbang extends Module {
    public enum Target { Nearest, LowestHealth, LowestArmor, ClosestAngle, Random }
    public enum Rotation { None, Client, Silent }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Target> target = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("target-priority")
        .description("Which entity to hit when several are in range.")
        .defaultValue(Target.Nearest)
        .build()
    );

    private final Setting<Rotation> rotation = sgGeneral.add(new EnumSetting.Builder<Rotation>()
        .name("rotation")
        .description("None = don't aim; Client = turn your view; Silent = send look packets only (server sees the aim, your camera doesn't move).")
        .defaultValue(Rotation.None)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Max distance to a target (blocks).").defaultValue(4.5).min(1.0).sliderRange(3.0, 8.0).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("attack-delay").description("Ticks between attacks (lower = faster).").defaultValue(10).range(1, 40).sliderRange(1, 20).build());
    private final Setting<Integer> missChance = sgGeneral.add(new IntSetting.Builder().name("miss-chance").description("Chance (%) to skip an attack this cycle, like a human misclicking. 0 = never miss.").defaultValue(0).min(0).max(100).sliderRange(0,40).build());
    private final Setting<Integer> timingJitter = sgGeneral.add(new IntSetting.Builder().name("timing-jitter").description("Vary the attack delay by +/- this % so it isn't a perfect fixed rhythm.").defaultValue(0).min(0).max(100).sliderRange(0,60).build());

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only").description("Only target players.").defaultValue(true).build());

    private int timer;

    public Wallbang() {
        super(shama.addon.ShamaAddon.COMBAT, "wallbang++", "Lets you attack targets through walls. Whether it lands depends on the server.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer++ < Humanize.jitter(delay.get(), timingJitter.get())) return;
        timer = 0;

        Entity t = selectTarget(range.get());
        if (t == null) return;

        if (rotation.get() != Rotation.None) aim(t);

        if (Humanize.shouldMiss(missChance.get())) { timer = 0; return; }
        mc.interactionManager.attackEntity(mc.player, t);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private Entity selectTarget(double maxRange) {
        double maxSq = maxRange * maxRange;
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        Vec3d look = mc.player.getRotationVec(1.0f);

        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity le) || !e.isAlive()) continue;
            if (playersOnly.get() && !(e instanceof PlayerEntity)) continue;
            double distSq = mc.player.squaredDistanceTo(e);
            if (distSq > maxSq) continue;

            double score = switch (target.get()) {
                case Nearest -> distSq;
                case LowestHealth -> le.getHealth();
                case LowestArmor -> le.getArmor();
                case ClosestAngle -> {
                    Vec3d dir = e.getEyePos().subtract(mc.player.getEyePos()).normalize();
                    yield -look.dotProduct(dir); // smaller = closer to crosshair
                }
                case Random -> ThreadLocalRandom.current().nextDouble();
            };
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    private void aim(Entity e) {
        double dx = e.getX() - mc.player.getX();
        double dy = (e.getY() + e.getStandingEyeHeight()) - mc.player.getEyeY();
        double dz = e.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (rotation.get() == Rotation.Client) {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        } else if (mc.getNetworkHandler() != null) {
            // Silent: server sees the aim, camera doesn't move.
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    @Override
    public String getInfoString() {
        return target.get().name().toLowerCase();
    }
}
