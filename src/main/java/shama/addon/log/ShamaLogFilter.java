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

    public ShamaLogFilter(LogConfig cfg) {
        super(Result.NEUTRAL, Result.NEUTRAL);
        this.cfg = cfg;
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

        // 1b. Drop mod-list dump lines logged after install (resource reload
        //     listing, crash-assistant modlist, any stray loader tree lines).
        if (cfg.hideModList && matchesAny(hay, cfg.modListMarkers)) return Result.DENY;

        // 2. Keep crashes (past force-hide, so not cheat traces): anything with
        //    a stacktrace, plus FATAL. Plain ERROR lines without a stacktrace fall
        //    through to the allow-list, so they show only from allowed sources.
        if (cfg.showCrashes && event.getThrown() != null) return Result.NEUTRAL;
        Level level = event.getLevel();
        if (cfg.showCrashes && level != null && level.isMoreSpecificThan(Level.ERROR)) {
            return Result.NEUTRAL;
        }

        // 3. Allow / deny.
        if ("DENYLIST".equalsIgnoreCase(cfg.mode)) {
            // forceHide already returned above; also drop extra deny terms.
            return matchesAny(hay, cfg.deny) ? Result.DENY : Result.NEUTRAL;
        }
        return matchesAny(hay, cfg.allow) ? Result.NEUTRAL : Result.DENY;
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
