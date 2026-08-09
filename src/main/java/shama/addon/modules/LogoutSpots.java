package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Logout Spots++ — remembers where other players vanish. A player entity that was near
 * you last tick and is gone this tick (without you leaving render range) logged out
 * there — a prime spot to dig for a stash/base. Boxes each logout position.
 */
public class LogoutSpots extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> chatAlert = sg.add(new BoolSetting.Builder().name("chat").description("Print a chat message on a new find.").defaultValue(true).build());
    private final Setting<SettingColor> color = sg.add(new ColorSetting.Builder().name("color").description("Highlight colour.").defaultValue(new SettingColor(255, 0, 255, 90)).build());

    private final Map<Integer, Vec3d> lastSeen = new HashMap<>();
    private final Map<String, Vec3d> logouts = new HashMap<>();

    public LogoutSpots() { super(shama.addon.ShamaAddon.HUNT, "logout-spots++", "Marks where other players log out."); }

    @Override public void onActivate() { lastSeen.clear(); logouts.clear(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        Map<Integer, Vec3d> current = new HashMap<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            current.put(p.getId(), new net.minecraft.util.math.Vec3d(p.getX(), p.getY(), p.getZ()));
        }
        // any id seen last tick, missing now, and within ~a chunk of where it was => logout
        for (Map.Entry<Integer, Vec3d> e : lastSeen.entrySet()) {
            if (!current.containsKey(e.getKey())) {
                Vec3d pos = e.getValue();
                if (new net.minecraft.util.math.Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(pos) < 160) {
                    String key = (int) pos.x + "," + (int) pos.y + "," + (int) pos.z;
                    if (logouts.put(key, pos) == null && chatAlert.get())
                        shama.addon.util.Chat.info("[LogoutSpots] player logged out at (%d, %d, %d)", (int) pos.x, (int) pos.y, (int) pos.z);
                }
            }
        }
        lastSeen.clear();
        lastSeen.putAll(current);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color c = color.get(); Color line = new Color(c.r, c.g, c.b, 255);
        for (Vec3d p : logouts.values())
            event.renderer.box(p.x - 0.5, p.y, p.z - 0.5, p.x + 0.5, p.y + 2, p.z + 0.5, c, line, ShapeMode.Both, 0);
    }

    @Override public String getInfoString() { return logouts.size() + " spots"; }
}
