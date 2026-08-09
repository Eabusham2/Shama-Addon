package shama.addon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import shama.addon.log.LogConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod Menu config screen for the log filter. Lets you toggle the filter options
 * from the GUI instead of editing config/shama-log.json by hand.
 *
 * Note: the filter runs at launch (preLaunch), so changes here are saved to the
 * JSON and take effect on the NEXT launch, not the current session. The screen
 * says so, to avoid confusion.
 */
public class ShamaConfigScreen extends Screen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Screen parent;
    private LogConfig config;

    public ShamaConfigScreen(Screen parent) {
        super(Text.literal("Shama Addon — Log Filter"));
        this.parent = parent;
        this.config = load();
    }

    private Path configPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("shama-log.json");
    }

    private LogConfig load() {
        try (Reader r = Files.newBufferedReader(configPath())) {
            LogConfig c = GSON.fromJson(r, LogConfig.class);
            if (c != null) return c;
        } catch (Exception ignored) {}
        return new LogConfig();
    }

    private void save() {
        try (Writer w = Files.newBufferedWriter(configPath())) {
            GSON.toJson(config, w);
        } catch (IOException ignored) {}
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 50;

        addDrawableChild(toggle(cx - 150, y, "Filter enabled", config.enabled,
            v -> config.enabled = v));
        y += 28;

        addDrawableChild(ButtonWidget.builder(
            Text.literal("Mode: " + config.mode),
            b -> {
                config.mode = config.mode.equalsIgnoreCase("DENYLIST") ? "ALLOWLIST" : "DENYLIST";
                b.setMessage(Text.literal("Mode: " + config.mode));
            }
        ).dimensions(cx - 150, y, 300, 20).build());
        y += 28;

        addDrawableChild(toggle(cx - 150, y, "Show crashes", config.showCrashes,
            v -> config.showCrashes = v));
        y += 28;

        addDrawableChild(toggle(cx - 150, y, "Hide mod list", config.hideModList,
            v -> config.hideModList = v));
        y += 28;

        addDrawableChild(toggle(cx - 150, y, "Hide render spam", config.hideRenderSpam,
            v -> config.hideRenderSpam = v));
        y += 28;

        addDrawableChild(toggle(cx - 150, y, "Write clean log", config.writeCleanLog,
            v -> config.writeCleanLog = v));
        y += 40;

        addDrawableChild(ButtonWidget.builder(
            Text.literal("Save"),
            b -> { save(); if (client != null) client.setScreen(parent); }
        ).dimensions(cx - 150, y, 145, 20).build());

        addDrawableChild(ButtonWidget.builder(
            Text.literal("Cancel"),
            b -> { if (client != null) client.setScreen(parent); }
        ).dimensions(cx + 5, y, 145, 20).build());
    }

    private ButtonWidget toggle(int x, int y, String label, boolean initial, java.util.function.Consumer<Boolean> setter) {
        final boolean[] state = { initial };
        return ButtonWidget.builder(
            Text.literal(label + ": " + (state[0] ? "ON" : "OFF")),
            b -> {
                state[0] = !state[0];
                setter.accept(state[0]);
                b.setMessage(Text.literal(label + ": " + (state[0] ? "ON" : "OFF")));
            }
        ).dimensions(x, y, 300, 20).build();
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Changes apply on next launch.").formatted(Formatting.GRAY),
            width / 2, 28, 0xAAAAAA);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
