package shama.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;

/**
 * Godmode — does what's actually possible on each side, no fake promises.
 *
 *  - Singleplayer (your own world): TRUE invincibility. The integrated server
 *    runs on your machine, so we top the server-side player's health back to max
 *    every tick. You can't die.
 *
 *  - Servers: damage is decided by the server, so real invincibility is
 *    impossible client-side. What we CAN do, and what actually works:
 *      * No fall damage — spoof an "on ground" packet while falling so the
 *        server never builds up fall distance.
 *      * Anti-knockback — drop the velocity packets the server sends to shove
 *        you, so hits don't move you.
 *    You're NOT immune to mob/player hits on a server; nothing client-side can
 *    do that. These just remove the two most common avoidable deaths.
 */
public class Godmode extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> spHeal = sgGeneral.add(new BoolSetting.Builder()
        .name("full-invincible")
        .description("Keeps your health topped up so you stay alive. How much applies depends on the server.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noFall = sgGeneral.add(new BoolSetting.Builder()
        .name("no-fall-damage")
        .description("Spoof on-ground packets while falling so the server never applies fall damage. Works on most servers and singleplayer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiKnockback = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-knockback")
        .description("Cancel the velocity packets the server sends to knock you back. You won't get shoved by hits or explosions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> instantKill = sgGeneral.add(new BoolSetting.Builder()
        .name("instant-kill")
        .description("While held, instantly kills the entity you're looking at. Whether it works depends on the server. Off by default so you don't wipe everything the moment godmode turns on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> infiniteHunger = sgGeneral.add(new BoolSetting.Builder()
        .name("infinite-hunger")
        .description("Keep food and saturation topped up so the hunger bar never drops and sprint never cuts out. In singleplayer this also stops starvation entirely; on servers the bar may still flicker since the server tracks real hunger, but it keeps it pinned full client-side.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> regen = sgGeneral.add(new BoolSetting.Builder()
        .name("regen")
        .description("Continuously heals you back to full (works alongside or instead of full invincibility). How much applies depends on the server.")
        .defaultValue(true)
        .build()
    );

    public Godmode() {
        super(shama.addon.ShamaAddon.COMBAT, "godmode++", "Makes you effectively unkillable — negates fall damage, knockback and other harm. How much applies depends on the server.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // --- Singleplayer true godmode: heal the server-side player ---
        if (spHeal.get() && mc.getServer() != null) {
            ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if (sp != null) {
                if (sp.getHealth() < sp.getMaxHealth()) sp.setHealth(sp.getMaxHealth());
                sp.getHungerManager().setFoodLevel(20);
                sp.setFireTicks(0);
                sp.fallDistance = 0;
            }
        }

        // --- Regen (singleplayer): heal back to full even if invincibility is off ---
        if (regen.get() && mc.getServer() != null) {
            ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
            if (sp != null && sp.getHealth() < sp.getMaxHealth()) {
                sp.setHealth(sp.getMaxHealth());
            }
        }

        // --- Infinite hunger / saturation: keep the bar pinned full ---
        if (infiniteHunger.get()) {
            // Client side: stops the bar dropping and keeps sprint available.
            mc.player.getHungerManager().setFoodLevel(20);
            mc.player.getHungerManager().setSaturationLevel(20f);
            // Singleplayer: pin the server player too so it truly never depletes.
            if (mc.getServer() != null) {
                ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
                if (sp != null) {
                    sp.getHungerManager().setFoodLevel(20);
                    sp.getHungerManager().setSaturationLevel(20f);
                }
            }
        }

        // --- No fall damage (works SP + servers): tell the server we're grounded ---
        if (noFall.get() && mc.getNetworkHandler() != null && !mc.player.isOnGround()) {
            // Only bother when actually descending, to avoid spamming.
            if (mc.player.getVelocity().y < -0.2) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                    mc.player.getX(),
                    mc.player.getY(),
                    mc.player.getZ(),
                    mc.player.getYaw(),
                    mc.player.getPitch(),
                    true,                              // onGround = true -> resets server fall distance
                    mc.player.horizontalCollision
                ));
                mc.player.fallDistance = 0;
            }
        }

        // --- Instant kill (singleplayer only): kill what you're looking at ---
        if (instantKill.get() && mc.getServer() != null && mc.options.attackKey.isPressed()) {
            if (mc.crosshairTarget instanceof EntityHitResult hit) {
                Entity clientTarget = hit.getEntity();
                if (clientTarget != null && clientTarget != mc.player) {
                    ServerWorld sw = mc.getServer().getWorld(mc.player.getEntityWorld().getRegistryKey());
                    if (sw != null) {
                        Entity serverTarget = sw.getEntityById(clientTarget.getId());
                        if (serverTarget instanceof LivingEntity living) {
                            living.setHealth(0f); // clean death + drops
                        } else if (serverTarget != null) {
                            serverTarget.discard();
                        }
                    }
                }
            }
        }
    }

    // --- Anti-knockback: drop server velocity updates aimed at us ---
    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!antiKnockback.get()) return;
        if (mc.player == null) return;

        if (event.packet instanceof EntityVelocityUpdateS2CPacket vel) {
            if (vel.getEntityId() == mc.player.getId()) {
                event.cancel();
            }
        }
    }

    @Override
    public String getInfoString() {
        if (mc.getServer() != null && spHeal.get()) return "invincible";
        return "fall/kb";
    }
}
