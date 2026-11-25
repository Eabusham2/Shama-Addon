package shama.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import shama.addon.modules.Travel;
import shama.addon.util.Chat;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * .travel — send travel++ somewhere.
 *
 *   .travel <x> <z>                 go to that column
 *   .travel <x> <y> <z>             go to that exact block
 *   ... nether | overworld          say which dimension the coordinates are written in
 *   .travel stop                    end the trip
 *
 * Without nether or overworld, the coordinates are read the way travel++'s coords-in is set.
 */
public class TravelCommand extends Command {
    public TravelCommand() {
        super("travel", "Send travel++ to a position: .travel <x> <z> or .travel <x> <y> <z>, optionally followed by nether or overworld. .travel stop ends the trip.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("stop").executes(ctx -> {
            Travel t = Modules.get().get(Travel.class);
            if (t != null && t.isActive()) t.toggle();
            Chat.info("[travel] Trip stopped.");
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("x", IntegerArgumentType.integer())
            .then(argument("second", IntegerArgumentType.integer())
                .executes(ctx -> two(ctx, null))
                .then(literal("nether").executes(ctx -> two(ctx, Travel.Coords.Nether)))
                .then(literal("overworld").executes(ctx -> two(ctx, Travel.Coords.Overworld)))
                .then(argument("third", IntegerArgumentType.integer())
                    .executes(ctx -> three(ctx, null))
                    .then(literal("nether").executes(ctx -> three(ctx, Travel.Coords.Nether)))
                    .then(literal("overworld").executes(ctx -> three(ctx, Travel.Coords.Overworld))))));
    }

    private int two(CommandContext<CommandSource> ctx, Travel.Coords in) {
        go(IntegerArgumentType.getInteger(ctx, "x"), null, IntegerArgumentType.getInteger(ctx, "second"), in);
        return SINGLE_SUCCESS;
    }

    private int three(CommandContext<CommandSource> ctx, Travel.Coords in) {
        go(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "second"),
            IntegerArgumentType.getInteger(ctx, "third"), in);
        return SINGLE_SUCCESS;
    }

    private void go(int x, Integer y, int z, Travel.Coords in) {
        Travel t = Modules.get().get(Travel.class);
        if (t == null) return;
        t.travelTo(x, y, z, in);
        Chat.info("[travel] Heading to %d%s, %d (%s coordinates).", x, y == null ? "" : ", " + y, z,
            t.coordsIn.get() == Travel.Coords.Nether ? "Nether" : "Overworld");
    }
}
