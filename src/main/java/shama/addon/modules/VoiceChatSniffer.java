package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice Chat Sniffer — passive ESP only. It reads the positional voice-chat custom
 * payloads the server ALREADY broadcasts to you (Simple Voice Chat), never sends or
 * modifies anything, captures NO audio, and ignores identity. When it can decode a
 * position it highlights only the CHUNK that a transmitting player is in — and only for
 * players below Y 0 (deep = likely a base), same idea as LightEsp's chunk box. If Simple
 * Voice Chat isn't installed there's nothing to read and it does nothing.
 */
public class VoiceChatSniffer extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<String> namespace = sg.add(new StringSetting.Builder().name("channel-namespace").description("Only react to custom payloads in this namespace.").defaultValue("voicechat").build());
    private final Setting<Integer> maxY = sg.add(new IntSetting.Builder().name("max-y").description("Only highlight chunks of voices below this Y.").defaultValue(0).min(-64).max(64).sliderRange(-64, 16).build());
    private final Setting<Integer> forgetSeconds = sg.add(new IntSetting.Builder().name("forget-seconds").description("Seconds before a silent source is forgotten.").defaultValue(15).range(3, 120).build());
    private final Setting<SettingColor> fill = sg.add(new ColorSetting.Builder().name("fill-color").description("Colour of the filled part of the box.").defaultValue(new SettingColor(0, 200, 255, 45)).build());
    private final Setting<SettingColor> line = sg.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline.").defaultValue(new SettingColor(0, 200, 255, 220)).build());

    // chunkLong -> last-seen millis
    private final Map<Long, Long> chunks = new ConcurrentHashMap<>();

    public VoiceChatSniffer() {
        super(shama.addon.ShamaAddon.HUNT, "voice-chat-sniffer++", "Highlights the chunk of a deep voice-chat transmitter (passive, no audio).");
    }

    @Override public void onActivate() { chunks.clear(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof CustomPayloadS2CPacket p)) return;
        // The payload accessor name varies across mappings; pull it reflectively (this module is
        // best-effort and only does anything when Simple Voice Chat is installed).
        Object payload = null;
        try {
            for (java.lang.reflect.Field f : p.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(p);
                if (v != null) { payload = v; break; }
            }
        } catch (Throwable ignored) {}
        if (payload == null) return;
        String cls = payload.getClass().getName().toLowerCase();
        if (!cls.contains(namespace.get().toLowerCase())) return;

        // Reflectively look for a Vec3d position field on the SVC payload (read-only).
        Vec3d pos = findVec(payload);
        if (pos == null || pos.y > maxY.get()) return;
        chunks.put(new ChunkPos((int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4).toLong(), System.currentTimeMillis());
    }

    private Vec3d findVec(Object o) {
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (Vec3d.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Vec3d vec) return vec;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        chunks.entrySet().removeIf(e -> now - e.getValue() > forgetSeconds.get() * 1000L);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (chunks.isEmpty()) return;
        Color f = fill.get(), l = line.get();
        for (Long key : chunks.keySet()) {
            ChunkPos cp = new ChunkPos(key);
            double x0 = cp.x * 16, z0 = cp.z * 16;
            event.renderer.box(x0, -64, z0, x0 + 16, maxY.get() + 1, z0 + 16, f, l, ShapeMode.Both, 0);
        }
    }

    @Override public String getInfoString() { return chunks.size() + " voices"; }
}
