package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Swarm — coordinate your own alt accounts. One instance runs as HOST (opens a port and
 * broadcasts its position); the others run as WORKERS (connect to the host IP:port and
 * receive it, e.g. to follow). This is peer coordination between machines YOU control —
 * it connects only to the host you type in, nothing external. Ported from the Swarm concept.
 */
public class Swarm extends Module {
    public enum Mode { Host, Worker }

    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>().name("mode").description("Which mode to use.").defaultValue(Mode.Worker).build());
    private final Setting<String> host = sg.add(new StringSetting.Builder().name("host-ip").description("Worker: host to connect to.").defaultValue("127.0.0.1").visible(() -> mode.get() == Mode.Worker).build());
    private final Setting<Integer> port = sg.add(new IntSetting.Builder().name("port").description("Network port to use.").defaultValue(25566).range(1024, 65535).noSlider().build());

    private volatile boolean running;
    private ServerSocket server;
    private Socket client;
    private final List<PrintWriter> workers = new CopyOnWriteArrayList<>();
    private volatile double[] hostPos; // received by workers

    public Swarm() { super(shama.addon.ShamaAddon.MISC, "swarm++", "Coordinate your own alt accounts (host/worker) over a port you control."); }

    @Override
    public void onActivate() {
        running = true;
        if (mode.get() == Mode.Host) startHost(); else startWorker();
    }

    @Override
    public void onDeactivate() {
        running = false;
        workers.clear();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (client != null) client.close(); } catch (Exception ignored) {}
    }

    private void startHost() {
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(port.get());
                shama.addon.util.Chat.info("[Swarm] hosting on port %d", port.get());
                while (running) {
                    Socket s = server.accept();
                    workers.add(new PrintWriter(s.getOutputStream(), true));
                }
            } catch (Exception e) { if (running) shama.addon.util.Chat.warning("[Swarm] host stopped: %s", e.getMessage()); }
        }, "shama-swarm-host");
        t.setDaemon(true); t.start();
    }

    private void startWorker() {
        Thread t = new Thread(() -> {
            try {
                client = new Socket(host.get(), port.get());
                shama.addon.util.Chat.info("[Swarm] connected to %s:%d", host.get(), port.get());
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while (running && (line = in.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length == 3) hostPos = new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
                }
            } catch (Exception e) { if (running) shama.addon.util.Chat.warning("[Swarm] worker stopped: %s", e.getMessage()); }
        }, "shama-swarm-worker");
        t.setDaemon(true); t.start();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        if (mode.get() == Mode.Host && !workers.isEmpty()) {
            String msg = mc.player.getX() + "," + mc.player.getY() + "," + mc.player.getZ();
            for (PrintWriter w : workers) w.println(msg);
        }
        // Workers expose hostPos via getInfoString; following logic can be layered on top.
    }

    @Override
    public String getInfoString() {
        if (mode.get() == Mode.Host) return workers.size() + " workers";
        double[] h = hostPos;
        return h != null ? String.format("host %.0f %.0f %.0f", h[0], h[1], h[2]) : "connecting";
    }
}
