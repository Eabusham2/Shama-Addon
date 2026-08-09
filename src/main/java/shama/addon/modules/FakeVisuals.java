package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;

import java.util.List;

/**
 * Fake Visuals — things that only ever exist on your own screen.
 *
 * Ported from the FakePay and FakeScoreboard modules in the shared files. Nothing here is sent to
 * the server and nothing changes what anyone else sees: the pay command is stopped before it leaves,
 * and the sidebar is drawn by this addon rather than coming from the server. It is for screenshots
 * and winding people up, not for getting anything out of the server.
 */
public class FakeVisuals extends Module {
    private final SettingGroup sgPay = settings.createGroup("Fake Pay");

    private final Setting<Boolean> fakePay = sgPay.add(new BoolSetting.Builder()
        .name("fake-pay")
        .description("Catch your own pay command before it is sent and print a receipt that looks real. Nothing reaches the server, so no money moves and the other person sees nothing at all.")
        .defaultValue(false).build());

    private final Setting<String> payCommand = sgPay.add(new StringSetting.Builder()
        .name("command").description("The command to catch, without the slash.")
        .defaultValue("pay").visible(fakePay::get).build());

    private final Setting<String> currency = sgPay.add(new StringSetting.Builder()
        .name("currency").description("Symbol to put in front of the amount.")
        .defaultValue("$").visible(fakePay::get).build());

    private final Setting<Boolean> blockCommand = sgPay.add(new BoolSetting.Builder()
        .name("block-command")
        .description("Stop the command from being sent at all. Turning this off sends it for real, which defeats the point.")
        .defaultValue(true).visible(fakePay::get).build());

    private final SettingGroup sgBoard = settings.createGroup("Fake Sidebar");

    private final Setting<Boolean> sidebar = sgBoard.add(new BoolSetting.Builder()
        .name("sidebar")
        .description("Draw a sidebar of your own with whatever numbers you like. It is painted by this addon, so the server has no idea it is there.")
        .defaultValue(false).build());

    private final Setting<String> title = sgBoard.add(new StringSetting.Builder()
        .name("title").description("Heading at the top of the sidebar.")
        .defaultValue("Stats").visible(sidebar::get).build());

    private final Setting<List<String>> lines = sgBoard.add(new StringListSetting.Builder()
        .name("lines")
        .description("Each entry is one row. Write them however you like, e.g. \"Balance: $1,204,000\".")
        .defaultValue(List.of("Balance: $1,204,000", "Kills: 1337", "Deaths: 0", "Playtime: 412h"))
        .visible(sidebar::get).build());

    private final Setting<Integer> boardX = sgBoard.add(new IntSetting.Builder()
        .name("x").description("Distance from the left of the screen.")
        .defaultValue(1400).min(0).max(3840).sliderRange(0, 1920).visible(sidebar::get).build());
    private final Setting<Integer> boardY = sgBoard.add(new IntSetting.Builder()
        .name("y").description("Distance from the top of the screen.")
        .defaultValue(120).min(0).max(2160).sliderRange(0, 900).visible(sidebar::get).build());
    private final Setting<SettingColor> boardBg = sgBoard.add(new ColorSetting.Builder()
        .name("background").description("Colour behind the sidebar.")
        .defaultValue(new SettingColor(0, 0, 0, 140)).visible(sidebar::get).build());
    private final Setting<SettingColor> boardText = sgBoard.add(new ColorSetting.Builder()
        .name("text-color").description("Colour of the rows.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).visible(sidebar::get).build());

    public FakeVisuals() {
        super(shama.addon.ShamaAddon.MISC, "fake-visuals++",
            "Client-side fakes for screenshots — a pay receipt that never sends, and a sidebar of your own invention.");
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!fakePay.get()) return;
        if (!(event.packet instanceof CommandExecutionC2SPacket p)) return;

        // The accessor for the command text has moved between versions, so it is found by
        // signature: the packet's only no-argument method that returns a String.
        String cmd = null;
        try {
            for (var m : p.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                if (m.getName().equals("toString") || m.getName().equals("getName")) continue;
                Object v = m.invoke(p);
                if (v instanceof String str) { cmd = str; break; }
            }
        } catch (Throwable ignored) {}
        String want = payCommand.get();
        if (cmd == null || !cmd.toLowerCase().startsWith(want.toLowerCase() + " ")) return;

        // "pay <who> <amount>"
        String[] parts = cmd.trim().split("\\s+");
        String who = parts.length > 1 ? parts[1] : "someone";
        String amount = parts.length > 2 ? parts[2] : "0";

        if (blockCommand.get()) event.cancel();      // never leaves the client
        shama.addon.util.Chat.info("You have sent %s%s to %s.", currency.get(), amount, who);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!sidebar.get()) return;
        List<String> rows = lines.get();
        if (rows.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        double lineH = text.getHeight() + 2;
        double w = text.getWidth(title.get());
        for (String r : rows) w = Math.max(w, text.getWidth(r));
        double x = boardX.get(), y = boardY.get();
        double h = lineH * (rows.size() + 1) + 8;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 4, y - 4, w + 12, h, boardBg.get());
        Renderer2D.COLOR.render();

        text.beginBig();
        text.render(title.get(), x, y, new Color(255, 255, 255, 255));
        int i = 1;
        for (String r : rows) {
            text.render(r, x, y + lineH * i, boardText.get());
            i++;
        }
        text.end();
    }
}
