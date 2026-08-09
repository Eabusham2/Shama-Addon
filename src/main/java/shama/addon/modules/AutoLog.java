package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

/** AutoLog — disconnects you when your health drops below a threshold (or a stranger comes close). */
public class AutoLog extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Double> healthLog = sg.add(new DoubleSetting.Builder().name("health").description("Disconnect at/below this health (0 = off).").defaultValue(6).min(0).max(20).sliderRange(0, 20).build());
    private final Setting<Boolean> onPlayer = sg.add(new BoolSetting.Builder().name("on-player").description("Also disconnect when a player comes within range.").defaultValue(false).build());
    private final Setting<Double> playerRange = sg.add(new DoubleSetting.Builder().name("player-range").description("How close a player must be to react (blocks).").defaultValue(32).min(4).sliderRange(8, 128).visible(onPlayer::get).build());

    public AutoLog() { super(shama.addon.ShamaAddon.COMBAT, "auto-log++", "Auto-disconnect at low health or when a player approaches."); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        boolean logout = healthLog.get() > 0 && mc.player.getHealth() <= healthLog.get();
        if (!logout && onPlayer.get() && mc.world != null) {
            for (PlayerEntity p : mc.world.getPlayers())
                if (p != mc.player && mc.player.distanceTo(p) <= playerRange.get()) { logout = true; break; }
        }
        if (logout) {
            mc.getNetworkHandler().getConnection().disconnect(Text.literal("[AutoLog] safety disconnect"));
            toggle();
        }
    }
}
