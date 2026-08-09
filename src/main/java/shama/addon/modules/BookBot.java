package shama.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * BookBot — ported from their BookBot: writes the held writable book. Fills each page
 * with your text and (optionally) signs it with a title, sent via the book update packet.
 * One book per activation.
 */
public class BookBot extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();
    private final Setting<String> pageText = sg.add(new StringSetting.Builder().name("page-text").description("Text to put on the page.").defaultValue("shama").build());
    private final Setting<Integer> pages = sg.add(new IntSetting.Builder().name("pages").description("How many pages to fill.").defaultValue(50).range(1, 100).sliderRange(1, 100).build());
    private final Setting<Boolean> sign = sg.add(new BoolSetting.Builder().name("sign").description("Sign the book with a title.").defaultValue(false).build());
    private final Setting<String> title = sg.add(new StringSetting.Builder().name("title").description("Title of the book.").defaultValue("shama").visible(sign::get).build());

    public BookBot() { super(shama.addon.ShamaAddon.MISC, "book-bot++", "Writes the held writable book with your text."); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (!mc.player.getMainHandStack().isOf(Items.WRITABLE_BOOK)) { shama.addon.util.Chat.warning("[BookBot] hold a writable book."); toggle(); return; }

        List<String> content = new ArrayList<>();
        for (int i = 0; i < pages.get(); i++) content.add(pageText.get());
        int slot = mc.player.getInventory().getSelectedSlot();
        mc.getNetworkHandler().sendPacket(new BookUpdateC2SPacket(slot, content, sign.get() ? Optional.of(title.get()) : Optional.empty()));
        shama.addon.util.Chat.info("[BookBot] wrote %d pages.", pages.get());
        toggle();
    }
}
