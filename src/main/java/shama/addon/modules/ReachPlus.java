package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Reach++ — extended-range attacking with selectable targeting and rotation.
 *
 * Reality check: the SERVER validates attack range. Beyond ~3 blocks, vanilla and
 * anti-cheats reject the hit; this can't change that. Singleplayer / lenient servers
 * get the extended reach; strict servers cap you at vanilla range.
 *
 * target-priority: Crosshair = only what you're looking at (most legit-looking);
 * the others auto-pick within range like a killaura. Silent rotation sends look
 * packets so the server sees your aim without your camera moving.
 */
public class ReachPlus extends Module {
    public enum Target { Crosshair, Nearest, LowestHealth, LowestArmor, Random }
    public enum Rotation { None, Client, Silent }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Target> target = sgGeneral.add(new EnumSetting.Builder<Target>()
        .name("target-priority")
        .description("Crosshair = only what you aim at; others auto-select within range.")
        .defaultValue(Target.Crosshair)
        .build()
    );

    private final Setting<Rotation> rotation = sgGeneral.add(new EnumSetting.Builder<Rotation>()
        .name("rotation")
        .description("None = don't aim; Client = turn your view; Silent = send look packets only (server sees the aim, camera stays put).")
        .defaultValue(Rotation.None)
        .visible(() -> target.get() != Target.Crosshair)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Attack range in blocks (vanilla is ~3).").defaultValue(5.0).min(1.0).sliderRange(3.0, 10.0).build());

    private final Setting<Boolean> autoAttack = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-attack").description("Automatically attack the selected target within range.").defaultValue(false).build());

    private final Setting<Boolean> hardCap = sgGeneral.add(new BoolSetting.Builder()
        .name("hard-cap")
        .description("Also raise the vanilla attack-range attribute directly (in addition to the extended-range targeting above), for servers that read that attribute instead of just validating hit distance.")
        .defaultValue(false).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("attack-delay").description("Ticks between auto-attacks (lower = faster).").defaultValue(10).range(1, 40).sliderRange(1, 20).visible(autoAttack::get).build());

    private final Setting<Boolean> playersOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("players-only").description("Only target players.").defaultValue(false).build());

    private int timer;

    public ReachPlus() {
        super(shama.addon.ShamaAddon.PLAYER, "reach++", "Extends attack range with selectable targeting. Rejected by strict server anti-cheats.");
    }

    private static final Identifier REACH_MOD_ID = Identifier.of("shama", "reach_plus_hardcap");

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        applyHardCap();
        if (!autoAttack.get()) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer++ < delay.get()) return;
        timer = 0;

        Entity t = target.get() == Target.Crosshair ? raycastEntity(range.get()) : selectTarget(range.get());
        if (t == null) return;

        if (target.get() != Target.Crosshair && rotation.get() != Rotation.None) aim(t);

        mc.interactionManager.attackEntity(mc.player, t);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Entity the player is looking at, up to maxRange. */
    private Entity raycastEntity(double maxRange) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d end = eyes.add(look.multiply(maxRange));
        Entity best = null;
        double bestDist = maxRange * maxRange;

        Box search = mc.player.getBoundingBox().stretch(look.multiply(maxRange)).expand(1.0);
        for (Entity e : mc.world.getOtherEntities(mc.player, search)) {
            if (!(e instanceof LivingEntity) || !e.isAlive()) continue;
            if (playersOnly.get() && !(e instanceof PlayerEntity)) continue;
            Box box = e.getBoundingBox().expand(e.getTargetingMargin());
            var hit = box.raycast(eyes, end);
            if (hit.isPresent()) {
                double d = eyes.squaredDistanceTo(hit.get());
                if (d < bestDist) { bestDist = d; best = e; }
            }
        }
        return best;
    }

    /** Auto-pick a target within range by the chosen priority. */
    private Entity selectTarget(double maxRange) {
        double maxSq = maxRange * maxRange;
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity le) || !e.isAlive()) continue;
            if (playersOnly.get() && !(e instanceof PlayerEntity)) continue;
            double distSq = mc.player.squaredDistanceTo(e);
            if (distSq > maxSq) continue;
            double score = switch (target.get()) {
                case Nearest -> distSq;
                case LowestHealth -> le.getHealth();
                case LowestArmor -> le.getArmor();
                case Random -> ThreadLocalRandom.current().nextDouble();
                case Crosshair -> distSq; // unreachable here
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
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        }
    }

    private void applyHardCap() {
        if (mc.player == null) return;
        EntityAttributeInstance attr = mc.player.getAttributeInstance(EntityAttributes.ENTITY_INTERACTION_RANGE);
        if (attr == null) return;
        attr.removeModifier(REACH_MOD_ID);
        if (hardCap.get()) {
            attr.addTemporaryModifier(new EntityAttributeModifier(REACH_MOD_ID, range.get() - attr.getBaseValue(), EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;
        EntityAttributeInstance attr = mc.player.getAttributeInstance(EntityAttributes.ENTITY_INTERACTION_RANGE);
        if (attr != null) attr.removeModifier(REACH_MOD_ID);
    }

    @Override
    public String getInfoString() {
        return target.get().name().toLowerCase();
    }
}
