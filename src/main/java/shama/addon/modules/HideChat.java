package shama.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;

/** HideChat — hides the chat feed and history from the screen, but you can still open chat and type. Toggle state read by ChatHudMixin. */
public class HideChat extends Module {
    public HideChat() { super(shama.addon.ShamaAddon.MISC, "hide-chat++", "Hides chat & history but still lets you type."); }
}
