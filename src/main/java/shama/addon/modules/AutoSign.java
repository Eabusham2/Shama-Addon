package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;

/**
 * AutoSign — ported from their AutoSign: learns the text from the first sign you write
 * (captured off your outgoing sign packet), then auto-fills every sign edit screen after
 * that with the same four lines and closes it. Write one sign, the rest copy it.
 */
public class AutoSign extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> front = sg.add(new BoolSetting.Builder().name("front").description("Write on the front side of the sign.").defaultValue(true).build());
    private String[] text;

    public AutoSign() { super(shama.addon.ShamaAddon.PLAYER, "auto-sign++", "Copies your first sign's text onto every sign after."); }

    @Override public void onDeactivate() { text = null; }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSignC2SPacket u) text = u.getText(); // learn from your manual sign
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null || text == null || mc.currentScreen == null) return;
        if (!mc.currentScreen.getClass().getName().toLowerCase().contains("sign")) return;
        BlockPos pos = findSignPos(mc.currentScreen);
        if (pos == null) return;
        String l0 = text.length > 0 ? text[0] : "", l1 = text.length > 1 ? text[1] : "",
               l2 = text.length > 2 ? text[2] : "", l3 = text.length > 3 ? text[3] : "";
        mc.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(pos, front.get(), l0, l1, l2, l3));
        mc.setScreen(null);
    }

    private BlockPos findSignPos(Object screen) {
        try {
            for (Field f : screen.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(screen);
                if (v instanceof BlockEntity be) return be.getPos();
                if (v instanceof BlockPos bp) return bp;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override public String getInfoString() { return text != null ? "ready" : "learning"; }
}
