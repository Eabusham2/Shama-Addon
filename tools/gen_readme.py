#!/usr/bin/env python3
import re, glob, collections, sys, os
root = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/hostile-esp"
mods = collections.defaultdict(list)
for p in sorted(glob.glob(os.path.join(root, "src/main/java/shama/addon/modules/*.java"))):
    s = open(p).read()
    m = re.search(r'super\(shama\.addon\.ShamaAddon\.([A-Z]+),\s*"([^"]+)"\s*,\s*"([^"]*)"\)', s)
    if not m: continue
    cat, name, desc = m.groups()
    mods[cat].append((name, desc))
order = ["HUNT", "COMBAT", "MOVEMENT", "PLAYER", "MISC"]
titles = {"HUNT": "Finding bases, stashes & players", "COMBAT": "Combat",
          "MOVEMENT": "Movement", "PLAYER": "Player", "MISC": "Misc"}
total = sum(len(v) for v in mods.values())
o = ["# Shama Addon\n",
     "[![Build](https://github.com/Eabusham2/Shama-Addon/actions/workflows/build.yml/badge.svg)]"
     "(https://github.com/Eabusham2/Shama-Addon/actions/workflows/build.yml)\n",
     "A Meteor Client addon for Minecraft 1.21.11.\n",
     "## Download\n",
     "**[Get the latest build](https://github.com/Eabusham2/Shama-Addon/releases/latest)** — rebuilt "
     "automatically every time the code changes, so this link is always the newest version.\n",
     "Drop the `.jar` into your `mods` folder next to Meteor Client.\n",
     f"It adds **{total} modules**, built mainly around finding bases, stashes and players, "
     "with a full set of combat, movement and utility modules alongside them.\n",
     "## Installing\n",
     "1. Install [Fabric](https://fabricmc.net/) for Minecraft 1.21.11 and [Meteor Client](https://meteorclient.com/).\n"
     "2. Drop the addon `.jar` into your `mods` folder next to Meteor.\n"
     "3. Launch the game — the modules appear in the Meteor menu under their categories.\n",
     "## Building it yourself\n", "```bash\n./gradlew build\n```\n",
     "The finished `.jar` ends up in `build/libs/`. You'll need Java 21.\n",
     "## Modules\n",
     "Every module has settings, and every setting has a short explanation when you hover over it in-game.\n"]
for cat in order:
    if cat not in mods: continue
    o.append(f"### {titles[cat]}\n")
    for name, desc in sorted(mods[cat]): o.append(f"- **{name}** — {desc}")
    o.append("")
open(os.path.join(root, "README.md"), "w").write("\n".join(o))
print(f"README regenerated: {total} modules")
