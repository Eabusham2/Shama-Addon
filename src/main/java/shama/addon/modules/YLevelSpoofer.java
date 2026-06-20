package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class YLevelSpoofer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> y = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-level")
        .description("Y level to spoof to.")
        .defaultValue(-10)
        .build()
    );

    private final Setting<Integer> packets = sgGeneral.add(new IntSetting.Builder()
        .name("packets")
        .description("How many spoofed position packets to send before disabling. More = longer flicker.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private int sent;

    public YLevelSpoofer() {
        super(Categories.Player, "y-level-spoof-(risky)", "Sends spoofed Y-level position packets without moving you, then disables itself. Most anti-cheats will flag this.");
    }

    @Override
    public void onActivate() {
        sent = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        // Actively send a Full position packet with the real X/Z but spoofed Y.
        // This fires regardless of whether the player is moving, which is why the
        // old "wait for an outgoing packet" approach didn't flicker while standing still.
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            mc.player.getX(),
            y.get(),
            mc.player.getZ(),
            mc.player.getYaw(),
            mc.player.getPitch(),
            mc.player.isOnGround(),
            mc.player.horizontalCollision
        ));

        sent++;
        if (sent >= packets.get()) toggle();
    }

    @Override
    public String getInfoString() {
        return sent + "/" + packets.get();
    }
}
