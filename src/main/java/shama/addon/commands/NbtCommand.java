package shama.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.NbtCompound;
import shama.addon.nbt.NbtActions;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * .nbt [data] — paste Java NBT (or leave blank to use your clipboard, e.g. what you
 * middle-clicked with NBT Adder). Item NBT (has "id") is given to your hand
 * (creative); structure NBT ("size"+"blocks") is placed at your feet (singleplayer).
 */
public class NbtCommand extends Command {
    public NbtCommand() {
        super("nbt", "Give an item or place a structure from pasted Java NBT (or your clipboard).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            run(null);
            return SINGLE_SUCCESS;
        });
        builder.then(argument("data", StringArgumentType.greedyString()).executes(ctx -> {
            run(StringArgumentType.getString(ctx, "data"));
            return SINGLE_SUCCESS;
        }));
    }

    private void run(String snbt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (snbt == null || snbt.isBlank()) snbt = mc.keyboard.getClipboard();
        if (snbt == null || snbt.isBlank()) {
            ChatUtils.error("No NBT given and the clipboard is empty.");
            return;
        }
        NbtCompound nbt;
        try {
            nbt = shama.addon.nbt.Snbt.parse(snbt.trim());
        } catch (Exception e) {
            ChatUtils.error("Couldn't parse that NBT: " + e.getMessage());
            return;
        }
        NbtActions.handle(nbt);
    }
}
