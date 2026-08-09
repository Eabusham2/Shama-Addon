package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Chat Extras++ — merged Spam / MessageAura. spam repeats a message on an interval;
 * message-aura sends a line when a player comes within range (cooldown-gated).
 */
public class ChatExtras extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> spam = sg.add(new BoolSetting.Builder().name("repeat-message").description("Repeat a chat message automatically.").defaultValue(false).build());
    private final Setting<String> spamMsg = sg.add(new StringSetting.Builder().name("spam-message").description("The message to repeat.").defaultValue("shama on top").visible(spam::get).build());
    private final Setting<Integer> spamDelay = sg.add(new IntSetting.Builder().name("spam-delay").description("Ticks between spam messages.").defaultValue(200).min(20).sliderRange(20, 600).visible(spam::get).build());
    private final Setting<Boolean> aura = sg.add(new BoolSetting.Builder().name("message-aura").description("Send a message when a player comes near.").defaultValue(false).build());
    private final Setting<String> auraMsg = sg.add(new StringSetting.Builder().name("aura-message").description("The message the aura sends.").defaultValue("caught in 4k").visible(aura::get).build());
    private final Setting<Double> auraRange = sg.add(new DoubleSetting.Builder().name("aura-range").description("How far the message-aura reaches (blocks).").defaultValue(6).min(1).sliderRange(2, 16).visible(aura::get).build());

    private int timer, auraCd;

    public ChatExtras() { super(shama.addon.ShamaAddon.MISC, "chat-extras++", "Chat spam + proximity message-aura."); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (spam.get() && ++timer >= spamDelay.get()) { timer = 0; mc.getNetworkHandler().sendChatMessage(spamMsg.get()); }
        if (auraCd > 0) auraCd--;
        if (aura.get() && auraCd == 0 && mc.world != null) {
            for (PlayerEntity p : mc.world.getPlayers())
                if (p != mc.player && mc.player.distanceTo(p) <= auraRange.get()) {
                    mc.getNetworkHandler().sendChatMessage(auraMsg.get()); auraCd = 100; break;
                }
        }
    }
}
