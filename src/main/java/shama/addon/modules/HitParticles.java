package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.EntityHitResult;

/** Hit Particles — spawns a burst of particles on the entity you attack. */
public class HitParticles extends Module {
    public enum Particle { Crit, Damage, Flame, Heart, Explosion }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Particle> particle = sg.add(new EnumSetting.Builder<Particle>().name("particle").description("Which particle to use.").defaultValue(Particle.Crit).build());
    private final Setting<Integer> count = sg.add(new IntSetting.Builder().name("count").description("How many particles to spawn.").defaultValue(12).min(1).max(60).sliderRange(4, 30).build());

    private boolean wasAttacking;

    public HitParticles() { super(shama.addon.ShamaAddon.COMBAT, "hit-particles++", "Spawns particles on the entity you attack."); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.options == null) return;
        boolean attacking = mc.options.attackKey.isPressed();
        boolean edge = attacking && !wasAttacking;   // rising edge = a fresh click
        wasAttacking = attacking;
        if (!edge) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult hit)) return;

        var e = hit.getEntity();
        var type = switch (particle.get()) {
            case Crit -> ParticleTypes.CRIT;
            case Damage -> ParticleTypes.DAMAGE_INDICATOR;
            case Flame -> ParticleTypes.FLAME;
            case Heart -> ParticleTypes.HEART;
            case Explosion -> ParticleTypes.EXPLOSION;
        };
        for (int i = 0; i < count.get(); i++) {
            double ox = (mc.world.random.nextDouble() - 0.5) * e.getWidth();
            double oy = mc.world.random.nextDouble() * e.getHeight();
            double oz = (mc.world.random.nextDouble() - 0.5) * e.getWidth();
            mc.world.addParticleClient(type, e.getX() + ox, e.getY() + oy, e.getZ() + oz,
                (mc.world.random.nextDouble() - 0.5) * 0.4, mc.world.random.nextDouble() * 0.3, (mc.world.random.nextDouble() - 0.5) * 0.4);
        }
    }
}
