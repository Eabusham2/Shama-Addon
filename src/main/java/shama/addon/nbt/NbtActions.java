package shama.addon.nbt;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;

/**
 * Shared actions for turning a Java-format NbtCompound into something in the world:
 * give an item (creative) or place a structure (singleplayer). Used by both the
 * .nbt command (Java SNBT) and the .bnbt command (converted Bedrock).
 */
public final class NbtActions {

    /** Route a Java NBT compound to the right action based on its shape. */
    public static void handle(NbtCompound nbt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (nbt.contains("size") && nbt.contains("blocks")) {
            placeStructure(mc, nbt);
        } else if (nbt.contains("id")) {
            giveItem(mc, nbt);
        } else {
            ChatUtils.warning("Unrecognized NBT — need 'id' for an item or 'size'+'blocks' for a structure.");
        }
    }

    public static void giveItem(MinecraftClient mc, NbtCompound nbt) {
        if (mc.player == null || mc.interactionManager == null || mc.world == null) return;
        if (!mc.player.getAbilities().creativeMode) {
            ChatUtils.error("Items can only be spawned in creative.");
            return;
        }
        ItemStack stack = Snbt.itemFromNbt(nbt);
        if (stack.isEmpty()) {
            ChatUtils.error("That NBT didn't parse into a valid item.");
            return;
        }
        int slot = 36 + mc.player.getInventory().getSelectedSlot();
        mc.interactionManager.clickCreativeStack(stack, slot);
        ChatUtils.info("Gave " + stack.getName().getString() + ".");
    }

    /** Reflective StructureTemplate use so the version-sensitive API can't block the
     *  build or crash — degrades to a message. Singleplayer only. */
    public static void placeStructure(MinecraftClient mc, NbtCompound nbt) {
        if (mc.getServer() == null || mc.player == null) {
            ChatUtils.error("Structures can only be placed in singleplayer (the integrated server sets the blocks).");
            return;
        }
        try {
            ServerWorld world = mc.getServer().getWorld(mc.player.getEntityWorld().getRegistryKey());
            if (world == null) world = mc.getServer().getOverworld();

            // 1.21.11 (verified): StructureTemplate#readNbt(RegistryEntryLookup<Block>, NbtCompound)
            // and #place(ServerWorldAccess, BlockPos pos, BlockPos pivot, StructurePlacementData,
            // Random, int flags). Direct calls so Loom remaps them into the built jar — name-based
            // reflection compiles but breaks after remapping.
            RegistryWrapper.Impl<Block> blockLookup = world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK);

            StructureTemplate template = new StructureTemplate();
            template.readNbt(blockLookup, nbt);

            StructurePlacementData data = new StructurePlacementData();
            BlockPos pos = mc.player.getBlockPos();
            template.place(world, pos, pos, data, world.getRandom(), 3);
            ChatUtils.info("Placed structure at " + pos.toShortString() + ".");
        } catch (Exception e) {
            ChatUtils.error("Structure place failed: " + e.getMessage());
        }
    }
}
