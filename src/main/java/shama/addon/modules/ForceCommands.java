package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Optional;

/**
 * Force Commands — singleplayer only. Ops you on your own integrated server so
 * every command works even in a world created without cheats. On a real server
 * mc.getServer() is null and this does nothing — you can't grant yourself op on
 * someone else's server, and pretending otherwise would be a lie.
 *
 * 1.21.11 op API (verified against Yarn 1.21.11): the operator list keys on a
 * PlayerConfigEntry, obtained from PlayerEntity#getPlayerConfigEntry(). The calls
 * are direct (not reflective) so Loom remaps them correctly in the built jar —
 * reflection-by-name would silently break after remapping.
 */
public class ForceCommands extends Module {
    public ForceCommands() {
        super(shama.addon.ShamaAddon.MISC, "force-commands++", "Give yourself operator permissions so all commands work even with cheats off. Works where you have that authority (your own worlds and servers that allow it).");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getServer() == null) return;

        PlayerManager pm = mc.getServer().getPlayerManager();
        ServerPlayerEntity sp = pm.getPlayer(mc.player.getUuid());
        if (sp == null) return;

        PlayerConfigEntry entry = sp.getPlayerConfigEntry();
        if (!pm.isOperator(entry)) {
            pm.addToOperators(entry, Optional.empty(), Optional.empty());
        }
    }

    @Override
    public String getInfoString() {
        return mc.getServer() != null ? "op" : "SP only";
    }
}
