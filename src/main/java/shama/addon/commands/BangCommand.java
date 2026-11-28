package shama.addon.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.PlayerArgumentType;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.Input;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import shama.addon.modules.Bang;
import shama.addon.util.Chat;

public final class BangCommand extends Command {
    public static final BangCommand INSTANCE = new BangCommand();
    public static final float TRAVEL = 0.18f;

    private Bang module;
    private PlayerEntity target;
    private int startAge;

    private BangCommand() {
        super("bang", "Start/stop the bang++ module. .bang <player> [speed] starts; .bang or .unbang stops.", "unbang");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            Bang m = Modules.get().get(Bang.class);
            if (m != null && m.isActive()) {
                m.toggle();
                Chat.info("[bang] Stopped.");
            } else {
                Chat.info("[bang] Module not active. Use %s <player> [speed] to start.", this);
            }
            return SINGLE_SUCCESS;
        });

        if (builder.getLiteral().equals(getName())) {
            builder.then(argument("player", PlayerArgumentType.create())
                .executes(ctx -> start(PlayerArgumentType.get(ctx), null))
                .then(argument("speed", DoubleArgumentType.doubleArg(0.1, 10))
                    .executes(ctx -> start(PlayerArgumentType.get(ctx), DoubleArgumentType.getDouble(ctx, "speed")))));
        }
    }

    private int start(PlayerEntity player, Double speed) {
        Bang m = Modules.get().get(Bang.class);
        if (m == null) {
            Chat.error("[bang] Module not found.");
            return 0;
        }
        if (player == mc.player) {
            Chat.error("[bang] Pick a player other than yourself.");
            return 0;
        }
        if (!player.isAlive() || !mc.player.isAlive()) {
            Chat.error("[bang] Both players must be alive.");
            return 0;
        }
        if (mc.player.hasVehicle() || mc.player.isSleeping()) {
            Chat.error("[bang] Dismount or get out of bed first.");
            return 0;
        }

        m.target.set(player.getName().getString());
        if (speed != null) m.speed.set(speed);
        if (!m.isActive()) m.toggle();
        else {
            m.startAge = mc.player.age;
            m.followed = player;
            Chat.info("[bang] Target changed to %s.", player.getName().getString());
        }
        return SINGLE_SUCCESS;
    }

    // Called by Bang module each tick
    public PlayerInput applyInput(Input source, PlayerInput keyboard) {
        if (module == null || module.target.get().isBlank()) return keyboard;
        if (source != mc.player.input) return keyboard;
        if (module.mode.get() == Bang.Mode.Legit) return module.legitInput().orElse(keyboard);
        return PlayerInput.DEFAULT; // Normal: clear keyboard, no walking/crouching
    }

    /** Replace self-propelled movement before vanilla resolves collisions.
     * Only applies to Normal mode. Legit mode uses legitInput() for movement. */
    public Vec3d lockedMovement(Vec3d original) {
        if (module == null || !module.isActive() || module.isWalkingPhase()) return original;
        if (module.mode.get() == Bang.Mode.Legit) return original; // Legit mode handles its own movement
        if (module.followed == null) return original;

        double contact = (mc.player.getWidth() + module.followed.getWidth()) * 0.5;
        double behind = contact + module.distance.get() * (1 - module.extension());
        float yaw = module.followed.getBodyYaw();
        int sign = module.position.get() == Bang.Position.Back ? -1 : 1;
        Vec3d facing = Vec3d.fromPolar(0, yaw);
        Vec3d anchor = module.followed.getEntityPos().add(
            sign * behind * facing.x,
            module.height.get(),
            sign * behind * facing.z
        );
        mc.player.setVelocity(Vec3d.ZERO);
        return anchor.subtract(mc.player.getEntityPos());
    }

    private double extension(int age) {
        if (module == null) return 0;
        double cycles = Math.max(0, age - module.startAge) * module.speed.get() / 20;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * cycles);
    }

    /** Match vanilla's interpolation of the two positions; a negative value means no pose.
     * Only applies to Normal mode (leg swing pose). */
    public float renderExtension(PlayerEntityRenderState state) {
        if (module == null || !module.isActive() || module.isWalkingPhase() || module.followed == null) return -1;
        if (module.mode.get() == Bang.Mode.Legit) return -1; // Legit mode uses vanilla limb animation
        if (state.id != mc.player.getId()) return -1;
        int age = MathHelper.floor(state.age);
        return (float) MathHelper.lerp(state.age - age, extension(age - 1), extension(age));
    }

    public void setModule(Bang m) { this.module = m; }
    public void setTarget(PlayerEntity p) { this.target = p; }
    public void setSpeed(double s) { if (module != null) module.speed.set(s); }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        Bang m = Modules.get().get(Bang.class);
        if (m != null && m.isActive()) m.toggle();
    }

    public void clear() {
        target = null;
        module = null;
    }
}