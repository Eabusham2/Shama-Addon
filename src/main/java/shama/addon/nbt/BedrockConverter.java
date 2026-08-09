package shama.addon.nbt;

import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts Bedrock NBT (already parsed by {@link LeNbt}) into Java-format NBT that
 * {@link NbtActions} can place/give.
 *
 * What it does well: geometry + block types of a .mcstructure, and id/count/damage/
 * enchantments of an item.
 * What's lossy (honest limits): block orientation/rotation and other complex block
 * states are only partially translated (unknown states are dropped, so the block
 * falls back to its default facing); waterlogging (Bedrock layer 1) is ignored;
 * block entities (chests' contents, sign text, etc.) and entities are not carried
 * over; items keep only id/count/damage/enchantments.
 *
 * Navigation uses raw get()+casts and stable number/string extraction so it doesn't
 * depend on the 1.20.5/1.21 typed-getter churn.
 */
public final class BedrockConverter {

    // ---- small, version-stable NBT accessors -------------------------------

    private static NbtElement get(NbtElement e, String key) {
        return (e instanceof NbtCompound c) ? c.get(key) : null;
    }

    private static int asInt(NbtElement e) {
        if (e instanceof AbstractNbtNumber n) return n.intValue();
        try { return Integer.parseInt(asStr(e)); } catch (Exception ignored) { return 0; }
    }

    private static String asStr(NbtElement e) {
        if (e == null) return "";
        if (e instanceof NbtString) {
            // NbtString#toString() is the SNBT form: a quoted, escaped string. Strip the
            // outer quotes and unescape. Remap-safe — no reflection, no version-specific
            // accessor whose mapped name changes across builds.
            String raw = e.toString();
            int n = raw.length();
            if (n >= 2) {
                char q = raw.charAt(0);
                if ((q == '"' || q == '\'') && raw.charAt(n - 1) == q) {
                    StringBuilder sb = new StringBuilder(n - 2);
                    for (int i = 1; i < n - 1; i++) {
                        char c = raw.charAt(i);
                        if (c == '\\' && i + 1 < n - 1) c = raw.charAt(++i);
                        sb.append(c);
                    }
                    return sb.toString();
                }
            }
            return raw;
        }
        return e.toString();
    }

    // =======================================================================
    //  STRUCTURE  (.mcstructure  ->  Java structure template NBT)
    // =======================================================================

    public static NbtCompound structure(NbtCompound bedrock) {
        NbtElement sizeEl = bedrock.get("size");
        if (!(sizeEl instanceof NbtList size) || size.size() < 3)
            throw new RuntimeException("no 'size' list — not a .mcstructure?");
        int sx = asInt(size.get(0)), sy = asInt(size.get(1)), sz = asInt(size.get(2));

        NbtElement structEl = bedrock.get("structure");
        if (!(structEl instanceof NbtCompound structure))
            throw new RuntimeException("no 'structure' compound — not a .mcstructure?");

        // block_indices: a list of layers; layer 0 = main blocks, layer 1 = waterlog (ignored).
        NbtElement biEl = structure.get("block_indices");
        if (!(biEl instanceof NbtList layers) || layers.isEmpty())
            throw new RuntimeException("no 'block_indices'");
        NbtElement layer0El = layers.get(0);
        if (!(layer0El instanceof NbtList layer0))
            throw new RuntimeException("bad 'block_indices' layer 0");

        // palette.default.block_palette
        NbtElement paletteRoot = structure.get("palette");
        NbtElement def = get(paletteRoot, "default");
        NbtElement bpEl = get(def, "block_palette");
        if (!(bpEl instanceof NbtList bedrockPalette))
            throw new RuntimeException("no 'block_palette'");

        // Convert palette.
        NbtList javaPalette = new NbtList();
        for (int i = 0; i < bedrockPalette.size(); i++) {
            NbtElement be = bedrockPalette.get(i);
            javaPalette.add(convertBlock(be instanceof NbtCompound c ? c : new NbtCompound()));
        }

        // Convert blocks. Bedrock index order: x outer, y middle, z inner.
        NbtList blocks = new NbtList();
        int total = sx * sy * sz;
        for (int i = 0; i < total && i < layer0.size(); i++) {
            int state = asInt(layer0.get(i));
            if (state < 0) continue; // -1 = no block
            int x = i / (sy * sz);
            int rem = i % (sy * sz);
            int y = rem / sz;
            int z = rem % sz;

            NbtCompound block = new NbtCompound();
            NbtList pos = new NbtList();
            pos.add(NbtInt.of(x));
            pos.add(NbtInt.of(y));
            pos.add(NbtInt.of(z));
            block.put("pos", pos);
            block.putInt("state", state);
            blocks.add(block);
        }

        NbtCompound out = new NbtCompound();
        NbtList javaSize = new NbtList();
        javaSize.add(NbtInt.of(sx));
        javaSize.add(NbtInt.of(sy));
        javaSize.add(NbtInt.of(sz));
        out.put("size", javaSize);
        out.put("palette", javaPalette);
        out.put("blocks", blocks);
        out.put("entities", new NbtList());
        return out;
    }

    /** Bedrock palette entry {name, states, version} -> Java {Name, Properties}. */
    private static NbtCompound convertBlock(NbtCompound bb) {
        NbtCompound out = new NbtCompound();
        String name = asStr(bb.get("name"));
        if (name.isEmpty()) name = "minecraft:air";
        out.put("Name", NbtString.of(mapBlockName(name)));

        NbtElement statesEl = bb.get("states");
        if (statesEl instanceof NbtCompound states) {
            NbtCompound props = new NbtCompound();
            for (String key : states.getKeys()) {
                translateState(props, key, states.get(key));
            }
            if (!props.isEmpty()) out.put("Properties", props);
        }
        return out;
    }

    private static final Map<String, String> BLOCK_OVERRIDES = new HashMap<>();
    static {
        // Most modern (1.18+) Bedrock block names already match Java, so default is
        // passthrough; these are the common ones that don't.
        BLOCK_OVERRIDES.put("minecraft:grass", "minecraft:grass_block");
        BLOCK_OVERRIDES.put("minecraft:flowing_water", "minecraft:water");
        BLOCK_OVERRIDES.put("minecraft:flowing_lava", "minecraft:lava");
        BLOCK_OVERRIDES.put("minecraft:powered_repeater", "minecraft:repeater");
        BLOCK_OVERRIDES.put("minecraft:unpowered_repeater", "minecraft:repeater");
        BLOCK_OVERRIDES.put("minecraft:powered_comparator", "minecraft:comparator");
        BLOCK_OVERRIDES.put("minecraft:unpowered_comparator", "minecraft:comparator");
        BLOCK_OVERRIDES.put("minecraft:lit_redstone_lamp", "minecraft:redstone_lamp");
        BLOCK_OVERRIDES.put("minecraft:lit_furnace", "minecraft:furnace");
        BLOCK_OVERRIDES.put("minecraft:wall_sign", "minecraft:oak_wall_sign");
        BLOCK_OVERRIDES.put("minecraft:standing_sign", "minecraft:oak_sign");
    }

    private static String mapBlockName(String name) {
        if (!name.contains(":")) name = "minecraft:" + name;
        return BLOCK_OVERRIDES.getOrDefault(name, name);
    }

    /** Translate a Bedrock block state into a Java palette Property (value as String).
     *  Unknown states are dropped, so the block keeps its default for that axis. */
    private static void translateState(NbtCompound props, String key, NbtElement val) {
        switch (key) {
            case "pillar_axis" -> props.put("axis", NbtString.of(asStr(val)));
            case "upside_down_bit" -> props.put("half", NbtString.of(asInt(val) != 0 ? "top" : "bottom"));
            case "upper_block_bit" -> props.put("half", NbtString.of(asInt(val) != 0 ? "upper" : "lower"));
            case "open_bit" -> props.put("open", NbtString.of(bool(val)));
            case "door_hinge_bit" -> props.put("hinge", NbtString.of(asInt(val) != 0 ? "right" : "left"));
            case "facing_direction", "torch_facing_direction" -> props.put("facing", NbtString.of(face(asInt(val))));
            case "weirdo_direction" -> props.put("facing", NbtString.of(stairFace(asInt(val))));
            case "top_slot_bit" -> props.put("type", NbtString.of(asInt(val) != 0 ? "top" : "bottom"));
            case "minecraft:vertical_half" -> props.put("half", NbtString.of(asStr(val)));
            case "minecraft:cardinal_direction" -> props.put("facing", NbtString.of(asStr(val)));
            case "powered_bit" -> props.put("powered", NbtString.of(bool(val)));
            case "button_pressed_bit" -> props.put("powered", NbtString.of(bool(val)));
            case "age", "growth", "moisturized_amount" -> props.put("age", NbtString.of(String.valueOf(asInt(val))));
            default -> {
                // Pass through string-valued states whose key already looks like a
                // Java property (e.g. "axis"); skip the rest.
                if (val instanceof NbtString && !key.contains("_bit"))
                    props.put(key, NbtString.of(asStr(val)));
            }
        }
    }

    private static String bool(NbtElement v) { return asInt(v) != 0 ? "true" : "false"; }

    // Bedrock facing_direction: 0 down,1 up,2 north,3 south,4 west,5 east.
    private static String face(int d) {
        return switch (d) {
            case 0 -> "down";
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            default -> "east";
        };
    }

    // Bedrock stair weirdo_direction: 0 east,1 west,2 south,3 north.
    private static String stairFace(int d) {
        return switch (d) {
            case 0 -> "east";
            case 1 -> "west";
            case 2 -> "south";
            default -> "north";
        };
    }

    // =======================================================================
    //  ITEM  (Bedrock item  ->  Java component item)
    // =======================================================================

    public static NbtCompound item(NbtCompound bedrock) {
        // Some exporters wrap the item as {"Item":{...}}.
        if (bedrock.get("Name") == null && bedrock.get("Item") instanceof NbtCompound inner) bedrock = inner;

        String id = asStr(bedrock.get("Name"));
        if (id.isEmpty()) throw new RuntimeException("item has no 'Name'");
        id = mapItemName(id);

        int count = bedrock.get("Count") != null ? asInt(bedrock.get("Count")) : 1;
        if (count <= 0) count = 1;

        NbtCompound out = new NbtCompound();
        out.put("id", NbtString.of(id));
        out.putInt("count", count);

        NbtCompound components = new NbtCompound();

        int damage = bedrock.get("Damage") != null ? asInt(bedrock.get("Damage")) : 0;
        if (damage > 0) components.putInt("minecraft:damage", damage);

        NbtElement tag = bedrock.get("tag");
        NbtCompound t = tag instanceof NbtCompound tc ? tc : null;

        // Container "kits" (shulker / chest with items): Bedrock stores the contents
        // as an Items list (under tag.Items, or sometimes top-level). Java 1.20.5+
        // uses the minecraft:container component: [{slot, item}, ...]. Recurse so
        // enchanted items and nested shulkers inside the kit convert too.
        NbtElement itemsEl = t != null ? t.get("Items") : null;
        if (itemsEl == null) itemsEl = bedrock.get("Items");
        if (itemsEl instanceof NbtList itemList && !itemList.isEmpty()) {
            NbtList container = new NbtList();
            for (int i = 0; i < itemList.size(); i++) {
                if (!(itemList.get(i) instanceof NbtCompound ic) || ic.get("Name") == null) continue;
                int slot = ic.get("Slot") != null ? asInt(ic.get("Slot")) : i;
                NbtCompound entry = new NbtCompound();
                entry.putInt("slot", slot);
                entry.put("item", item(ic));
                container.add(entry);
            }
            if (!container.isEmpty()) components.put("minecraft:container", container);
        }

        if (t != null) {
            // Enchantments: tag.ench = [{id, lvl}, ...]
            NbtElement ench = t.get("ench");
            if (ench instanceof NbtList el && !el.isEmpty()) {
                NbtCompound levels = new NbtCompound();
                for (int i = 0; i < el.size(); i++) {
                    NbtElement e = el.get(i);
                    int eid = asInt(get(e, "id"));
                    int lvl = asInt(get(e, "lvl"));
                    String ench_name = ENCHANTS.get(eid);
                    if (ench_name != null && lvl > 0) levels.putInt("minecraft:" + ench_name, lvl);
                }
                if (!levels.isEmpty()) {
                    NbtCompound enchComp = new NbtCompound();
                    enchComp.put("levels", levels);
                    components.put("minecraft:enchantments", enchComp);
                }
            }
            // Custom name: tag.display.Name (plain string on Bedrock).
            NbtElement display = t.get("display");
            String customName = asStr(get(display, "Name"));
            if (!customName.isEmpty())
                components.put("minecraft:custom_name", NbtString.of("\"" + customName.replace("\"", "\\\"") + "\""));
        }

        if (!components.isEmpty()) out.put("components", components);
        return out;
    }

    private static final Map<String, String> ITEM_OVERRIDES = new HashMap<>();
    static {
        // Bedrock item ids that differ from Java (the common kit-relevant ones).
        ITEM_OVERRIDES.put("minecraft:undyed_shulker_box", "minecraft:shulker_box");
    }

    private static String mapItemName(String id) {
        if (!id.contains(":")) id = "minecraft:" + id;
        return ITEM_OVERRIDES.getOrDefault(id, id);
    }

    // Bedrock enchantment id -> Java enchantment name (covers the common set).
    private static final Map<Integer, String> ENCHANTS = new HashMap<>();
    static {
        ENCHANTS.put(0, "protection");
        ENCHANTS.put(1, "fire_protection");
        ENCHANTS.put(2, "feather_falling");
        ENCHANTS.put(3, "blast_protection");
        ENCHANTS.put(4, "projectile_protection");
        ENCHANTS.put(5, "thorns");
        ENCHANTS.put(6, "respiration");
        ENCHANTS.put(7, "depth_strider");
        ENCHANTS.put(8, "aqua_affinity");
        ENCHANTS.put(9, "sharpness");
        ENCHANTS.put(10, "smite");
        ENCHANTS.put(11, "bane_of_arthropods");
        ENCHANTS.put(12, "knockback");
        ENCHANTS.put(13, "fire_aspect");
        ENCHANTS.put(14, "looting");
        ENCHANTS.put(15, "efficiency");
        ENCHANTS.put(16, "silk_touch");
        ENCHANTS.put(17, "unbreaking");
        ENCHANTS.put(18, "fortune");
        ENCHANTS.put(19, "power");
        ENCHANTS.put(20, "punch");
        ENCHANTS.put(21, "flame");
        ENCHANTS.put(22, "infinity");
        ENCHANTS.put(23, "luck_of_the_sea");
        ENCHANTS.put(24, "lure");
        ENCHANTS.put(25, "frost_walker");
        ENCHANTS.put(26, "mending");
        ENCHANTS.put(27, "binding_curse");
        ENCHANTS.put(28, "vanishing_curse");
        ENCHANTS.put(29, "impaling");
        ENCHANTS.put(30, "riptide");
        ENCHANTS.put(31, "loyalty");
        ENCHANTS.put(32, "channeling");
        ENCHANTS.put(33, "multishot");
        ENCHANTS.put(34, "piercing");
        ENCHANTS.put(35, "quick_charge");
        ENCHANTS.put(36, "soul_speed");
        ENCHANTS.put(37, "swift_sneak");
    }
}
