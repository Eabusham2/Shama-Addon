package shama.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Box;

/** Item Highlight — boxes dropped items on the ground so they're easy to spot. */
public class ItemHighlight extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<Boolean> colorByRarity = sg.add(new BoolSetting.Builder()
        .name("color-by-rarity")
        .description("Colour each item by how rare it is instead of using one flat colour: white common, yellow uncommon, pink rare, purple epic. Enchanted items get the rare colour too.")
        .defaultValue(true).build());
    private final Setting<SettingColor> line = sg.add(new ColorSetting.Builder().name("line-color").description("Colour of the box outline when rarity colouring is off.").defaultValue(new SettingColor(255, 255, 0, 220)).visible(() -> !colorByRarity.get()).build());
    private final Setting<SettingColor> side = sg.add(new ColorSetting.Builder().name("fill-color").description("Colour of the box sides when rarity colouring is off.").defaultValue(new SettingColor(255, 255, 0, 40)).visible(() -> !colorByRarity.get()).build());
    private final Setting<SettingColor> commonColor = sg.add(new ColorSetting.Builder().name("common-color").description("Colour for ordinary items.").defaultValue(new SettingColor(220, 220, 220, 220)).visible(colorByRarity::get).build());
    private final Setting<SettingColor> uncommonColor = sg.add(new ColorSetting.Builder().name("uncommon-color").description("Colour for uncommon items.").defaultValue(new SettingColor(255, 255, 85, 220)).visible(colorByRarity::get).build());
    private final Setting<SettingColor> rareColor = sg.add(new ColorSetting.Builder().name("rare-color").description("Colour for rare and enchanted items.").defaultValue(new SettingColor(255, 105, 180, 220)).visible(colorByRarity::get).build());
    private final Setting<SettingColor> epicColor = sg.add(new ColorSetting.Builder().name("epic-color").description("Colour for epic items.").defaultValue(new SettingColor(180, 80, 255, 220)).visible(colorByRarity::get).build());
    private final Setting<Integer> fillAlpha = sg.add(new IntSetting.Builder().name("fill-alpha").description("How solid the filled sides are when colouring by rarity.").defaultValue(40).min(0).max(255).sliderRange(0, 120).visible(colorByRarity::get).build());

    private final Setting<Boolean> tracers = sg.add(new BoolSetting.Builder().name("tracers").description("Draw a line from you to each highlighted thing.").defaultValue(false).build());
    private final Setting<ShapeMode> shapeMode = sg.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How boxes are drawn: outline only, filled sides only, or both.").defaultValue(ShapeMode.Both).build());

    public ItemHighlight() { super(shama.addon.ShamaAddon.MISC, "item-highlight++", "Highlights dropped items on the ground."); }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        Color l = line.get(), s = side.get();
        var cam = meteordevelopment.meteorclient.utils.render.RenderUtils.center;
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity item)) continue;
            Color il = l, is = s;
            if (colorByRarity.get()) {
                il = rarityColor(item.getStack());
                is = new Color(il.r, il.g, il.b, fillAlpha.get());
            }
            Box b = item.getBoundingBox();
            event.renderer.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, is, il, shapeMode.get(), 0);
            if (tracers.get()) event.renderer.line(cam.x, cam.y, cam.z, item.getX(), item.getY(), item.getZ(), il);
        }
    }

    /** Enchanted counts as rare even when the base item is common. */
    private Color rarityColor(ItemStack stack) {
        try {
            if (stack.hasEnchantments()) return rareColor.get();
            Rarity r = stack.getRarity();
            if (r == Rarity.EPIC) return epicColor.get();
            if (r == Rarity.RARE) return rareColor.get();
            if (r == Rarity.UNCOMMON) return uncommonColor.get();
        } catch (Throwable ignored) {}
        return commonColor.get();
    }
}
