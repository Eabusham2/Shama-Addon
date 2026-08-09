package shama.addon.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Root-logger filter. DENY drops a line from latest.log (and console), NEUTRAL
 * lets it pass. Decision order:
 *   1. forceHide  -> always DENY (beats crashes and the allow list)
 *   2. crashes    -> SHOW if showCrashes (and not force-hidden)
 *   3. allow/deny -> per mode
 */
public class ShamaLogFilter extends AbstractFilter {
    private final LogConfig cfg;
    private java.io.BufferedWriter cleanLog;

    public ShamaLogFilter(LogConfig cfg) {
        super(Result.NEUTRAL, Result.NEUTRAL);
        this.cfg = cfg;
        if (cfg.writeCleanLog) openCleanLog();
    }

    private void openCleanLog() {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir().resolve("logs");
            java.nio.file.Files.createDirectories(dir);
            // Fresh file each launch.
            cleanLog = java.nio.file.Files.newBufferedWriter(dir.resolve("shama-clean.log"));
        } catch (Exception ignored) {
            cleanLog = null;
        }
    }

    private synchronized void keep(LogEvent event, String msg) {
        // Mirror a kept line into the clean log, best-effort. Synchronized because
        // log4j calls the filter from many threads (render/server/netty/workers),
        // and a shared BufferedWriter isn't thread-safe — without this, lines could
        // interleave or throw.
        if (cleanLog == null) return;
        try {
            Level lvl = event.getLevel();
            cleanLog.write("[" + (lvl == null ? "INFO" : lvl) + "] " + msg);
            cleanLog.newLine();
            cleanLog.flush();
        } catch (Exception ignored) {}
    }

    @Override
    public Result filter(LogEvent event) {
        if (!cfg.enabled) return Result.NEUTRAL;

        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        String thread = event.getThreadName() == null ? "" : event.getThreadName();
        String msg = (event.getMessage() != null) ? event.getMessage().getFormattedMessage() : "";

        // Include the throwable's class/message so cheat stacktraces are matchable.
        String thrown = "";
        if (event.getThrown() != null) {
            Throwable t = event.getThrown();
            thrown = String.valueOf(t.getClass().getName()) + " " + String.valueOf(t.getMessage());
        }

        String hay = (logger + " " + thread + " " + msg + " " + thrown).toLowerCase(Locale.ROOT);

        // 1. Force-hide wins over everything.
        if (matchesAny(hay, cfg.forceHide)) return Result.DENY;

        // 1b. Drop mod-list dump lines logged after install.
        if (cfg.hideModList && matchesAny(hay, cfg.modListMarkers)) return Result.DENY;

        // 1c. Drop harmless render/auth spam from other mods (Essential NPE, etc).
        //     This beats showCrashes so the repeating NPE stacktrace is silenced.
        if (cfg.hideRenderSpam && matchesAny(hay, cfg.renderSpam)) return Result.DENY;

        // 2. Keep crashes (past force-hide, so not cheat traces).
        if (cfg.showCrashes && event.getThrown() != null) { keep(event, msg); return Result.NEUTRAL; }
        Level level = event.getLevel();
        if (cfg.showCrashes && level != null && level.isMoreSpecificThan(Level.ERROR)) {
            keep(event, msg);
            return Result.NEUTRAL;
        }

        // 3. Allow / deny.
        if ("DENYLIST".equalsIgnoreCase(cfg.mode)) {
            if (matchesAny(hay, cfg.deny)) return Result.DENY;
            keep(event, msg);
            return Result.NEUTRAL;
        }
        if (matchesAny(hay, cfg.allow)) { keep(event, msg); return Result.NEUTRAL; }
        return Result.DENY;
    }

    private boolean matchesAny(String hay, List<String> patterns) {
        if (patterns == null) return false;
        for (String p : patterns) {
            if (p == null || p.isEmpty()) continue;
            if (cfg.useRegex) {
                try {
                    if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(hay).find()) return true;
                } catch (PatternSyntaxException ignored) {
                    if (hay.contains(p.toLowerCase(Locale.ROOT))) return true;
                }
            } else if (hay.contains(p.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
