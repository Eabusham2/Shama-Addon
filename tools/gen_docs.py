#!/usr/bin/env python3
"""Generate the module list for the README and the full reference in MODULES.md."""
import re, glob, os, collections, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/hostile-esp"
MODS = os.path.join(ROOT, "src/main/java/shama/addon/modules")

ORDER  = ["HUNT", "COMBAT", "MOVEMENT", "PLAYER", "MISC"]
TITLES = {"HUNT": "Finding bases, stashes & players", "COMBAT": "Combat",
          "MOVEMENT": "Movement", "PLAYER": "Player", "MISC": "Misc"}
BLURB  = {
 "HUNT": "The core of the addon. These look for other people's things — bases, stashes, farms, "
         "hidden players — using chunk data, packets, block patterns and timing.",
 "COMBAT": "Fighting tools. Anything that fabricates movement or timing is marked (risky) and off "
           "by default, because those are what an anti-cheat objects to.",
 "MOVEMENT": "Getting around. Flight, elytra, freecam and movement helpers.",
 "PLAYER": "Things that act on your own character — mining, inventory, interaction.",
 "MISC": "Everything else: rendering, chat, HUD, timing and utilities.",
}

def parse(path):
    s = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r'super\(shama\.addon\.ShamaAddon\.([A-Z]+),\s*"([^"]+)"\s*,\s*"([^"]*)"\)', s)
    if not m: return None
    cat, name, desc = m.groups()

    # settings in declaration order, tagged with the group they belong to
    groups, current = [], "General"
    order = {}
    for gm in re.finditer(r'SettingGroup\s+(\w+)\s*=\s*settings\.(?:createGroup\("([^"]+)"\)|getDefaultGroup\(\))', s):
        order[gm.group(1)] = gm.group(2) or "General"

    settings = []
    for sm in re.finditer(
        r'Setting<([^>]+)>\s+\w+\s*=\s*(sg\w*|settings\.getDefaultGroup\(\))\.add\('
        r'(?:(?!\.build\(\)\);).)*?\.name\("([^"]+)"\)'
        r'((?:(?!\.build\(\)\);).)*?)\.build\(\)\);', s, re.DOTALL):
        typ, grp, nm, rest = sm.groups()
        d = re.search(r'\.description\("((?:[^"\\]|\\.)*)"\)', rest)
        dv = re.search(r'\.defaultValue\(([^)]*)\)', rest)
        gated = ".visible(" in rest
        settings.append({
            "group": order.get(grp, "General"),
            "name": nm,
            "type": typ,
            "desc": (d.group(1).replace('\\"', '"') if d else ""),
            "default": (dv.group(1) if dv else ""),
            "gated": gated,
        })

    modes = []
    for em in re.finditer(r'public enum (\w+)\s*\{([^}]*)\}', s):
        vals = [v.strip().split("(")[0] for v in em.group(2).split(",") if v.strip() and v.strip()[0].isupper()]
        if vals: modes.append((em.group(1), vals))

    return {"cat": cat, "name": name, "desc": desc, "settings": settings, "modes": modes}

mods = collections.defaultdict(list)
for p in sorted(glob.glob(os.path.join(MODS, "*.java"))):
    d = parse(p)
    if d: mods[d["cat"]].append(d)

total = sum(len(v) for v in mods.values())

# ---------------------------------------------------------------- MODULES.md
out = [
 "# Module reference\n",
 f"Every one of the **{total} modules**, what it is for, and what each of its settings does.\n",
 "Settings marked *(hidden until enabled)* only appear once the option above them is switched on, "
 "so the menu stays short until you need the detail. Anything named **(risky)** fabricates "
 "movement, rotation or timing you did not actually perform — those are off by default and can get "
 "you rubber-banded or kicked.\n",
 "---\n",
]
for cat in ORDER:
    if cat not in mods: continue
    out.append(f"## {TITLES[cat]}\n")
    out.append(BLURB[cat] + "\n")
    for m in sorted(mods[cat], key=lambda x: x["name"]):
        out.append(f"### {m['name']}\n")
        out.append(m["desc"] + "\n")
        for en, vals in m["modes"]:
            out.append(f"**Modes** — {', '.join(vals)}\n")
        if not m["settings"]:
            out.append("*No settings.*\n"); continue
        bygroup = collections.OrderedDict()
        for st in m["settings"]:
            bygroup.setdefault(st["group"], []).append(st)
        for gname, items in bygroup.items():
            out.append(f"**{gname}**\n")
            for st in items:
                bits = []
                if st["default"]: bits.append(f"default `{st['default']}`")
                if st["gated"]: bits.append("hidden until enabled")
                tail = f" — *{', '.join(bits)}*" if bits else ""
                out.append(f"- `{st['name']}`{tail}  \n  {st['desc']}")
            out.append("")
        out.append("")
open(os.path.join(ROOT, "MODULES.md"), "w").write("\n".join(out))

# ---------------------------------------------------------------- README tail
names = []
for cat in ORDER:
    for m in sorted(mods.get(cat, []), key=lambda x: x["name"]):
        names.append(m["name"])
tail = ["\n## Every module\n",
        "In one line, for searching:\n",
        ", ".join(names) + "\n",
        "Full explanations of every module and every setting are in **[MODULES.md](MODULES.md)**.\n"]
readme = os.path.join(ROOT, "README.md")
txt = open(readme).read()
txt = re.split(r"\n## Every module\n", txt)[0].rstrip() + "\n" + "\n".join(tail)
open(readme, "w").write(txt)
print(f"MODULES.md: {total} modules, {sum(len(m['settings']) for v in mods.values() for m in v)} settings documented")
