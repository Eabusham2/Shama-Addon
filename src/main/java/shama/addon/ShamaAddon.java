package shama.addon;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;

import shama.addon.modules.HostileEsp;
import shama.addon.modules.LightEsp;
import shama.addon.modules.PingSpoofer;
import shama.addon.modules.YLevelSpoofer;

public class ShamaAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    // Custom category so all Shama modules group together in Meteor's GUI.
    public static final Category CATEGORY = new Category("Shama", Items.NETHER_STAR.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Initializing Shama addon");

        Modules.get().add(new HostileEsp());
        Modules.get().add(new LightEsp());
        Modules.get().add(new YLevelSpoofer());
        Modules.get().add(new PingSpoofer());
    }

    @Override
    public String getPackage() {
        return "shama.addon";
    }
}
