package shama.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.NbtCompound;
import shama.addon.nbt.BedrockConverter;
import shama.addon.nbt.LeNbt;
import shama.addon.nbt.NbtActions;

import java.util.Base64;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * .bnbt [data] — convert a Bedrock structure/item/kit to Java, then place/give it.
 *
 * Accepts either:
 *   - pasted Bedrock NBT TEXT (SNBT/JSON), e.g. a Horion or Toolbox kit string that
 *     starts with '{', or
 *   - base64 of a raw binary Bedrock file (.mcstructure), which is little-endian.
 * Leave blank to use your clipboard.
 *
 * Shulker/chest kits: the Bedrock container's items (tag.Items) become Java's
 * minecraft:container component, so you get the shulker/chest already filled with
 * the kit (creative). Coverage is best-effort — see BedrockConverter for limits.
 */
public class BnbtCommand extends Command {
    public BnbtCommand() {
        super("bnbt", "Convert a Bedrock structure/item/kit (Horion/Toolbox text or base64) to Java and place/give it.");
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

    private void run(String input) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (input == null || input.isBlank()) input = mc.keyboard.getClipboard();
        if (input == null || input.isBlank()) {
            ChatUtils.error("Nothing to convert. Paste a Horion/Toolbox NBT string, or base64 of a .mcstructure.");
            return;
        }
        input = input.trim();

        NbtCompound bedrock;
        try {
            if (input.startsWith("{")) {
                // Horion / Toolbox export: textual Bedrock SNBT.
                bedrock = shama.addon.nbt.Snbt.parse(input);
            } else {
                // Binary .mcstructure encoded as base64.
                byte[] bytes = Base64.getDecoder().decode(input.replaceAll("\\s", ""));
                bedrock = LeNbt.parse(bytes);
            }
        } catch (Exception e) {
            ChatUtils.error("Couldn't read that as Bedrock NBT (text or base64): " + e.getMessage());
            return;
        }

        NbtCompound java;
        try {
            if (bedrock.contains("structure") || (bedrock.contains("size") && bedrock.contains("format_version"))) {
                java = BedrockConverter.structure(bedrock);
                ChatUtils.info("Converted Bedrock structure -> Java. Placing...");
            } else if (bedrock.contains("Name") || bedrock.contains("Item")) {
                java = BedrockConverter.item(bedrock);
                ChatUtils.info("Converted Bedrock item/kit -> Java. Giving...");
            } else {
                ChatUtils.warning("Not a recognizable Bedrock structure, item, or kit.");
                return;
            }
        } catch (Exception e) {
            ChatUtils.error("Conversion failed: " + e.getMessage());
            return;
        }

        NbtActions.handle(java);
    }
}
