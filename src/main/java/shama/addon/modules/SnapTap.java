package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;

/**
 * Snap Tap — when you hold two opposite movement keys, the one you pressed most recently wins
 * instead of the two cancelling each other out. Makes counter-strafing instant.
 */
public class SnapTap extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> leftRight = sg.add(new BoolSetting.Builder()
        .name("left-right")
        .description("Apply it to strafing (A / D).")
        .defaultValue(true).build());

    private final Setting<Boolean> forwardBack = sg.add(new BoolSetting.Builder()
        .name("forward-back")
        .description("Apply it to forward / back (W / S).")
        .defaultValue(true).build());

    private final Setting<Boolean> releaseRestores = sg.add(new BoolSetting.Builder()
        .name("release-restores")
        .description("When you let go of the newer key while still holding the older one, snap straight back to the older direction instead of stopping.")
        .defaultValue(true).build());

    // which side was pressed most recently: 1 = first key, 2 = second key, 0 = neither
    private int lrNewest, fbNewest;
    private boolean lastLeft, lastRight, lastFwd, lastBack;

    public SnapTap() {
        super(shama.addon.ShamaAddon.MOVEMENT, "snap-tap++",
            "When you hold two opposite movement keys at once, the most recently pressed one wins instead of both cancelling out.");
    }

    @Override
    public void onDeactivate() {
        lrNewest = fbNewest = 0;
        lastLeft = lastRight = lastFwd = lastBack = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (leftRight.get()) {
            KeyBinding l = mc.options.leftKey, r = mc.options.rightKey;
            boolean lp = l.isPressed(), rp = r.isPressed();
            if (lp && !lastLeft) lrNewest = 1;          // just pressed left  -> left is newest
            if (rp && !lastRight) lrNewest = 2;         // just pressed right -> right is newest
            if (!lp && !rp) lrNewest = 0;
            else if (!lp && lrNewest == 1) lrNewest = releaseRestores.get() ? 2 : 0;
            else if (!rp && lrNewest == 2) lrNewest = releaseRestores.get() ? 1 : 0;
            lastLeft = lp; lastRight = rp;
            if (lp && rp) {                              // both held: suppress the older one
                l.setPressed(lrNewest == 1);
                r.setPressed(lrNewest == 2);
            }
        }

        if (forwardBack.get()) {
            KeyBinding f = mc.options.forwardKey, b = mc.options.backKey;
            boolean fp = f.isPressed(), bp = b.isPressed();
            if (fp && !lastFwd) fbNewest = 1;
            if (bp && !lastBack) fbNewest = 2;
            if (!fp && !bp) fbNewest = 0;
            else if (!fp && fbNewest == 1) fbNewest = releaseRestores.get() ? 2 : 0;
            else if (!bp && fbNewest == 2) fbNewest = releaseRestores.get() ? 1 : 0;
            lastFwd = fp; lastBack = bp;
            if (fp && bp) {
                f.setPressed(fbNewest == 1);
                b.setPressed(fbNewest == 2);
            }
        }
    }
}
