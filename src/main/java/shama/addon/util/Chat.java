package shama.addon.util;

import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.lang.reflect.Method;

/**
 * Chat output that respects Meteor's own "chat feedback" switch.
 *
 * Every module in this addon routes its chat through here, so turning chat feedback off in Meteor's
 * settings silences the whole addon at once — you don't have to hunt down a chat tickbox in each
 * module. The per-module tickboxes still work; this is the master switch above them.
 *
 * Meteor's config field is reached reflectively and cached, so if that class ever moves this simply
 * falls back to printing rather than breaking the build.
 */
public final class Chat {
    private static Method configGet, settingGet;
    private static Object chatFeedbackSetting;
    private static boolean resolved;

    private Chat() {}

    /** False only when Meteor's chat-feedback switch is definitely off. */
    public static boolean feedbackEnabled() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> cfg = Class.forName("meteordevelopment.meteorclient.systems.config.Config");
                configGet = cfg.getMethod("get");
                Object instance = configGet.invoke(null);
                Object setting = instance.getClass().getField("chatFeedback").get(instance);
                chatFeedbackSetting = setting;
                settingGet = setting.getClass().getMethod("get");
            } catch (Throwable ignored) {
                chatFeedbackSetting = null;
            }
        }
        if (chatFeedbackSetting == null || settingGet == null) return true;
        try {
            Object v = settingGet.invoke(chatFeedbackSetting);
            return !(v instanceof Boolean b) || b;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * A copy of everything this addon reports, newest last.
     *
     * Every finder already routes its alerts through here, so keeping a copy gives find-log++ a
     * complete record without touching a single detection module.
     */
    public record Entry(String text, long time) {}

    private static final java.util.ArrayDeque<Entry> RECENT = new java.util.ArrayDeque<>();

    public static java.util.List<Entry> recent() {
        synchronized (RECENT) { return new java.util.ArrayList<>(RECENT); }
    }

    public static void clearRecent() {
        synchronized (RECENT) { RECENT.clear(); }
    }

    private static void remember(String formatted) {
        synchronized (RECENT) {
            RECENT.addLast(new Entry(formatted, System.currentTimeMillis()));
            while (RECENT.size() > 500) RECENT.pollFirst();
        }
    }

    public static void info(String message, Object... args) {
        remember(safeFormat(message, args));
        if (feedbackEnabled()) ChatUtils.info(message, args);
    }

    public static void warning(String message, Object... args) {
        remember(safeFormat(message, args));
        if (feedbackEnabled()) ChatUtils.warning(message, args);
    }

    private static String safeFormat(String message, Object... args) {
        try { return args == null || args.length == 0 ? message : String.format(message, args); }
        catch (Throwable t) { return message; }
    }

    public static void error(String message, Object... args) {
        if (feedbackEnabled()) ChatUtils.error(message, args);
    }
}
