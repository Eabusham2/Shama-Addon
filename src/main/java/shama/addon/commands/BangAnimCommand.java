package shama.addon.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.MathHelper;
import shama.addon.util.Chat;

/**
 * .banganim [speed] — toggle. Standalone, NO module, NO bang++ required, NO target lock.
 * Same Normal-mode animation code (renderExtension + BangPoseMixin), just plays the pose
 * on you without lockedMovement; you can move and turn. .unbanganim off.
 */
public final class BangAnimCommand extends Command {
    public static final BangAnimCommand INSTANCE = new BangAnimCommand();

    private boolean active;
    private double speed = 1.0;
    private int startAge;

    private BangAnimCommand() {
        super("banganim", "Toggle the bang swing animation on yourself without locking to a target. .banganim [speed]; .unbanganim off.", "unbanganim");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // .unbanganim off
        if (!builder.getLiteral().equals(getName())) {
            builder.executes(ctx -> { setActive(false); Chat.info("[banganim] Stopped."); return SINGLE_SUCCESS; });
            return;
        }

        builder.executes(ctx -> { toggle(null); return SINGLE_SUCCESS; });
        builder.then(argument("speed", DoubleArgumentType.doubleArg(0.1, 10))
            .executes(ctx -> { toggle(DoubleArgumentType.getDouble(ctx, "speed")); return SINGLE_SUCCESS; }));
    }

    private void toggle(Double speed) {
        if (active) { setActive(false); Chat.info("[banganim] Stopped."); return; }
        if (speed != null) this.speed = speed;
        setActive(true);
        Chat.info("[banganim] Playing at %s cycles per second.", this.speed);
    }

    public void setActive(boolean on) {
        active = on;
        if (on) startAge = mc.player != null ? mc.player.age : 0;
    }

    public boolean isAnimActive() { return active; }

    /** Same renderExtension as normal mode; -1 if off or not local player. */
    public float animExtension(PlayerEntityRenderState state) {
        if (!active || mc.player == null || state.id != mc.player.getId()) return -1;
        int age = MathHelper.floor(state.age);
        return (float) MathHelper.lerp(state.age - age, extension(age - 1), extension(age));
    }

    private double extension(int age) {
        double cycles = Math.max(0, age - startAge) * speed / 20;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * cycles);
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) { active = false; }
}