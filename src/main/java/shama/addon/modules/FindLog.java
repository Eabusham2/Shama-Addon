package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find Log — one place for everything the finders turn up.
 *
 * There are dozens of detection modules here and each one reports on its own. Alerts scroll away in
 * chat and boxes get left behind in the world, so an hour of flying leaves you with no record of
 * where anything actually was. This collects every find as it happens, keeps the coordinates, and
 * shows them sorted by whatever matters to you.
 *
 * It reads the alerts the modules already print rather than hooking into each one, so every finder
 * is covered automatically and any new one is too.
 */
public class FindLog extends Module {
    /** [Module] some text at 123, 45, -678 — coordinates in almost any shape the modules print. */
    private static final Pattern SOURCE = Pattern.compile("\\[(\\w+)]");
    private static final Pattern COORDS = Pattern.compile(
        "(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern COORDS_XZ = Pattern.compile(
        "(?:at|around|chunk)\\s+(-?\\d+)\\s*,\\s*(-?\\d+)(?!\\s*,)");

    public enum Sort {
        /** Closest first — what to walk to next. */
        Distance,
        /** Newest first — what just happened. */
        Recent
    }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Sort> sort = sg.add(new EnumSetting.Builder<Sort>()
        .name("sort")
        .description("Closest first when you are deciding where to walk, newest first when you are watching things happen.")
        .defaultValue(Sort.Distance).build());

    private final Setting<Integer> shown = sg.add(new IntSetting.Builder()
        .name("rows")
        .description("How many finds to list at once.")
        .defaultValue(10).min(1).max(40).sliderRange(3, 25).build());

    private final Setting<Integer> keepMinutes = sg.add(new IntSetting.Builder()
        .name("keep-for")
        .description("Drop a find from the list after this many minutes. Set it high if you want a record of a whole session.")
        .defaultValue(30).min(1).max(1440).sliderRange(5, 240).build());

