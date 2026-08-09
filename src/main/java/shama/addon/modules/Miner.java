package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Miner++ — merged mining tools with per-method tickboxes (VeinMiner / PacketMine /
 * Excavator). Enable one; while you hold attack on a block it applies that method.
 * Full effect in singleplayer / on lenient servers; strict anti-cheats cap break rate.
 */
public class Miner extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> vein = sg.add(new BoolSetting.Builder().name("vein-miner").description("Break all connected same-type blocks around the target.").defaultValue(true).build());
    private final Setting<Boolean> packet = sg.add(new BoolSetting.Builder().name("packet-mine").description("Fire raw start+stop destroy packets (instant on lenient servers).").defaultValue(false).build());
    private final Setting<Boolean> excavate = sg.add(new BoolSetting.Builder().name("excavator").description("Break every block in a radius around the target (nuker-style area).").defaultValue(false).build());
    private final Setting<Boolean> infinity = sg.add(new BoolSetting.Builder().name("infinity-miner").description("Auto-hold mine on whatever block you look at (no need to hold click).").defaultValue(false).build());
    private final Setting<Integer> radius = sg.add(new IntSetting.Builder().name("radius").description("Vein/excavator search radius.").defaultValue(3).range(1, 6).sliderRange(1, 6).build());
    private final Setting<Integer> perTick = sg.add(new IntSetting.Builder().name("blocks-per-tick").description("Max blocks to send per tick (kick safety).").defaultValue(16).range(1, 128).sliderRange(4, 64).build());

    public Miner() {
        super(shama.addon.ShamaAddon.PLAYER, "miner++", "Mines for you — vein mining, fast packet mining, or a wide excavator, all in one module.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        boolean mining = mc.options.attackKey.isPressed();
        if (infinity.get() && mc.crosshairTarget instanceof BlockHitResult ih && ih.getType() == HitResult.Type.BLOCK) {
            mc.interactionManager.updateBlockBreakingProgress(ih.getBlockPos(), ih.getSide());
            mc.player.swingHand(Hand.MAIN_HAND);
            mining = true;
        }
        if (!mining) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos origin = hit.getBlockPos();
        Direction side = hit.getSide();
        int budget = perTick.get();

        if (excavate.get()) {
            int r = radius.get();
            for (int dx = -r; dx <= r && budget > 0; dx++)
                for (int dy = -r; dy <= r && budget > 0; dy++)
                    for (int dz = -r; dz <= r && budget > 0; dz++) {
                        BlockPos p = origin.add(dx, dy, dz);
                        if (mc.world.getBlockState(p).isAir()) continue;
                        breakBlock(p, side); budget--;
                    }
            return;
        }

        if (vein.get()) {
            Block target = mc.world.getBlockState(origin).getBlock();
            if (target == null) return;
            int r = radius.get();
            Set<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            q.add(origin); seen.add(origin);
            while (!q.isEmpty() && budget > 0) {
                BlockPos p = q.poll();
                if (mc.world.getBlockState(p).getBlock() != target) continue;
                breakBlock(p, side); budget--;
                for (Direction d : Direction.values()) {
                    BlockPos n = p.offset(d);
                    if (seen.add(n) && n.isWithinDistance(origin, r + 0.5)) q.add(n);
                }
            }
            return;
        }

        if (packet.get()) breakBlock(origin, side);
    }

    private void breakBlock(BlockPos pos, Direction side) {
        var nh = mc.getNetworkHandler();
        nh.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side, 0));
        nh.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side, 0));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    @Override
    public String getInfoString() {
        return excavate.get() ? "excavate" : vein.get() ? "vein" : packet.get() ? "packet" : "off";
    }
}
