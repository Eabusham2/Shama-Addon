package shama.addon;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Provides the "Configure" button in Mod Menu for Shama addon. */
public class ShamaModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ShamaConfigScreen::new;
    }
}
