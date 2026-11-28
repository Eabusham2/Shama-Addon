package shama.addon.util;

import meteordevelopment.meteorclient.systems.modules.Modules;
import shama.addon.modules.Bang;

/** Tiny accessor so mixins can reach the Bang module without depending on MeteorClient's internal structure. */
public final class MeteorClientAccessor {
    private MeteorClientAccessor() {}

    public static Bang getBangModule() {
        return Modules.get().get(Bang.class);
    }
}