package shama.addon.nbt;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryOps;

/**
 * NBT helpers for 1.21.11. Two relevant API moves from earlier 1.21.x:
 *   - StringNbtReader.parse(String) was replaced by readCompound(String) at 1.21.7
 *   - ItemStack.fromNbt / ItemStack.encode were removed in favour of ItemStack.CODEC
 * Verified against Yarn 1.21.11. Direct calls (not reflection) so Loom remaps them
 * into the built jar — name-based reflection would compile but break after remap.
 */
public final class Snbt {

    /** Parse SNBT text into an NbtCompound (1.21.7+ readCompound). */
    public static NbtCompound parse(String s) throws Exception {
        return StringNbtReader.readCompound(s);
    }

    private static RegistryOps<NbtElement> ops() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return RegistryOps.of(NbtOps.INSTANCE, mc.world.getRegistryManager());
    }

    /** Decode an item from a Java item NbtCompound ({id,count,components}). */
    public static ItemStack itemFromNbt(NbtCompound nbt) {
        try {
            return ItemStack.CODEC.parse(ops(), nbt).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Encode an item to its full Java NBT form (id/count/components). */
    public static NbtElement itemToNbt(ItemStack stack) {
        try {
            return ItemStack.CODEC.encodeStart(ops(), stack).result().orElse(new NbtCompound());
        } catch (Exception e) {
            return new NbtCompound();
        }
    }
}
