package shama.addon.modules;

import com.mojang.datafixers.util.Pair;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisibility++ — honest about each side:
 *
 *  - Singleplayer / your own LAN world: REAL invisibility. We apply the effect to
 *    the server-side you AND force the invisible flag, so mobs lose you and any
 *    other players in your world can't see your body. With hide-equipment on, we
 *    also blank your armor/held items to those players, so there's nothing left to
 *    see (vanilla invis still shows gear; this removes it).
 *  - Real servers: client-side self-invis only — you render invisible to yourself.
 *    Other players still see you; the server controls what they render and nothing
 *    purely client-side changes that.
 */
public class Invisibility extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> clientSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("client-self")
        .description("Render yourself invisible on your own screen (cosmetic; doesn't affect what others see).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> serverSp = sgGeneral.add(new BoolSetting.Builder()
        .name("true-invisible")
        .description("In your own world, apply real invisibility server-side so mobs and other players in your world can't see you. No effect on real servers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideEquipment = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-equipment")
        .description("Also hide your armor and held items from other players, so invisibility doesn't give you away by showing floating gear. How well this holds up depends on the server.")
        .defaultValue(true)
        .build()
    );

    public Invisibility() {
        super(shama.addon.ShamaAddon.PLAYER, "invisibility++", "Turns you invisible to other players and mobs. How complete the invisibility is depends on the server.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Client-side self view.
        if (clientSelf.get())
            mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 40, 0, false, false));

        // Singleplayer / LAN: real, broadcast to everyone tracking you.
        if (serverSp.get() && mc.getServer() != null) {
            ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if (sp != null) {
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false));
                sp.setInvisible(true);
                if (hideEquipment.get()) sendEquipment(sp, true);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.INVISIBILITY);
        if (mc.getServer() != null && mc.player != null) {
            ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if (sp != null) {
                sp.removeStatusEffect(StatusEffects.INVISIBILITY);
                sp.setInvisible(false);
                sendEquipment(sp, false); // restore real gear to other players
            }
        }
    }

    /** Push an equipment update for sp to every OTHER player in the world — empty
     *  stacks to hide, real stacks to restore. SP/LAN only; wrapped so any API
     *  drift can't crash the module. */
    private void sendEquipment(ServerPlayerEntity sp, boolean blank) {
        try {
            List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values())
                list.add(Pair.of(slot, blank ? ItemStack.EMPTY : sp.getEquippedStack(slot)));
            EntityEquipmentUpdateS2CPacket packet = new EntityEquipmentUpdateS2CPacket(sp.getId(), list);
            for (ServerPlayerEntity other : mc.getServer().getPlayerManager().getPlayerList()) {
                if (other != sp && other.networkHandler != null) other.networkHandler.sendPacket(packet);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public String getInfoString() {
        return mc.getServer() != null ? "real" : "client";
    }
}
