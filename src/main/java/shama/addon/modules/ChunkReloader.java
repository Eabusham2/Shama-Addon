package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/**
 * ChunkReloaderV2 — their module, verbatim logic: runs a 4-command sequence (default
 * delhome -> sethome -> rtp -> home) on a delay to force a chunk reload. It just sends the
 * player's own configurable chat commands; nothing else.
 */
public class ChunkReloader extends Module {
    private enum Phase { IDLE, CMD1, CMD2, CMD3, CMD4, DONE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCommands = settings.createGroup("Commands");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder().name("auto-disable").description("Automatically disable the module after the command sequence completes.").defaultValue(true).build());
    private final Setting<Boolean> clientReload = sgGeneral.add(new BoolSetting.Builder().name("client-reload").description("Also force the client to redraw all chunks locally when the sequence finishes (or immediately, if the command sequence is off).").defaultValue(false).build());
    private final Setting<Boolean> commandSequence = sgGeneral.add(new BoolSetting.Builder().name("command-sequence").description("Run the server command sequence below (delhome -> sethome -> rtp -> home) to force a real chunk reload.").defaultValue(true).build());
    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder().name("chat").description("Show progress messages in chat.").defaultValue(true).build());
    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder().name("loop").description("Continuously loop the command sequence while active.").defaultValue(false).visible(() -> !autoDisable.get()).build());

    private final Setting<String> cmd1 = sgCommands.add(new StringSetting.Builder().name("command-1").description("First command.").defaultValue("delhome 1").build());
    private final Setting<String> cmd2 = sgCommands.add(new StringSetting.Builder().name("command-2").description("Second command.").defaultValue("sethome 1").build());
    private final Setting<String> cmd3 = sgCommands.add(new StringSetting.Builder().name("command-3").description("Third command.").defaultValue("rtp").build());
    private final Setting<String> cmd4 = sgCommands.add(new StringSetting.Builder().name("command-4").description("Fourth command.").defaultValue("home 1").build());

    private final Setting<Integer> delayTicks = sgTiming.add(new IntSetting.Builder().name("delay-ticks").description("Base delay between commands (20 ticks = 1s).").defaultValue(15).range(5, 200).sliderRange(5, 100).build());
    private final Setting<Integer> randomJitter = sgTiming.add(new IntSetting.Builder().name("random-jitter").description("Random extra ticks added to each delay.").defaultValue(5).range(0, 60).sliderRange(0, 40).build());
    private final Setting<Integer> loopCooldown = sgTiming.add(new IntSetting.Builder().name("loop-cooldown").description("Extra delay before restarting when looping.").defaultValue(40).range(10, 600).sliderRange(20, 300).visible(() -> !autoDisable.get() && loop.get()).build());

    private Phase phase = Phase.IDLE;
    private int ticksRemaining;
    private int cycleCount;

    public ChunkReloader() { super(shama.addon.ShamaAddon.PLAYER, "chunk-reloader++", "Runs a command sequence (delhome -> sethome -> rtp -> home) to force a chunk reload."); }

    @Override public void onActivate() {
        if (!commandSequence.get()) { doClientReload(); toggle(); return; }
        phase = Phase.CMD1; ticksRemaining = 0; cycleCount = 0;
        if (chatFeedback.get()) info("§aStarting RTP sequence...");
    }

    private void doClientReload() {
        if (clientReload.get() && mc.worldRenderer != null) {
            mc.worldRenderer.reload();
            if (chatFeedback.get()) info("§7Client chunks redrawn.");
        }
    }
    @Override public void onDeactivate() { phase = Phase.IDLE; ticksRemaining = 0; }

    private int getDelay() { return delayTicks.get() + getJitter(); }
    private int getJitter() { return randomJitter.get() > 0 ? (int) (Math.random() * randomJitter.get()) : 0; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (ticksRemaining > 0) { ticksRemaining--; return; }
        switch (phase) {
            case CMD1 -> { sendCommand(cmd1.get(), 1); phase = Phase.CMD2; ticksRemaining = getDelay(); }
            case CMD2 -> { sendCommand(cmd2.get(), 2); phase = Phase.CMD3; ticksRemaining = getDelay(); }
            case CMD3 -> { sendCommand(cmd3.get(), 3); phase = Phase.CMD4; ticksRemaining = getDelay(); }
            case CMD4 -> { sendCommand(cmd4.get(), 4); cycleCount++; phase = Phase.DONE; ticksRemaining = 0; }
            case DONE -> {
                if (chatFeedback.get()) info("§aRTP sequence complete! §7(cycle #%d)", cycleCount);
                doClientReload();
                if (!autoDisable.get() && loop.get()) {
                    phase = Phase.CMD1; ticksRemaining = loopCooldown.get() + getJitter();
                    if (chatFeedback.get()) info("§7Looping in %.1f seconds...", ticksRemaining / 20.0);
                } else toggle();
            }
            default -> {}
        }
    }

    private void sendCommand(String cmd, int index) {
        if (cmd == null || cmd.isBlank()) return;
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        if (chatFeedback.get()) info("§7[%d/4] §fSending: §e/%s", index, c);
        if (mc.getNetworkHandler() != null) mc.getNetworkHandler().sendChatCommand(c);
    }
}
