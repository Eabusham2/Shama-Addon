package shama.addon.util;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

/** Their ChunkOverlayRenderer helper — shared chunk/box drawing used by the finders. */
public final class ChunkOverlayRenderer {
    public static void drawChunkBox(Render3DEvent event, ChunkPos chunkPos, SettingColor color, double y1, double y2, ShapeMode mode) {
        double x1 = chunkPos.getStartX(), z1 = chunkPos.getStartZ();
        Color side = new Color(color.r, color.g, color.b, color.a / 4);
        event.renderer.box(x1, y1, z1, x1 + 16.0D, y2, z1 + 16.0D, side, color, mode, 0);
    }

    public static void drawBox(Render3DEvent event, Box box, SettingColor color, ShapeMode mode) {
        Color side = new Color(color.r, color.g, color.b, color.a / 4);
        event.renderer.box(box, side, color, mode, 0);
    }
}
