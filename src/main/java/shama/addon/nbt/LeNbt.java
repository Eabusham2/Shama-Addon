package shama.addon.nbt;

import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtShort;
import net.minecraft.nbt.NbtString;

import java.nio.charset.StandardCharsets;

/**
 * Reads Bedrock-edition NBT (little-endian) into Java's in-memory NBT objects.
 * Bedrock files (.mcstructure, level.dat, etc.) store NBT little-endian; Java is
 * big-endian — so we can't use Java's readers. Tag IDs are the same 0..12.
 *
 * Handles the optional 8-byte header (version + length, both little-endian) that
 * some Bedrock files carry; .mcstructure has none.
 */
public final class LeNbt {
    private final byte[] b;
    private int p;

    private LeNbt(byte[] b, int p) {
        this.b = b;
        this.p = p;
    }

    public static NbtCompound parse(byte[] data) {
        int off = 0;
        // Skip an 8-byte header if present (first real tag byte is 0x0A = compound).
        if (data.length > 8 && (data[0] & 0xFF) != 10 && (data[8] & 0xFF) == 10) off = 8;
        LeNbt r = new LeNbt(data, off);
        int type = r.u8();
        if (type != 10) throw new RuntimeException("root tag is not a compound (got id " + type + ")");
        r.str(); // root name, usually empty
        return r.compound();
    }

    private int u8() { return b[p++] & 0xFF; }

    private short s16() {
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        p += 2;
        return (short) v;
    }

    private int i32() {
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
        p += 4;
        return v;
    }

    private long i64() {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long) (b[p + i] & 0xFF)) << (8 * i);
        p += 8;
        return v;
    }

    private float f32() { return Float.intBitsToFloat(i32()); }

    private double f64() { return Double.longBitsToDouble(i64()); }

    private String str() {
        int len = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        p += 2;
        String s = new String(b, p, len, StandardCharsets.UTF_8);
        p += len;
        return s;
    }

    private NbtCompound compound() {
        NbtCompound c = new NbtCompound();
        while (true) {
            int type = u8();
            if (type == 0) break; // END
            String name = str();
            c.put(name, payload(type));
        }
        return c;
    }

    private NbtList list() {
        int type = u8();
        int len = i32();
        NbtList l = new NbtList();
        for (int i = 0; i < len; i++) l.add(payload(type));
        return l;
    }

    private NbtElement payload(int type) {
        switch (type) {
            case 1: return NbtByte.of((byte) u8());
            case 2: return NbtShort.of(s16());
            case 3: return NbtInt.of(i32());
            case 4: return NbtLong.of(i64());
            case 5: return NbtFloat.of(f32());
            case 6: return NbtDouble.of(f64());
            case 7: {
                int n = i32();
                byte[] a = new byte[n];
                System.arraycopy(b, p, a, 0, n);
                p += n;
                return new NbtByteArray(a);
            }
            case 8: return NbtString.of(str());
            case 9: return list();
            case 10: return compound();
            case 11: {
                int n = i32();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = i32();
                return new NbtIntArray(a);
            }
            case 12: {
                int n = i32();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = i64();
                return new NbtLongArray(a);
            }
            default:
                throw new RuntimeException("unknown tag id " + type + " at offset " + p);
        }
    }
}
