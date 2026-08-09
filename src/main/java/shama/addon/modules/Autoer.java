package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Autoer++ — merged automation with per-feature tickboxes. Consolidates the various
 * auto-* helpers into one module.
 *
 *   auto-tool  : swap to the fastest applicable tool for whatever you're breaking.
 *   auto-mount : right-click the nearest rideable (horse/camel/etc.) to mount it.
 *
 * (More auto-farm features fold in here as additional tickboxes.)
 */
public class Autoer extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> autoTool = sg.add(new BoolSetting.Builder().name("auto-tool").description("Swap to the best tool for the block you're mining.").defaultValue(true).build());
    private final Setting<Boolean> autoMount = sg.add(new BoolSetting.Builder().name("auto-mount").description("Mount the nearest rideable animal you look at.").defaultValue(false).build());
    private final Setting<Boolean> autoWeapon = sg.add(new BoolSetting.Builder().name("auto-weapon").description("Swap to your best sword/axe when attacking a mob.").defaultValue(false).build());
    private final Setting<Boolean> autoShear = sg.add(new BoolSetting.Builder().name("auto-shear").description("Shear nearby wooly sheep (needs shears in hand).").defaultValue(false).build());
    private final Setting<Boolean> autoBreed = sg.add(new BoolSetting.Builder().name("auto-breed").description("Feed nearby breedable animals with the food you're holding.").defaultValue(false).build());
    private final Setting<Boolean> autoFish = sg.add(new BoolSetting.Builder().name("auto-fish").description("Auto-reel and recast when a fish bites.").defaultValue(false).build());
    private final Setting<Boolean> mountBypass = sg.add(new BoolSetting.Builder().name("mount-bypass").description("Force-mount the animal you look at even if you have a vehicle already / server quirks.").defaultValue(false).build());
    private final Setting<Boolean> autoSmelter = sg.add(new BoolSetting.Builder().name("auto-smelter").description("With a furnace open: auto-collect finished output and top up coal fuel.").defaultValue(false).build());
    private final Setting<Boolean> autoBrewer = sg.add(new BoolSetting.Builder().name("auto-brewer").description("With a brewing stand open: auto-collect finished potions and top up blaze powder fuel.").defaultValue(false).build());
    private final Setting<Boolean> autoNametag = sg.add(new BoolSetting.Builder().name("auto-nametag").description("Name-tag nearby un-named animals (needs a name tag in hotbar).").defaultValue(false).build());
    private final Setting<Boolean> autoArmor = sg.add(new BoolSetting.Builder().name("auto-armor").description("Equip the best armor from your inventory into empty armor slots.").defaultValue(false).build());

    private int armorTick;

    public Autoer() {
        super(shama.addon.ShamaAddon.PLAYER, "autoer++", "Auto-tool + auto-mount (and more) in one module.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (autoArmor.get() && armorTick++ % 8 == 0) equipArmor();

        if (autoTool.get() && mc.options.attackKey.isPressed()
            && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockState state = mc.world.getBlockState(hit.getBlockPos());
            int best = mc.player.getInventory().getSelectedSlot();
            double bestSpeed = mc.player.getInventory().getStack(best).getMiningSpeedMultiplier(state);
            for (int i = 0; i < 9; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                double sp = s.getMiningSpeedMultiplier(state);
                if (sp > bestSpeed) { bestSpeed = sp; best = i; }
            }
            if (best != mc.player.getInventory().getSelectedSlot()) InvUtils.swap(best, false);
        }

        if ((autoMount.get() || mountBypass.get()) && mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult eh
            && eh.getEntity() instanceof AbstractHorseEntity && (mountBypass.get() || !mc.player.hasVehicle())) {
            mc.interactionManager.interactEntity(mc.player, eh.getEntity(), Hand.MAIN_HAND);
        }

        if (autoWeapon.get() && mc.options.attackKey.isPressed()
            && mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult) {
            int best = -1, bestScore = -1;
            for (int i = 0; i < 9; i++) {
                var it = mc.player.getInventory().getStack(i).getItem();
                String id = net.minecraft.registry.Registries.ITEM.getId(it).getPath();
                int sc = id.endsWith("_sword") ? 2 : id.endsWith("_axe") ? 1 : 0;
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best >= 0 && bestScore > 0 && best != mc.player.getInventory().getSelectedSlot()) InvUtils.swap(best, false);
        }

        Box near = mc.player.getBoundingBox().expand(4);
        if (autoNametag.get()) {
            int nt = InvUtils.findInHotbar(Items.NAME_TAG).slot();
            if (nt >= 0) for (Entity e : mc.world.getOtherEntities(mc.player, near))
                if (e instanceof AnimalEntity an && !an.hasCustomName()) {
                    int sel = mc.player.getInventory().getSelectedSlot();
                    InvUtils.swap(nt, false);
                    mc.interactionManager.interactEntity(mc.player, e, Hand.MAIN_HAND);
                    InvUtils.swap(sel, false);
                    break;
                }
        }
        if (autoShear.get() && (mc.player.getMainHandStack().isOf(Items.SHEARS) || mc.player.getOffHandStack().isOf(Items.SHEARS))) {
            for (Entity e : mc.world.getOtherEntities(mc.player, near))
                if (e instanceof SheepEntity sh && !sh.isSheared() && !sh.isBaby()) {
                    mc.interactionManager.interactEntity(mc.player, e, mc.player.getMainHandStack().isOf(Items.SHEARS) ? Hand.MAIN_HAND : Hand.OFF_HAND);
                    break;
                }
        }
        if (autoBreed.get() && !mc.player.getMainHandStack().isEmpty()) {
            for (Entity e : mc.world.getOtherEntities(mc.player, near))
                if (e instanceof AnimalEntity an && an.isBreedingItem(mc.player.getMainHandStack()) && an.getBreedingAge() == 0) {
                    mc.interactionManager.interactEntity(mc.player, e, Hand.MAIN_HAND);
                    break;
                }
        }
        if (autoSmelter.get() && mc.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler fh && mc.interactionManager != null) {
            int sync = fh.syncId;
            // collect output (slot 2)
            if (!fh.getSlot(2).getStack().isEmpty())
                mc.interactionManager.clickSlot(sync, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
            // top up fuel (slot 1) with coal from inventory
            if (fh.getSlot(1).getStack().isEmpty()) {
                for (int i = 3; i < fh.slots.size(); i++) {
                    if (fh.getSlot(i).getStack().isOf(Items.COAL) || fh.getSlot(i).getStack().isOf(Items.CHARCOAL)) {
                        mc.interactionManager.clickSlot(sync, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                        break;
                    }
                }
            }
        }
        if (autoBrewer.get() && mc.player.currentScreenHandler instanceof BrewingStandScreenHandler bh && mc.interactionManager != null) {
            int sync = bh.syncId;
            // top up blaze powder fuel (slot 4)
            if (bh.getSlot(4).getStack().isEmpty()) {
                for (int i = 5; i < bh.slots.size(); i++)
                    if (bh.getSlot(i).getStack().isOf(Items.BLAZE_POWDER)) { mc.interactionManager.clickSlot(sync, i, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, mc.player); break; }
            }
        }
        if (autoFish.get() && (mc.player.getMainHandStack().isOf(Items.FISHING_ROD) || mc.player.getOffHandStack().isOf(Items.FISHING_ROD))) {
            FishingBobberEntity bobber = mc.player.fishHook;
            if (bobber != null && bobber.isOnGround() == false && bobber.getVelocity().y < -0.05 && bobber.isTouchingWater()) {
                Hand h = mc.player.getMainHandStack().isOf(Items.FISHING_ROD) ? Hand.MAIN_HAND : Hand.OFF_HAND;
                mc.interactionManager.interactItem(mc.player, h); // reel in
                mc.interactionManager.interactItem(mc.player, h); // recast
            }
        }
    }

    private void equipArmor() {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        String[] kinds = {"helmet", "chestplate", "leggings", "boots"};
        for (int si = 0; si < 4; si++) {
            if (!mc.player.getEquippedStack(slots[si]).isEmpty()) continue;   // slot already filled
            int bestSlot = -1, bestTier = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack st = mc.player.getInventory().getStack(i);
                if (st.isEmpty()) continue;
                String pth = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath();
                boolean match = pth.endsWith(kinds[si]) || (si == 0 && pth.equals("turtle_helmet"));
                if (!match) continue;
                int tier = armorTier(pth);
                if (tier > bestTier) { bestTier = tier; bestSlot = i; }
            }
            if (bestSlot >= 0) {
                int screen = bestSlot < 9 ? bestSlot + 36 : bestSlot; // inv index -> player screen slot
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screen, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }
    }

    private int armorTier(String p) {
        if (p.contains("netherite")) return 5;
        if (p.contains("diamond")) return 4;
        if (p.contains("iron")) return 3;
        if (p.contains("chainmail")) return 2;
        if (p.contains("golden")) return 1;
        return 0; // leather / turtle
    }

    @Override
    public String getInfoString() {
        return autoTool.get() ? "tool" : autoMount.get() ? "mount" : "off";
    }
}