    private final Setting<Integer> maxDistance = sg.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Ignore finds further away than this, in blocks. 0 keeps everything however far it was.")
        .defaultValue(0).min(0).max(10000).sliderRange(0, 2000).build());

    private final Setting<List<String>> ignore = sg.add(new StringListSetting.Builder()
        .name("ignore")
        .description("Skip finds whose text contains any of these. Useful for muting one noisy detector without turning it off.")
        .defaultValue(List.of()).build());

    // ---------------------------------------------------------------- panel
    private final SettingGroup sgPanel = settings.createGroup("Panel");

    private final Setting<Boolean> panel = sgPanel.add(new BoolSetting.Builder()
        .name("panel").description("Show the list on screen.").defaultValue(true).build());
    private final Setting<Integer> panelX = sgPanel.add(new IntSetting.Builder()
        .name("x").description("Distance from the left of the screen.")
        .defaultValue(6).min(0).max(3840).sliderRange(0, 1200).visible(panel::get).build());
    private final Setting<Integer> panelY = sgPanel.add(new IntSetting.Builder()
        .name("y").description("Distance from the top of the screen.")
        .defaultValue(200).min(0).max(2160).sliderRange(0, 900).visible(panel::get).build());
    private final Setting<Boolean> showSource = sgPanel.add(new BoolSetting.Builder()
        .name("show-source").description("Put the module that found it in front of each row.")
        .defaultValue(true).visible(panel::get).build());
    private final Setting<Boolean> showAge = sgPanel.add(new BoolSetting.Builder()
        .name("show-age").description("Show how long ago each find happened.")
        .defaultValue(true).visible(panel::get).build());
    private final Setting<SettingColor> panelBg = sgPanel.add(new ColorSetting.Builder()
        .name("background").description("Colour behind the list.")
        .defaultValue(new SettingColor(0, 0, 0, 140)).visible(panel::get).build());
    private final Setting<SettingColor> panelText = sgPanel.add(new ColorSetting.Builder()
        .name("text-color").description("Colour of the rows.")
        .defaultValue(new SettingColor(190, 235, 255, 255)).visible(panel::get).build());

    // ---------------------------------------------------------------- world
    private final SettingGroup sgWorld = settings.createGroup("In World");

    private final Setting<Boolean> markers = sgWorld.add(new BoolSetting.Builder()
        .name("markers")
        .description("Box every logged find in the world, so a whole session's discoveries stay visible at once even after each module has forgotten its own.")
        .defaultValue(false).build());
    private final Setting<Boolean> beams = sgWorld.add(new BoolSetting.Builder()
        .name("beams").description("Shoot a beam up from each one so you can see them over terrain.")
        .defaultValue(false).visible(markers::get).build());
    private final Setting<SettingColor> markerColor = sgWorld.add(new ColorSetting.Builder()
        .name("marker-color").description("Colour of those markers.")
        .defaultValue(new SettingColor(120, 220, 255, 90)).visible(markers::get).build());

    /** One recorded find. */
    private record Find(String source, String text, double x, double y, double z, long when) {}

    private final List<Find> finds = new ArrayList<>();
    private int seen;

    public FindLog() {
        super(shama.addon.ShamaAddon.HUNT, "find-log++",
            "Collects every find from every detection module into one list with coordinates, sorted by distance or by when it happened.");
    }

    @Override
    public void onActivate() { finds.clear(); seen = shama.addon.util.Chat.recent().size(); }

    @Override
    public void onDeactivate() { finds.clear(); }

    /** Pull anything new out of the shared alert record. */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        List<shama.addon.util.Chat.Entry> log = shama.addon.util.Chat.recent();
        if (log.size() < seen) seen = 0;                       // the record rolled over
        for (int i = seen; i < log.size(); i++) parse(log.get(i));
        seen = log.size();

        long cutoff = System.currentTimeMillis() - keepMinutes.get() * 60_000L;
        finds.removeIf(f -> f.when() < cutoff);
    }

    private void parse(shama.addon.util.Chat.Entry e) {
        String text = e.text();
        for (String skip : ignore.get()) {
            if (!skip.isBlank() && text.toLowerCase().contains(skip.toLowerCase())) return;
        }

        String source = "";
        Matcher sm = SOURCE.matcher(text);
        if (sm.find()) source = sm.group(1);

        Double x = null, y = null, z = null;
        Matcher cm = COORDS.matcher(text);
        if (cm.find()) {
            x = Double.parseDouble(cm.group(1));
            y = Double.parseDouble(cm.group(2));
            z = Double.parseDouble(cm.group(3));
        } else {
            Matcher xz = COORDS_XZ.matcher(text);
            if (!xz.find()) return;                            // nothing locatable in this message
            x = Double.parseDouble(xz.group(1));
            z = Double.parseDouble(xz.group(2));
            y = mc.player.getY();                              // only a column was reported
        }

        if (maxDistance.get() > 0) {
            double d = mc.player.getPos().distanceTo(new net.minecraft.util.math.Vec3d(x, y, z));
            if (d > maxDistance.get()) return;
        }

        // strip the source tag out of the row text, it is shown separately
        String body = sm.find(0) ? text.replaceFirst("\\[\\w+]\\s*", "") : text;
        finds.add(new Find(source, body.trim(), x, y, z, e.time()));
        while (finds.size() > 400) finds.remove(0);
    }

    private List<Find> ordered() {
        List<Find> copy = new ArrayList<>(finds);
        if (sort.get() == Sort.Recent) {
            copy.sort((a, b) -> Long.compare(b.when(), a.when()));
        } else if (mc.player != null) {
            var me = mc.player.getPos();
            copy.sort((a, b) -> Double.compare(
                me.squaredDistanceTo(a.x(), a.y(), a.z()),
                me.squaredDistanceTo(b.x(), b.y(), b.z())));
        }
        return copy;
    }

    private static String ago(long when) {
        long s = (System.currentTimeMillis() - when) / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h";
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!panel.get() || mc.player == null) return;
        List<Find> list = ordered();
        if (list.isEmpty()) return;

        int rows = Math.min(shown.get(), list.size());
        TextRenderer text = TextRenderer.get();
        double lineH = text.getHeight() + 2;
        double x = panelX.get(), y = panelY.get();

        List<String> out = new ArrayList<>();
        out.add("Finds (" + finds.size() + ")");
        var me = mc.player.getPos();
        for (int i = 0; i < rows; i++) {
            Find f = list.get(i);
            int dist = (int) Math.sqrt(me.squaredDistanceTo(f.x(), f.y(), f.z()));
            StringBuilder row = new StringBuilder();
            if (showSource.get() && !f.source().isEmpty()) row.append(f.source()).append(": ");
            row.append(String.format("%.0f, %.0f, %.0f", f.x(), f.y(), f.z()));
            row.append("  ").append(dist).append("m");
            if (showAge.get()) row.append("  ").append(ago(f.when()));
            out.add(row.toString());
        }

        double w = 0;
        for (String r : out) w = Math.max(w, text.getWidth(r));

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 4, y - 4, w + 12, lineH * out.size() + 8, panelBg.get());
        Renderer2D.COLOR.render();

        text.beginBig();
        text.render(out.get(0), x, y, new Color(255, 255, 255, 255));
        for (int i = 1; i < out.size(); i++) text.render(out.get(i), x, y + lineH * i, panelText.get());
        text.end();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!markers.get() || finds.isEmpty()) return;
        SettingColor c = markerColor.get();
        Color fill = new Color(c.r, c.g, c.b, c.a);
        Color line = new Color(c.r, c.g, c.b, Math.min(255, c.a + 140));

        for (Find f : finds) {
            event.renderer.box(f.x() - 0.5, f.y() - 0.5, f.z() - 0.5,
                f.x() + 0.5, f.y() + 0.5, f.z() + 0.5, fill, line, ShapeMode.Both, 0);
            if (beams.get())
                event.renderer.box(f.x() - 0.15, f.y(), f.z() - 0.15,
                    f.x() + 0.15, f.y() + 320, f.z() + 0.15,
                    new Color(c.r, c.g, c.b, 30), line, ShapeMode.Both, 0);
        }
    }

    @Override
    public String getInfoString() { return finds.isEmpty() ? null : Integer.toString(finds.size()); }
}
