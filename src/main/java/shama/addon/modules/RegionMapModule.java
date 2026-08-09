package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

/**
 * Region Map - draws the server's region grid: a 9x9 board of numbered regions, each shaded by
 * which datacentre it belongs to, with a legend and a marker for the region you're standing in.
 *
 * The world is split into 50,000-block squares offset by 225,000, so a coordinate maps to a cell
 * with (coord + 225000) / 50000.
 */
public class RegionMapModule extends Module {
    private static final int GRID = 9;
    private static final double REGION_SIZE = 50000.0;
    private static final double MAP_OFFSET = 225000.0;

    private static final String[] TYPE_NAMES = {"EU Central", "EU West", "NA East", "NA West", "Asia", "Oceania"};
    private static final Color[] TYPE_COLORS = {
        new Color(159, 206, 99), new Color(0, 166, 99), new Color(79, 173, 234),
        new Color(47, 110, 186), new Color(245, 194, 66), new Color(252, 136, 3)
    };

    /** {regionId, typeIndex} in row-major order. */
    private static final int[][] LAYOUT = {
        {82,5},{100,3},{101,3},{102,3},{103,2},{104,2},{105,2},{106,2},{91,2},
        {83,5},{44,3},{75,3},{42,3},{41,2},{40,2},{39,2},{38,2},{92,2},
        {84,5},{45,3},{14,3},{13,3},{12,2},{11,2},{10,2},{37,2},{93,2},
        {85,5},{46,5},{74,5},{3,3},{2,2},{1,2},{25,2},{36,2},{94,2},
        {86,4},{47,4},{72,4},{71,4},{5,2},{4,2},{24,2},{35,2},{95,2},
        {87,4},{51,1},{17,1},{9,0},{8,0},{7,0},{23,0},{34,0},{96,2},
        {88,4},{54,1},{18,1},{61,0},{62,0},{21,0},{22,0},{33,0},{97,0},
        {89,0},{26,1},{27,0},{28,0},{29,0},{30,0},{59,0},{32,0},{98,0},
        {90,0},{107,1},{108,1},{109,1},{110,1},{111,1},{112,1},{113,1},{99,0}
    };

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> posX = sg.add(new IntSetting.Builder()
        .name("x").description("Distance from the left of the screen.")
        .defaultValue(12).min(0).max(3840).sliderRange(0, 800).build());
    private final Setting<Integer> posY = sg.add(new IntSetting.Builder()
        .name("y").description("Distance from the top of the screen.")
        .defaultValue(148).min(0).max(2160).sliderRange(0, 800).build());
    private final Setting<Integer> cellSize = sg.add(new IntSetting.Builder()
        .name("cell-size").description("How big each region square is, in pixels.")
        .defaultValue(10).min(4).max(40).sliderRange(6, 24).build());
    private final Setting<Integer> cellGap = sg.add(new IntSetting.Builder()
        .name("cell-gap").description("Gap between squares, in pixels.")
        .defaultValue(1).min(0).max(6).sliderRange(0, 4).build());

