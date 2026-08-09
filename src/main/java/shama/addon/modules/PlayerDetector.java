package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Player Detector — alerts the moment another player is rendered near you (the server sends
 * their entity, which is what "someone is loading you" looks like client-side). Fires a title
 * popup, a sound, and a chat line, and re-alerts if they leave your range and come back. A
 * name whitelist keeps friends from tripping it. Note: a truly vanished player that the server
 * hides from your client entirely can't be seen by anything — this catches everyone the server
 * actually renders to you, spectators included where the server sends them.
 */
public class PlayerDetector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

        private final Setting<Integer> alertCooldown = sg.add(new IntSetting.Builder()
        .name("alert-cooldown")
        .description("Don't alert on the same player again for this many seconds. Without it, somebody walking in and out of range — or an entity flickering as you cross a chunk boundary — sets off a fresh alert every time.")
        .defaultValue(30).min(0).max(600).sliderRange(0, 120).build());
    private final Setting<Boolean> realPlayersOnly = sg.add(new BoolSetting.Builder()
        .name("real-players-only")
        .description("Only count entities that also appear in the server's player list. NPCs, shop holograms and other fake players look identical to real ones in the world, and they are the usual reason this module cries wolf.")
        .defaultValue(true).build());

private final Setting<Double> range = sg.add(new DoubleSetting.Builder()
        .name("range").description("Alert when a player is within this many blocks.").defaultValue(128).min(4).sliderRange(16, 256).build());
    private final Setting<List<String>> whitelist = sg.add(new StringListSetting.Builder()
        .name("whitelist").description("Player names to ignore (case-insensitive).").defaultValue(List.of()).build());

    private final Setting<Boolean> popup = sg.add(new BoolSetting.Builder().name("popup").description("Show an on-screen title popup.").defaultValue(true).build());
    private final Setting<Boolean> chat = sg.add(new BoolSetting.Builder().name("chat").description("Send a chat message.").defaultValue(true).build());
    private final Setting<Double> soundVolume = sg.add(new DoubleSetting.Builder().name("volume").description("How loud the alert sound is (0.1 = quiet, 1.0 = full).").defaultValue(1.0).min(0.1).max(1.0).sliderRange(0.1,1.0).build());
    private final Setting<Boolean> highlightChunks = sg.add(new BoolSetting.Builder().name("highlight-chunks").description("Box the chunk each nearby player is standing in.").defaultValue(false).build());
    private final Setting<Boolean> fillChunk = sg.add(new BoolSetting.Builder().name("fill-chunk").description("Fill the player's chunk box instead of just outlining it.").defaultValue(true).visible(highlightChunks::get).build());
    private final Setting<SettingColor> chunkColor = sg.add(new ColorSetting.Builder().name("chunk-color").description("Colour of the player's chunk box.").defaultValue(new SettingColor(255, 60, 60, 180)).visible(highlightChunks::get).build());
    private final Setting<Boolean> sound = sg.add(new BoolSetting.Builder().name("sound").description("Play an alert sound.").defaultValue(true).build());

    private final Set<UUID> seen = new HashSet<>();

    public PlayerDetector() { super(shama.addon.ShamaAddon.HUNT, "player-detector++", "Popup / sound / chat when another player renders near you."); }

    @Override public void onActivate() { seen.clear(); }
    @Override public void onDeactivate() { seen.clear(); lastAlert.clear(); }

    private final java.util.Map<UUID, Long> lastAlert = new java.util.HashMap<>();

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        Set<UUID> current = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            double dist = mc.player.distanceTo(p);
            if (dist > range.get()) continue;
            String name = p.getName().getString();
            if (isWhitelisted(name)) continue;
            // Fake players (NPCs, holograms) are in the world but never in the server's player list
            if (realPlayersOnly.get() && mc.getNetworkHandler() != null
                && mc.getNetworkHandler().getPlayerListEntry(p.getUuid()) == null) continue;
            current.add(p.getUuid());
            if (!seen.contains(p.getUuid())) {
                long now = System.currentTimeMillis();
                Long last = lastAlert.get(p.getUuid());
                if (last == null || now - last >= alertCooldown.get() * 1000L) {
                    lastAlert.put(p.getUuid(), now);
                    alert(name, dist);
                }
            }
        }
        seen.clear();
        seen.addAll(current);
    }

    private boolean isWhitelisted(String name) {
        for (String w : whitelist.get()) if (w.equalsIgnoreCase(name)) return true;
        return false;
    }

    private void alert(String name, double dist) {
        if (chat.get()) shama.addon.util.Chat.warning("[PlayerDetector] %s within %.0f blocks", name, dist);
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 30, 8);
            mc.inGameHud.setTitle(Text.literal("Player Nearby").formatted(Formatting.RED));
            mc.inGameHud.setSubtitle(Text.literal(name + " (" + (int) dist + "m)").formatted(Formatting.YELLOW));
        }
        if (sound.get() && mc.getSoundManager() != null) {
            try {
                if (mc.player != null) mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING, net.minecraft.sound.SoundCategory.PLAYERS, soundVolume.get().floatValue(), 1.2f);
            } catch (Throwable ignored) {}
        }
    }

    @Override public String getInfoString() { return seen.isEmpty() ? null : seen.size() + " near"; }

    @EventHandler
    private void onRenderChunks(Render3DEvent event) {
        if (!highlightChunks.get() || mc.world == null || mc.player == null) return;
        var col = chunkColor.get();
        double bY = mc.world.getBottomY(), tY = mc.world.getTopYInclusive() + 1;
        for (var pl : mc.world.getPlayers()) {
            if (pl == mc.player) continue;
            if (pl.distanceTo(mc.player) > range.get()) continue;
            var cp = pl.getChunkPos();
            double x0 = cp.getStartX(), z0 = cp.getStartZ();
            event.renderer.box(x0, bY, z0, x0 + 16, tY, z0 + 16,
                new Color(col.r, col.g, col.b, fillChunk.get() ? 40 : 0), col, ShapeMode.Both, 0);
        }
    }
}
