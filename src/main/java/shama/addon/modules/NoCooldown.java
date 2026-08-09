package shama.addon.modules;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * NoCooldown — removes the client-side hit/attack cooldown so every swing lands
 * at full charge with no recharge wait, and clears item-use cooldowns (ender
 * pearl, chorus fruit, etc.) so you can use them back-to-back.
 *
 * The actual bypassing is done by two mixins (AttackCooldownMixin,
 * ItemCooldownMixin) which check this module's toggles. Honest scope:
 *  - Singleplayer: full bypass (the integrated server shares the patched code).
 *  - Servers: removes the CLIENT gate so you can swing/use without waiting, but
 *    the server still computes its own cooldown for damage and may reject rapid
 *    item use. Works on lenient servers; a strict anti-cheat won't be fooled.
 */
public class NoCooldown extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> attack = sgGeneral.add(new BoolSetting.Builder()
        .name("attack-cooldown")
        .description("Always swing at full charge — no hit-swing delay between attacks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> items = sgGeneral.add(new BoolSetting.Builder()
        .name("item-cooldown")
        .description("Clear item use cooldowns — ender pearl, chorus fruit, etc. can be used with no delay.")
        .defaultValue(true)
        .build()
    );

    public NoCooldown() {
        super(shama.addon.ShamaAddon.PLAYER, "no-cooldown++", "Removes the attack-swing delay and item cooldowns (like ender pearls) so you can act back-to-back. Strength depends on the server.");
    }
}
