package shama.addon.log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain config object read/written as JSON at config/shama-log.json. */
public class LogConfig {
    public boolean enabled = true;

    /** "ALLOWLIST" = show only allow matches (+ crashes), hide everything else.
     *  "DENYLIST"  = hide only forceHide/deny matches, show everything else.
     *  Default DENYLIST: force-hide is the hard gate; everything else stays,
     *  so the log reads naturally and only cheat traces are removed. */
    public String mode = "DENYLIST";

    /** Show crash/stacktrace lines UNLESS they match forceHide. */
    public boolean showCrashes = true;

    /** Treat patterns as regex instead of plain case-insensitive substrings. */
    public boolean useRegex = false;

    /** Drop loader/runtime mod-list dumps logged AFTER the filter installs. */
    public boolean hideModList = true;

    public List<String> modListMarkers = new ArrayList<>(Arrays.asList(
        "reloading resourcemanager", "modlist", "mod(s) metadata", "|--", "\\--"
    ));

    /** BLACKLIST — highest priority, ALWAYS dropped (beats allow + crashes).
     *  This is the backstop: if it matches here, it never appears, full stop. */
    public List<String> forceHide = new ArrayList<>(Arrays.asList(
        // Meteor + ecosystem
        "meteor", "meteordevelopment", "meteorclient", "meteorist", "meteorextras",
        "meteor-rejects", "anticope", "rejects", "starscript", "orbit",
        // Pathing / nav
        "baritone", "nether-pathfinder", "nether_pathfinder", "nether pathfinder",
        // Seed / coord exploiting
        "seedcracker", "seedcrackerx",
        // Known cheat clients / addons
        "numbyhack", "numby hack", "numby", "higtools", "wurst", "impact",
        "aristois", "inertia", "rusherhack", "future",
        // Printers / dupe / automation
        "nerv-printer", "super-printer", "litematica-printer", "printer",
        // This addon
        "shama", "hostile-esp",
        // Meteor addon packs in this setup
        "glazed", "ikea", "kawaii", "genyo", "asteroide", "threatengl",
        "marlowcrystal", "kindscrystal", "g1axcrystal", "crystaloptimizer",
        "gpu_booster", "gpubooster", "anvianslib", "toadlib", "trouser",
        "streak-addon", "streak"
    ));

    /** ALLOWLIST — only consulted in ALLOWLIST mode. Generous: your real modset
     *  plus the common warnings/noise from normal startup so the log stays natural. */
    public List<String> allow = new ArrayList<>(Arrays.asList(
        // Game + loader + core
        "minecraft", "mojang", "datafixer", "fabric", "fabricloader", "knot",
        "mixin", "mixinextras", "lwjgl", "openal", "glfw", "joml", "netty",
        "brigadier", "authlib", "java", "oshi", "jna", "intermediary",
        // Performance
        "sodium", "iris", "lithium", "krypton", "ferritecore", "c2me", "ishland",
        "moreculling", "badoptimizations", "immediatelyfast", "threadtweak",
        "dynamic_fps", "dynamic-fps", "entityculling", "distanthorizons",
        "distant horizons", "spark", "cull",
        // Config libs
        "cloth", "yacl", "yet_another_config", "fzzy_config", "libjf", "owo",
        "forgeconfigapiport", "night-config", "supermartijn642", "konkrete",
        "permissions",
        // Libraries
        "geckolib", "architectury", "balm", "kuma_api", "creativecore",
        "puzzleslib", "searchables", "kirin", "transition", "trender",
        "conditional-mixin", "malilib", "masa", "litematica",
        // QoL
        "jei", "jade", "appleskin", "durabilitytooltip", "shulkerboxtooltip",
        "shulker", "inventoryessentials", "mousetweaks", "mouse tweaks",
        "controlling", "controlify", "sdl", "steamdeck",
        // Audio + visual
        "voicechat", "simple voice chat", "ambientsounds", "presencefootsteps",
        "sound_physics", "sound physics", "lambdyn", "particlerain",
        "fallingleaves", "visuality", "make_bubbles", "capes", "waveycapes",
        "skinlayers3d", "firstperson", "notenoughanimations", "dynamiccrosshair",
        "bettermounthud", "betterpingdisplay", "continuity",
        "entity_model_features", "entity_texture_features", "zoomify",
        // Other legit
        "essential", "modmenu", "pingwheel", "ping-wheel", "nochatreports",
        "opsec", "replaymod", "crash_assistant", "crashassistant",
        "notenoughcrashes", "debugify", "yosbr", "defaultoptions", "rrls",
        "packetfixer", "placeholder", "kotlin", "libgui", "libbamboo",
        "spruceui", "pride", "snakeyaml", "reflect",
        // Other PC modset (Fabulously Optimized family + QoL/cosmetic)
        "modernfix", "starlight", "noisium", "memoryleakfix", "memory leak",
        "fastquit", "lazydfu", "cullleaves", "cull leaves", "cull less leaves",
        "emi", "emiloot", "indium", "enhancedblockentities", "enhanced block entities",
        "alternatecurrent", "alternate current", "exordium", "servercore",
        "bobby", "fabricskyboxes", "skyboxes", "betterclouds", "better clouds",
        "betteradvancements", "better advancements", "chatheads", "chat heads",
        "statuseffectbars", "status effect bars", "betterf3", "durabilityviewer",
        "durability viewer", "tooltipfix", "languagereload", "language reload",
        "screenshot", "advancementplaques", "advancement plaques",
        "enchantmentdescriptions", "enchantment descriptions", "boatiteminview",
        "boat item view", "eatinganimation", "eating animation", "citresewn",
        "cit resewn", "fabulously", "optimized",
        // Engine / resource lines
        "resourcepack", "resource pack", "texture", "atlas", "shader",
        "pipeline", "config", "recipe", "advancement", "unifont", "font",
        "hardware", "opengl", "backend", "stdout", "reloading", "registering",
        // Common warnings / noise that should stay
        "reference map", "could not be read", "error loading class",
        "method overwrite", "should be static", "force disabled",
        "force-disabling", "@mixin target", "program match", "supported_formats",
        "pack metadata", "unable to read property", "blockstate",
        "update available", "datafixer optimizations", "quick reload listener",
        "created:", "loaded shader"
    ));

    /** Extra denylist terms (DENYLIST mode adds these to forceHide behavior). */
    public List<String> deny = new ArrayList<>(Arrays.asList(
        "xray", "x-ray", "killaura", "aimbot", "autototem", "autocrystal"
    ));
}
