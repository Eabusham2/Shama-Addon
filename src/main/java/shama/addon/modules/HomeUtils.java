package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Home Utils++ — merged from the several home-setter variants. Saves named coordinate
 * "homes" and can auto-walk toward the selected one. Homes persist for the session.
 */
public class HomeUtils extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<String> homeName = sg.add(new StringSetting.Builder()
        .name("name").description("Name for save/goto/remove.").defaultValue("base").build());

    private final Setting<Boolean> autoWalk = sg.add(new BoolSetting.Builder()
        .name("walk-to-home").description("Hold forward toward the current home until you arrive.").defaultValue(false).build());

    private final Map<String, BlockPos> homes = new LinkedHashMap<>();
    private BlockPos target;

    public HomeUtils() {
        super(shama.addon.ShamaAddon.PLAYER, "home-utils++", "Save/goto named coordinate homes; optional auto-walk.");
    }

    public void save() { if (mc.player != null) { homes.put(homeName.get(), mc.player.getBlockPos()); shama.addon.util.Chat.info("[Home] saved '%s' at %s", homeName.get(), mc.player.getBlockPos().toShortString()); } }
    public void goHome() { BlockPos p = homes.get(homeName.get()); if (p == null) { shama.addon.util.Chat.warning("[Home] no home '%s'", homeName.get()); return; } target = p; shama.addon.util.Chat.info("[Home] walking to '%s' %s", homeName.get(), p.toShortString()); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!autoWalk.get() || target == null || mc.player == null) { if (mc.options != null) mc.options.forwardKey.setPressed(false); return; }
        double dx = target.getX() + 0.5 - mc.player.getX();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        if (dx * dx + dz * dz < 4) { target = null; mc.options.forwardKey.setPressed(false); shama.addon.util.Chat.info("[Home] arrived."); return; }
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        mc.player.setYaw(yaw);
        mc.options.forwardKey.setPressed(true);
    }

    @Override public void onDeactivate() { if (mc.options != null) mc.options.forwardKey.setPressed(false); target = null; }
    @Override public String getInfoString() { return homes.size() + " homes"; }
}
