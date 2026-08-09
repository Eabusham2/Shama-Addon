package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/** PacketLogger — prints the class name of in/out packets to chat (filterable) for debugging server behaviour. */
public class PacketLogger extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> incoming = sg.add(new BoolSetting.Builder().name("incoming").description("Log packets coming from the server.").defaultValue(true).build());
    private final Setting<Boolean> outgoing = sg.add(new BoolSetting.Builder().name("outgoing").description("Log packets you send.").defaultValue(false).build());
    private final Setting<String> filter = sg.add(new StringSetting.Builder().name("name-filter").description("Only log packets whose class name contains this (blank = all).").defaultValue("").build());

    public PacketLogger() { super(shama.addon.ShamaAddon.MISC, "packet-logger++", "Lists the network packets going past in chat — a debug tool for seeing what the server is actually sending."); }

    @EventHandler private void onR(PacketEvent.Receive e) { if (incoming.get()) log("<-", e.packet.getClass().getSimpleName()); }
    @EventHandler private void onS(PacketEvent.Send e) { if (outgoing.get()) log("->", e.packet.getClass().getSimpleName()); }

    private void log(String dir, String name) {
        String f = filter.get();
        if (!f.isEmpty() && !name.toLowerCase().contains(f.toLowerCase())) return;
        shama.addon.util.Chat.info("[Packet] %s %s", dir, name);
    }
}
