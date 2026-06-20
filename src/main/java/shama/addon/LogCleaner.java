package shama.addon;

import shama.addon.log.LogConfig;
import shama.addon.log.ShamaLogFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installs the latest.log filter at Fabric's preLaunch stage — early enough to
 * catch most mod-init logging. Lines logged by the loader before this point
 * (e.g. the very first "Loading N mods" summary) may still slip through.
 */
public class LogCleaner implements PreLaunchEntrypoint {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onPreLaunch() {
        LogConfig cfg = loadOrCreateConfig();

        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig root = config.getRootLogger();

            ShamaLogFilter filter = new ShamaLogFilter(cfg);
            filter.start();
            root.addFilter(filter);

            ctx.updateLoggers();
        } catch (Throwable t) {
            // Never break the launch over log filtering.
            System.err.println("[shama-log] failed to install log filter: " + t);
        }
    }

    private LogConfig loadOrCreateConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("shama-log.json");
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path);
                LogConfig cfg = GSON.fromJson(json, LogConfig.class);
                if (cfg != null) return cfg;
            }
        } catch (Throwable ignored) {
            // fall through to defaults
        }

        LogConfig def = new LogConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(def));
        } catch (Throwable ignored) {
            // If we can't write defaults, still run with in-memory defaults.
        }
        return def;
    }
}
