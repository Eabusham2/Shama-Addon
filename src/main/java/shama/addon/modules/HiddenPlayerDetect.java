package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hidden Player Detect — spots players moving around underground by the traces their movement leaves behind. */
public class HiddenPlayerDetect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInventory = settings.createGroup("Inventory");
    private final Setting<Integer> confirmThreshold = sgInventory.add(new IntSetting.Builder().name("confirm-threshold").description("Score needed to confirm a chunk.").defaultValue(3).min(2).sliderMax(15).build());
    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder().name("chat").description("Print a message in chat.").defaultValue(true).build());
    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder().name("color").description("Highlight colour.").defaultValue(new SettingColor(255, 40, 40, 120)).build());

    // ===== vanished / spectator staff: they are invisible, but they still take up a slot =====
    private final SettingGroup sgVanish = settings.createGroup("Vanished Staff");

    private final Setting<Boolean> countMismatch = sgVanish.add(new BoolSetting.Builder()
        .name("count-mismatch")
        .description("Compare the number of players the server says are online against how many are actually in the tab list. Vanish plugins hide the entry but usually forget the count, so a gap means somebody is on who does not want to be seen. This costs nothing and catches most setups.")
        .defaultValue(true).build());

    private final Setting<Boolean> soundsNoSource = sgVanish.add(new BoolSetting.Builder()
        .name("unexplained-sounds")
        .description("Watch for player noises — footsteps, doors, containers, breaking blocks — arriving from places where nobody is visible. A spectator makes no sound, but staff watching in survival do, and the sound carries a position even when the player does not.")
        .defaultValue(true).build());

    private final Setting<Integer> soundRadius = sgVanish.add(new IntSetting.Builder()
        .name("attribute-radius")
        .description("How close a visible player must be for a sound to be treated as theirs. Anything outside that is unexplained.")
        .defaultValue(24).min(4).max(128).sliderRange(8, 64).visible(soundsNoSource::get).build());

    private final Setting<Boolean> entityGaps = sgVanish.add(new BoolSetting.Builder()
        .name("entity-id-gaps")
        .description("Watch the entity numbers the server hands out. They climb steadily, so a jump means entities were created that you were never told about — which is what happens when somebody joins hidden or a spectator spawns in.")
        .defaultValue(false).build());

    private final Setting<Integer> gapSize = sgVanish.add(new IntSetting.Builder()
        .name("min-gap")
        .description("How big a jump in those numbers has to be before it counts. Small gaps happen naturally from arrows and dropped items.")
        .defaultValue(40).min(5).max(500).sliderRange(10, 150).visible(entityGaps::get).build());

    private final Setting<Boolean> ghostChunks = sgVanish.add(new BoolSetting.Builder()
        .name("chunks-held-open")
        .description("Watch for ground staying loaded with nobody visible near it. A spectator still holds chunks open exactly like a normal player, and that is the one thing vanishing cannot hide — it is the strongest signal here.")
        .defaultValue(true).build());

    private final Setting<Boolean> vanishChat = sgVanish.add(new BoolSetting.Builder()
        .name("report-vanished")
        .description("Say in chat when one of these trips.").defaultValue(true).build());

    private java.util.Set<String> lastList = new java.util.HashSet<>();
    private long prevChunkAt;
    private int chunkStreak;
    private int lastEntityId = -1;
    private long lastVanishAlert;

    private final Map<Long, Integer> scores = new ConcurrentHashMap<>();

    public HiddenPlayerDetect() { super(shama.addon.ShamaAddon.HUNT, "hidden-player-detect++", "Spots players moving around underground where you can't see them, by picking up the traces their movement leaves behind."); }

    @Override public void onActivate() { scores.clear(); lastList.clear(); prevChunkAt = 0; chunkStreak = 0; lastEntityId = -1; }
    @Override public void onDeactivate() { scores.clear(); }

    private void addScore(long key, int delta, String reason) {
        int wasConfirmed = scores.getOrDefault(key, 0);
        int now = wasConfirmed + delta;
        scores.put(key, now);
        if (wasConfirmed < confirmThreshold.get() && now >= confirmThreshold.get() && chat.get()) {
            ChunkPos cp = new ChunkPos(key);
            shama.addon.util.Chat.info("[HiddenPlayerDetect] underground activity at %d, %d (%s)", cp.x, cp.z, reason);
        }
    }

    /** Alert about a vanished player, rate-limited so one cause cannot spam. */
    private void vanishAlert(String why) {
        long now = System.currentTimeMillis();
        if (now - lastVanishAlert < 15000) return;
        lastVanishAlert = now;
        if (vanishChat.get()) shama.addon.util.Chat.warning("[HiddenPlayerDetect] %s", why);
    }

    /** Nobody visible within the radius of this position. */
    private boolean nobodyNear(double x, double y, double z) {
        if (mc.world == null || mc.player == null) return false;
        double r = soundRadius.get(), r2 = r * r;
        for (var p : mc.world.getPlayers()) {
            if (p.squaredDistanceTo(x, y, z) <= r2) return false;
        }
        return true;
    }

    /** Ground staying loaded with nobody visible: a spectator holds chunks open like anyone else. */
    @EventHandler
    private void onGhostChunks(meteordevelopment.meteorclient.events.world.ChunkDataEvent event) {
        if (!ghostChunks.get() || mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (prevChunkAt > 0 && now - prevChunkAt <= 3) {
            if (++chunkStreak >= 24) {
                chunkStreak = 0;
                vanishAlert("ground here is being held open with nobody visible — somebody is watching");
            }
        } else chunkStreak = 0;
        prevChunkAt = now;
    }

    @EventHandler
    private void onVanishPackets(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;

        // A vanish toggle pulls somebody out of the tab list without a leave message, so a name that
        // disappears while the list is otherwise steady is somebody going hidden rather than leaving.
        if (countMismatch.get()) {
            try {
                java.util.Set<String> now = new java.util.HashSet<>();
                for (var e : mc.getNetworkHandler().getPlayerList()) now.add(e.getProfile().name());
                if (!lastList.isEmpty()) {
                    for (String n : lastList) {
                        if (!now.contains(n)) vanishAlert(n + " dropped out of the player list without leaving — vanished");
                    }
                }
                lastList = now;
            } catch (Throwable ignored) {}
        }

        // player noises with nobody around to have made them
        if (soundsNoSource.get()
            && event.packet instanceof net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket p) {
            try {
                // position only: naming the sound needs an API this build cannot confirm, and the
                // position is the part that matters anyway
                if (nobodyNear(p.getX(), p.getY(), p.getZ()) && mc.player.squaredDistanceTo(
                        p.getX(), p.getY(), p.getZ()) > (double) soundRadius.get() * soundRadius.get())
                    vanishAlert(String.format("sound at %.0f, %.0f, %.0f with nobody in sight",
                        p.getX(), p.getY(), p.getZ()));
            } catch (Throwable ignored) {}
        }

        // entity numbers climbing faster than the entities you were shown
        if (entityGaps.get()
            && event.packet instanceof net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket p) {
            try {
                int id = p.getEntityId();
                if (lastEntityId > 0 && id - lastEntityId >= gapSize.get())
                    vanishAlert((id - lastEntityId) + " entities were created that you were never shown");
                if (id > lastEntityId) lastEntityId = id;
            } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        // rubberband: server correcting your position while you weren't moving fast = something loaded nearby
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            addScore(mc.player.getChunkPos().toLong(), 3, "RUBBERBAND");
        }
        // a container screen opened below Y0 nearby (loot correlation)
        else if (event.packet instanceof OpenScreenS2CPacket && mc.player.getY() < 0) {
            addScore(mc.player.getChunkPos().toLong(), 2, "SCREEN_OPEN_Y<0");
        }
        // inventory contents pushed for an open container below Y0 = confirmed loot/storage nearby
        else if (event.packet instanceof InventoryS2CPacket && mc.player.getY() < 0) {
            addScore(mc.player.getChunkPos().toLong(), 5, "LOOT_CONTENTS");
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (scores.isEmpty() || mc.world == null) return;
        var c = color.get();
        double bottom = mc.world.getBottomY(), top = 0;
        for (var e : scores.entrySet()) {
            if (e.getValue() < confirmThreshold.get()) continue;
            ChunkPos cp = new ChunkPos(e.getKey()); double x0 = cp.getStartX(), z0 = cp.getStartZ();
            event.renderer.box(x0, bottom, z0, x0 + 16, top, z0 + 16, new Color(c.r, c.g, c.b, 40), c, ShapeMode.Both, 0);
        }
    }
}
