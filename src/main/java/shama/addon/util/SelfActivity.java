package shama.addon.util;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What YOU have been doing, so the detectors can tell your own traces from somebody else's.
 *
 * Most of the detectors look for signs of another player: chunks the server already had in memory,
 * the server moving your character, sounds with nobody near, containers being opened. Every one of
 * those is also exactly what your own play produces. Throw a pearl and the server teleports you,
 * plays the landing sound somewhere you are not standing yet, and streams the chunks around the
 * landing spot back at full speed because you only just left them. Open your own chest and the
 * server sends its contents. Without knowing what you did, each of those reads as somebody else.
 *
 * This records it once, for every module to ask:
 *   - teleports: a jump in your position, the server setting your position, a respawn or a new world
 *   - pearls and chorus fruit you used, so the teleport they cause is put down to you
 *   - the chunks you left and when, because the server keeps ground you just walked away from in
 *     memory, and it comes back instantly for that reason alone
 *   - the blocks you placed and the blocks you broke, so your own fills, tunnels and containers
 *     are yours, and the last block you clicked, so the container you open is known
 */
public final class SelfActivity {
    public static final SelfActivity INSTANCE = new SelfActivity();
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** A move bigger than this in one tick is a teleport, not walking or flying. */
    private static final double TELEPORT_BLOCKS = 8.0;
    private static final int MAX_TRACKED = 20000;

    private volatile long lastTeleport, lastServerMove, lastWorldChange, lastPearlThrow, lastChorusUse;
    private volatile long lastClickPos = Long.MIN_VALUE, lastClickAt;
    private double lx, ly, lz, lastSpeed;
    private boolean havePos;
    private Object lastWorld;

    private final Map<Long, Long> leftAt = new ConcurrentHashMap<>();
    private final Set<Long> placed = ConcurrentHashMap.newKeySet();
    private final Set<Long> broken = ConcurrentHashMap.newKeySet();

    private SelfActivity() {}

    // ---------------------------------------------------------------- recording

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        long now = System.currentTimeMillis();
        if (mc.player == null || mc.world == null) { havePos = false; return; }
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            lastWorldChange = now;
            havePos = false;
            leftAt.clear();
            placed.clear();
            broken.clear();
        }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        if (havePos) {
            double dx = x - lx, dy = y - ly, dz = z - lz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > TELEPORT_BLOCKS * TELEPORT_BLOCKS) lastTeleport = now;
            else lastSpeed = Math.sqrt(dx * dx + dz * dz);
        }
        lx = x; ly = y; lz = z; havePos = true;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        long now = System.currentTimeMillis();
        if (event.packet instanceof PlayerPositionLookS2CPacket) lastServerMove = now;
        else if (event.packet instanceof PlayerRespawnS2CPacket || event.packet instanceof GameJoinS2CPacket) lastWorldChange = now;
        else if (event.packet instanceof UnloadChunkS2CPacket p) {
            if (leftAt.size() > MAX_TRACKED) leftAt.clear();
            leftAt.put(p.comp_1726().toLong(), now);
        }
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (event.packet instanceof PlayerActionC2SPacket p) {
            // starting counts as well as finishing: an instant break never sends the finish
            var a = p.getAction();
            if (a == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK || a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
                if (broken.size() > MAX_TRACKED) broken.clear();
                broken.add(p.getPos().asLong());
            }
        } else if (event.packet instanceof PlayerInteractItemC2SPacket p) {
            ItemStack s = mc.player.getStackInHand(p.getHand());
            if (s.isOf(Items.ENDER_PEARL)) lastPearlThrow = now;
            else if (s.isOf(Items.CHORUS_FRUIT)) lastChorusUse = now;
        } else if (event.packet instanceof PlayerInteractBlockC2SPacket p) {
            BlockPos hit = p.getBlockHitResult().getBlockPos();
            lastClickPos = hit.asLong();
            lastClickAt = now;
            ItemStack s = mc.player.getStackInHand(p.getHand());
            if (s.getItem() instanceof BlockItem) {
                if (placed.size() > MAX_TRACKED) placed.clear();
                // either into the clicked spot (grass, snow, water) or against the face that was clicked
                placed.add(hit.asLong());
                placed.add(hit.offset(p.getBlockHitResult().getSide()).asLong());
            }
            if (s.isOf(Items.ENDER_PEARL)) lastPearlThrow = now;
        }
    }

    // ---------------------------------------------------------------- questions

    private static long since(long t) { return t == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - t; }

    /** You were moved a long way in one go — teleport, pearl, respawn, new world — within this time. */
    public boolean teleportedWithin(long ms) {
        return since(lastTeleport) < ms || since(lastServerMove) < ms || since(lastWorldChange) < ms;
    }

    /** A teleport right now would be yours: a pearl or chorus fruit you used, or a respawn / world change. */
    public boolean ownTeleportExpected() {
        return since(lastPearlThrow) < 30_000 || since(lastChorusUse) < 6_000 || since(lastWorldChange) < 10_000;
    }

    /** A pearl you threw is still in the air, or landed a moment ago. */
    public boolean pearlInFlight() {
        if (since(lastPearlThrow) < 20_000 && lastTeleport < lastPearlThrow && lastServerMove < lastPearlThrow) return true;
        return since(lastTeleport) < 2_500 || since(lastServerMove) < 2_500;
    }

    /** How fast you were moving across the ground last tick, in blocks per tick. */
    public double groundSpeed() { return lastSpeed; }

    /**
     * This chunk left your client within the time given. The server keeps ground you just walked
     * away from in memory for a while, so it comes back instantly on its own account — not because
     * somebody else is holding it.
     */
    public boolean leftRecently(long chunkKey, long ms) {
        Long t = leftAt.get(chunkKey);
        return t != null && System.currentTimeMillis() - t < ms;
    }

    /** When this chunk last left your client, or -1 if it has not since you joined. */
    public long leftAt(long chunkKey) {
        Long t = leftAt.get(chunkKey);
        return t == null ? -1 : t;
    }

    /** You placed a block at this spot this session. */
    public boolean placedByMe(BlockPos pos) { return placed.contains(pos.asLong()); }
    public boolean placedByMe(int x, int y, int z) { return placed.contains(BlockPos.asLong(x, y, z)); }

    /** You broke (or started breaking) the block at this spot this session. */
    public boolean brokenByMe(int x, int y, int z) { return broken.contains(BlockPos.asLong(x, y, z)); }

    /** You are within this many blocks of the point. */
    public boolean near(double x, double y, double z, double radius) {
        if (mc.player == null) return false;
        double dx = mc.player.getX() - x, dy = mc.player.getY() - y, dz = mc.player.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** The block you clicked most recently, if it was within this time; otherwise null. */
    public BlockPos lastClicked(long ms) {
        if (lastClickPos == Long.MIN_VALUE || since(lastClickAt) >= ms) return null;
        return BlockPos.fromLong(lastClickPos);
    }
}
