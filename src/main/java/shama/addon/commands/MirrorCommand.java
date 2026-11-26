package shama.addon.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import shama.addon.modules.Mirror;
import shama.addon.util.Chat;

import java.util.ArrayList;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * .mirror and .shadow — start or stop mirror++.
 *
 *   .mirror <player>              stay on top of them and copy what they do
 *   .shadow <player> [seconds]    copy them that far behind, along their path (default: the delay setting)
 *   .mirror stop / .shadow stop   stop copying
 */
public class MirrorCommand extends Command {
    private final Mirror.Mode mode;

    public MirrorCommand(Mirror.Mode mode) {
        super(mode == Mirror.Mode.Mirror ? "mirror" : "shadow", mode == Mirror.Mode.Mirror
            ? "Copy a player's movement on top of them with mirror++: .mirror <player>. .mirror stop ends it."
            : "Copy a player's movement a set time behind them, along their path, with mirror++: .shadow <player> [seconds]. .shadow stop ends it.");
        this.mode = mode;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("stop").executes(ctx -> {
            Mirror m = Modules.get().get(Mirror.class);
            if (m != null && m.isActive()) m.toggle();
            Chat.info("[mirror] Stopped.");
            return SINGLE_SUCCESS;
        }));

        RequiredArgumentBuilder<CommandSource, String> player = argument("player", StringArgumentType.word())
            .suggests((ctx, b) -> CommandSource.suggestMatching(names(), b))
            .executes(ctx -> start(StringArgumentType.getString(ctx, "player"), null));
        if (mode == Mirror.Mode.Shadow) player.then(argument("seconds", DoubleArgumentType.doubleArg(0.1, 30))
            .executes(ctx -> start(StringArgumentType.getString(ctx, "player"), DoubleArgumentType.getDouble(ctx, "seconds"))));
        builder.then(player);
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        if (mc.world == null) return out;
        for (PlayerEntity p : mc.world.getPlayers()) if (p != mc.player) out.add(p.getName().getString());
        return out;
    }

    private int start(String name, Double seconds) {
        Mirror m = Modules.get().get(Mirror.class);
        if (m == null) return SINGLE_SUCCESS;
        if (mc.player != null && name.equalsIgnoreCase(mc.player.getName().getString())) {
            Chat.error("[mirror] You cannot copy yourself.");
            return SINGLE_SUCCESS;
        }
        m.copy(name, mode, seconds);
        if (mode == Mirror.Mode.Mirror) Chat.info("[mirror] Copying %s.", name);
        else Chat.info("[mirror] Shadowing %s, %.1f s behind.", name, m.delay.get());
        return SINGLE_SUCCESS;
    }
}
