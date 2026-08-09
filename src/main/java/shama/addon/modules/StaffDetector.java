package shama.addon.modules;

import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Staff Detector++ — flags likely staff by gamemode, watched names, staff-role tags, or unusual names, and alerts when they go ONLINE or OFFLINE (popup + chat + sound). */
public class StaffDetector extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    // ---- detection methods (each a tick) ----
    private final Setting<Boolean> spectator = sg.add(new BoolSetting.Builder().name("spectator").description("Flag tab-list players in spectator mode (common for staff watching you).").defaultValue(true).build());
    private final Setting<Boolean> creative = sg.add(new BoolSetting.Builder().name("creative").description("Flag tab-list players in creative mode.").defaultValue(true).build());
    private final Setting<List<String>> watchedPlayers = sg.add(new StringListSetting.Builder().name("staff-usernames").description("Exact staff usernames — anyone here is always alerted on when they're online (case-insensitive). Pre-filled with known DonutSMP staff; add or remove freely.").defaultValue(List.of("0Gsummer", "Bigboss_jeff123", "Bronuts", "Dough4", "DrDonutt", "Fallerfly", "FluffyMaster07", "Frwost", "ItszDaBaby", "Itszdeath", "LzouZMp5", "Munkerlich", "NoahvdAa", "Pastagamer08", "Rokezy", "RyuuI_", "W1zoX_", "_chaon", "archivePedro", "bautiedgar", "showered")).build());
    private final Setting<Boolean> detectRoles = sg.add(new BoolSetting.Builder().name("detect-roles").description("Flag anyone whose tab-list name shows a staff tag like (Admin)/(Mod)/[Staff]/(Dev).").defaultValue(true).build());

    // ---- prefix detection with its own black/whitelist ----
    private final Setting<Boolean> detectPrefixes = sg.add(new BoolSetting.Builder().name("detect-prefixes").description("Turn prefix/name detection on or off. Uses the blacklist and whitelist below.").defaultValue(true).build());
    private final Setting<List<String>> prefixBlacklist = sg.add(new StringListSetting.Builder().name("prefix-blacklist").description("If a player's name contains any of these words/prefixes, flag them (e.g. dev, admin, mod, staff). Also includes the star symbols servers commonly put on staff ranks.").defaultValue(List.of("dev", "admin", "mod", "owner", "staff", "helper", "\u2605", "\u2606", "\u272A", "\u2B50")).visible(detectPrefixes::get).build());
    private final Setting<List<String>> prefixWhitelist = sg.add(new StringListSetting.Builder().name("prefix-whitelist").description("If a player's name contains any of these, never flag them by name/prefix (e.g. +, media, yt, or the camera icon servers give content creators). Overrides the blacklist and unusual-name check.").defaultValue(List.of("+", "media", "yt", "twitch", "\uD83D\uDCF7", "\uD83C\uDFA5", "\u25B6")).visible(detectPrefixes::get).build());
    private final Setting<Boolean> detectUnusualNames = sg.add(new BoolSetting.Builder().name("flag-odd-names").description("On top of the blacklist, also flag any name with a space or a character a normal Minecraft name can't have (coloured/bracketed staff names). Still respects the whitelist.").defaultValue(false).visible(detectPrefixes::get).build());

    // ---- alerts ----
    private final Setting<Boolean> onlineAlert = sgAlerts.add(new BoolSetting.Builder().name("online-alert").description("Alert when a flagged player comes online / appears.").defaultValue(true).build());
    private final Setting<Boolean> offlineAlert = sgAlerts.add(new BoolSetting.Builder().name("offline-alert").description("Alert when a flagged player goes offline / leaves.").defaultValue(true).build());
    private final SettingGroup sgPanel = settings.createGroup("Panel");
    private final Setting<Boolean> panel = sgPanel.add(new BoolSetting.Builder()
        .name("panel").description("Show a small on-screen list of the flagged staff who are currently online.").defaultValue(true).build());
    private final Setting<Integer> panelX = sgPanel.add(new IntSetting.Builder()
        .name("panel-x").description("Panel position from the left of the screen.").defaultValue(4).min(0).max(3840).sliderRange(0, 800).visible(panel::get).build());
    private final Setting<Integer> panelY = sgPanel.add(new IntSetting.Builder()
        .name("panel-y").description("Panel position from the top of the screen.").defaultValue(80).min(0).max(2160).sliderRange(0, 600).visible(panel::get).build());
    private final Setting<SettingColor> panelColor = sgPanel.add(new ColorSetting.Builder()
        .name("panel-text-color").description("Colour of the names in the panel.").defaultValue(new SettingColor(255, 80, 80, 255)).visible(panel::get).build());

    private final Setting<Boolean> popup = sgAlerts.add(new BoolSetting.Builder().name("popup").description("Show a title/subtitle popup on screen. Off by default — chat is less intrusive when several staff log on at once.").defaultValue(false).build());
    private final Setting<Boolean> chat = sgAlerts.add(new BoolSetting.Builder().name("chat").description("Print the alert in chat.").defaultValue(true).build());
    private final Setting<Boolean> sound = sgAlerts.add(new BoolSetting.Builder().name("sound").description("Play a sound on alert.").defaultValue(false).build());

    private final Map<String, String> flaggedOnline = new HashMap<>(); // name -> reason

    public StaffDetector() { super(shama.addon.ShamaAddon.HUNT, "staff-detector++", "Alerts (popup/chat/sound) when likely staff go online or offline."); }

    @Override public void onActivate() { flaggedOnline.clear(); }

    private String reasonFor(PlayerListEntry e) {
        String plain = e.getProfile().name();
        Text dn = e.getDisplayName();
        String display = dn != null ? dn.getString() : plain;
        GameMode gm = e.getGameMode();
        if (spectator.get() && gm == GameMode.SPECTATOR) return "spectator";
        if (creative.get() && gm == GameMode.CREATIVE) return "creative";
        if (plain != null) for (String w : watchedPlayers.get()) if (plain.equalsIgnoreCase(w)) return "watched";
        String low = display.toLowerCase();
        if (detectRoles.get() && (low.contains("(admin)") || low.contains("(mod)") || low.contains("(owner)") || low.contains("[staff]") || low.contains("(helper)") || low.contains("(dev)"))) return "staff role";
        if (detectPrefixes.get()) {
            // whitelist wins: if any whitelisted term is in the name, never flag by prefix/unusual
            boolean whitelisted = false;
            for (String w : prefixWhitelist.get()) if (!w.isBlank() && low.contains(w.toLowerCase())) { whitelisted = true; break; }
            if (!whitelisted) {
                for (String b : prefixBlacklist.get()) if (!b.isBlank() && low.contains(b.toLowerCase())) return "blacklisted prefix";
                if (detectUnusualNames.get()) {
                    boolean unusual = display.contains(" ") || (plain != null && !plain.matches("[a-zA-Z0-9_]+")) || !display.replaceAll("§.", "").matches("[a-zA-Z0-9_ ]+");
                    if (unusual) return "unusual name";
                }
            }
        }
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.getNetworkHandler() == null) return;
        Set<String> seen = new HashSet<>();
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            String reason = reasonFor(e);
            if (reason == null) continue;
            Text dn = e.getDisplayName();
            String name = dn != null ? dn.getString() : e.getProfile().name();
            seen.add(name);
            if (!flaggedOnline.containsKey(name)) {           // newly online
                flaggedOnline.put(name, reason);
                if (onlineAlert.get()) alert(name, reason, true);
            }
        }
        // anyone previously flagged but no longer in tab = went offline
        flaggedOnline.keySet().removeIf(name -> {
            if (!seen.contains(name)) { if (offlineAlert.get()) alert(name, flaggedOnline.get(name), false); return true; }
            return false;
        });
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!panel.get() || flaggedOnline.isEmpty()) return;
        var text = meteordevelopment.meteorclient.renderer.text.TextRenderer.get();
        double x = panelX.get(), y = panelY.get();
        String header = "Staff online (" + flaggedOnline.size() + ")";
        double w = text.getWidth(header);
        for (String n : flaggedOnline.keySet()) w = Math.max(w, text.getWidth(n) + 8);
        double lineH = text.getHeight() + 1;
        double h = lineH * (flaggedOnline.size() + 1) + 6;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 3, y - 3, w + 8, h, new Color(0, 0, 0, 140));
        Renderer2D.COLOR.render();

        text.beginBig();
        text.render(header, x, y, new Color(255, 255, 255, 255));
        int i = 1;
        for (String n : flaggedOnline.keySet()) {
            text.render("- " + n, x, y + lineH * i, panelColor.get());
            i++;
        }
        text.end();
    }

    private void alert(String name, String reason, boolean online) {
        String state = online ? "ONLINE" : "OFFLINE";
        if (chat.get()) {
            if (online) shama.addon.util.Chat.warning("[StaffDetector] %s is %s (%s)", name, state, reason);
            else shama.addon.util.Chat.info("[StaffDetector] %s went %s (%s)", name, state, reason);
        }
        if (popup.get() && mc.inGameHud != null) {
            mc.inGameHud.setTitleTicks(2, 40, 8);
            mc.inGameHud.setTitle(Text.literal((online ? "§c⚠ Staff " : "§a✔ Staff ") + state));
            mc.inGameHud.setSubtitle(Text.literal("§7" + name + " §8(" + reason + ")"));
        }
        if (sound.get() && mc.getSoundManager() != null) {
            try {
                if (mc.world != null && mc.player != null) mc.world.playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    online ? SoundEvents.BLOCK_NOTE_BLOCK_PLING : SoundEvents.BLOCK_NOTE_BLOCK_BASS, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, online ? 1.5f : 0.8f);
            } catch (Throwable ignored) {}
        }
    }

    @Override public String getInfoString() { return flaggedOnline.size() + " flagged"; }
}
