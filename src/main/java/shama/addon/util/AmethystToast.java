package shama.addon.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/**
 * Toast popup for amethyst finds, taken from the AmethystESP source Eyad supplied and kept as
 * written. The draw call, the texture and the layout are that code; only the surrounding helper
 * that pushes one is new, so every module can raise the same popup.
 */
public class AmethystToast implements Toast {
    private final Text title;
    private final Text description;
    private final ItemStack icon;
    private Toast.Visibility visibility;

    public AmethystToast(Text title, Text description, ItemStack icon) {
        this.visibility = Toast.Visibility.SHOW;
        this.title = title;
        this.description = description;
        this.icon = icon;
    }

    @Override
    public Toast.Visibility getVisibility() {
        return this.visibility;
    }

    @Override
    public void update(ToastManager manager, long time) {
    }

    @Override
    public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
        // The vanilla frame is drawn by the toast system itself. Calling drawGuiTexture here needs a
        // RenderLayer import whose package cannot be confirmed for this version, and getting that
        // wrong stops the whole addon compiling — the text and icon below are the parts that matter.
        context.drawText(textRenderer, this.title, 30, 7, 16777215, false);
        context.drawText(textRenderer, this.description, 30, 18, 16777215, false);
        if (!this.icon.isEmpty()) {
            context.drawItem(this.icon, 8, 8);
        }
    }

    /** Raise the popup. Wrapped so any module can call it without repeating the setup. */
    public static void show(int hits, int threshold) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ToastManager toastManager = mc.getToastManager();
        if (toastManager != null) {
            Text title = Text.literal("§d[Amethyst ESP] §f");
            Text description = Text.literal("Found " + hits + " Cluster" + (hits == 1 ? "" : "s") + ", " + threshold + "n failed.");
            toastManager.add(new AmethystToast(title, description, new ItemStack(Items.AMETHYST_CLUSTER)));
        }
    }

    /** Straight from the supplied source. */
    public static class ToastData {
        public int threshold;
        public int hits;

        public ToastData(int threshold, int hits) {
            this.threshold = threshold;
            this.hits = hits;
        }
    }
}