    private final SettingGroup sgShow = settings.createGroup("Show");
    private final Setting<Boolean> showIds = sgShow.add(new BoolSetting.Builder()
        .name("region-numbers").description("Print each region's number inside its square.").defaultValue(true).build());
    private final Setting<Boolean> showLegend = sgShow.add(new BoolSetting.Builder()
        .name("legend").description("Show the colour key listing each datacentre.").defaultValue(true).build());
    private final Setting<Boolean> showPlayer = sgShow.add(new BoolSetting.Builder()
        .name("player-marker").description("Mark where you are on the grid.").defaultValue(true).build());
    private final Setting<Boolean> showInfo = sgShow.add(new BoolSetting.Builder()
        .name("current-region").description("Write the region you're standing in, and its datacentre, under the map.").defaultValue(true).build());
    private final Setting<Boolean> highlightCurrent = sgShow.add(new BoolSetting.Builder()
        .name("highlight-current").description("Outline the square you're currently in.").defaultValue(true).build());

    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final Setting<Integer> opacity = sgColors.add(new IntSetting.Builder()
        .name("transparency").description("How solid the region squares are.")
        .defaultValue(210).min(30).max(255).sliderRange(60, 255).build());
    private final Setting<SettingColor> background = sgColors.add(new ColorSetting.Builder()
        .name("background").description("Colour behind the map.").defaultValue(new SettingColor(0, 0, 0, 150)).build());
    private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder()
        .name("player-color").description("Colour of your marker.").defaultValue(new SettingColor(255, 255, 255, 255)).visible(showPlayer::get).build());
    private final Setting<SettingColor> textColor = sgColors.add(new ColorSetting.Builder()
        .name("text-color").description("Colour of the numbers and labels.").defaultValue(new SettingColor(255, 255, 255, 255)).build());

    public RegionMapModule() {
        super(shama.addon.ShamaAddon.HUNT, "region-map++",
            "Shows the server's region grid - every region numbered and shaded by which datacentre it runs on, with a marker for the one you're in.");
    }

    private static int gridIndex(double coord) {
        return (int) Math.floor((coord + MAP_OFFSET) / REGION_SIZE);
    }

    private static int[] cellAt(int gx, int gz) {
        if (gx < 0 || gx >= GRID || gz < 0 || gz >= GRID) return null;
        return LAYOUT[gz * GRID + gx];
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null) return;

        int cs = cellSize.get(), gap = cellGap.get(), step = cs + gap;
        int ox = posX.get(), oy = posY.get();
        int pad = 4;
        int mapPixels = GRID * step - gap;

        TextRenderer text = TextRenderer.get();
        double lineH = text.getHeight() + 1;
        int legendW = showLegend.get() ? 80 : 0;
        double infoH = showInfo.get() ? lineH + 2 : 0;
        double panelW = mapPixels + pad * 2 + (legendW > 0 ? legendW + 4 : 0);
        double panelH = mapPixels + pad * 2 + infoH;

        int gx = gridIndex(mc.player.getX()), gz = gridIndex(mc.player.getZ());
        int[] here = cellAt(gx, gz);

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(ox - pad, oy - pad, panelW, panelH, background.get());
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                int[] cell = LAYOUT[row * GRID + col];
                Color base = TYPE_COLORS[Math.max(0, Math.min(cell[1], TYPE_COLORS.length - 1))];
                Renderer2D.COLOR.quad(ox + col * step, oy + row * step, cs, cs,
                    new Color(base.r, base.g, base.b, opacity.get()));
            }
        }
        if (highlightCurrent.get() && here != null) {
            Color hc = textColor.get();
            int hx = ox + gx * step, hy = oy + gz * step;
            Renderer2D.COLOR.quad(hx, hy, cs, 1, hc);
            Renderer2D.COLOR.quad(hx, hy + cs - 1, cs, 1, hc);
            Renderer2D.COLOR.quad(hx, hy, 1, cs, hc);
            Renderer2D.COLOR.quad(hx + cs - 1, hy, 1, cs, hc);
        }
        if (showLegend.get()) {
            int lx = ox + mapPixels + 6;
            for (int i = 0; i < TYPE_NAMES.length; i++) {
                Renderer2D.COLOR.quad(lx, oy + i * (int) (lineH + 2), 6, 6,
                    new Color(TYPE_COLORS[i].r, TYPE_COLORS[i].g, TYPE_COLORS[i].b, 255));
            }
        }
        if (showPlayer.get()) {
            double fx = Math.max(0, Math.min((mc.player.getX() + MAP_OFFSET) / REGION_SIZE, GRID - 0.01));
            double fz = Math.max(0, Math.min((mc.player.getZ() + MAP_OFFSET) / REGION_SIZE, GRID - 0.01));
            int dot = Math.max(3, cs / 2);
            Renderer2D.COLOR.quad(ox + fx * step - dot / 2.0, oy + fz * step - dot / 2.0, dot, dot, playerColor.get());
        }
        Renderer2D.COLOR.render();

        text.beginBig();
        Color tc = textColor.get();
        if (showIds.get()) {
            for (int row = 0; row < GRID; row++) {
                for (int col = 0; col < GRID; col++) {
                    String id = Integer.toString(LAYOUT[row * GRID + col][0]);
                    double w = text.getWidth(id);
                    if (w <= cs - 1) text.render(id, ox + col * step + (cs - w) / 2.0, oy + row * step + 1, tc);
                }
            }
        }
        if (showLegend.get()) {
            int lx = ox + mapPixels + 6;
            for (int i = 0; i < TYPE_NAMES.length; i++)
                text.render(TYPE_NAMES[i], lx + 9, oy + i * (lineH + 2) - 1, tc);
        }
        if (showInfo.get()) {
            String info = here != null
                ? "Region " + here[0] + " - " + TYPE_NAMES[Math.max(0, Math.min(here[1], TYPE_NAMES.length - 1))]
                : "Outside the mapped regions";
            text.render(info, ox, oy + mapPixels + 3, tc);
        }
        text.end();
    }

    @Override
    public String getInfoString() {
        if (mc.player == null) return null;
        int[] here = cellAt(gridIndex(mc.player.getX()), gridIndex(mc.player.getZ()));
        return here == null ? "outside" : Integer.toString(here[0]);
    }
}
