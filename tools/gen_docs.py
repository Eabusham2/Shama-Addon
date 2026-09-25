#!/usr/bin/env python3
"""
Generate the module reference in MODULES.md and refresh the one-line module list in the README.

Run it from anywhere:  python3 tools/gen_docs.py            (uses this repository)
                       python3 tools/gen_docs.py /other/root

Comments are stripped before anything is parsed, so a javadoc on an enum constant or inside a
setting builder cannot hide a mode or a default from the reference. Defaults are read with their
brackets balanced, so a list that spans several lines comes out as the items it holds rather than
as a slice of Java source.
"""
import re, glob, os, collections, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
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
 "PLAYER": "Things that act on your own character — mining, building, inventory, interaction.",
 "MISC": "Everything else: rendering, chat, HUD, timing and utilities.",
}


def strip_comments(text):
    """Drop // and /* */ comments but leave string literals alone."""
    out, i, n, quote = [], 0, len(text), None
    while i < n:
        c = text[i]
        if quote is None:
            if text.startswith("//", i):
                j = text.find("\n", i)
                i = n if j < 0 else j
                continue
            if text.startswith("/*", i):
                j = text.find("*/", i + 2)
                i = n if j < 0 else j + 2
                continue
            if c in "\"'":
                quote = c
            out.append(c)
            i += 1
        else:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
    return "".join(out)


def balanced(text, start):
    """text[start] is an opening bracket; return everything up to its matching close."""
    depth, i, quote = 0, start, None
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return text[start + 1:]


def split_args(text):
    """Split on the commas that sit outside quotes and brackets."""
    parts, depth, quote, cur = [], 0, None, []
    for c in text:
        if quote:
            cur.append(c)
            if c == quote:
                quote = None
            continue
        if c in "\"'":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    if cur:
        parts.append("".join(cur))
    return parts


def pretty_item(item):
    """Items.TRAPPED_CHEST -> trapped_chest; a string literal stays as it is."""
    item = item.strip()
    if item.startswith('"'):
        return item
    return item.rsplit(".", 1)[-1].lower()


def tidy_default(raw):
    """Turn a Java default value into something a reader can use."""
    v = " ".join(raw.split())
    m = re.fullmatch(r"List\.of\((.*)\)", v, re.S)
    if m:
        items = [x for x in split_args(m.group(1)) if x.strip()]
        return ", ".join(pretty_item(x) for x in items) if items else "empty"
    m = re.fullmatch(r"new SettingColor\((.*)\)", v)
    if m:
        return "rgba(" + ", ".join(x.strip() for x in m.group(1).split(",")) + ")"
    return v


def parse(path):
    s = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    m = re.search(r'super\(shama\.addon\.ShamaAddon\.([A-Z]+),\s*"([^"]+)"\s*,\s*"([^"]*)"\)', s)
    if not m:
        return None
    cat, name, desc = m.groups()

    # settings in declaration order, tagged with the group they belong to
    order = {}
    for gm in re.finditer(r'SettingGroup\s+(\w+)\s*=\s*settings\.(?:createGroup\("([^"]+)"\)|getDefaultGroup\(\))', s):
        order[gm.group(1)] = gm.group(2) or "General"

    settings = []
    for sm in re.finditer(
        r'Setting<((?:[^<>]|<[^<>]*>)+)>\s+\w+\s*=\s*(sg\w*|settings\.getDefaultGroup\(\))\.add\('
        r'(?:(?!\.build\(\)\);).)*?\.name\("([^"]+)"\)'
        r'((?:(?!\.build\(\)\);).)*?)\.build\(\)\);', s, re.DOTALL):
        typ, grp, nm, rest = sm.groups()
        d = re.search(r'\.description\("((?:[^"\\]|\\.)*)"\)', rest)
        dv = rest.find(".defaultValue(")
        default = tidy_default(balanced(rest, dv + len(".defaultValue"))) if dv >= 0 else ""
        settings.append({
            "group": order.get(grp, "General"),
            "name": nm,
            "type": typ,
            "desc": (d.group(1).replace('\\"', '"') if d else ""),
            "default": default,
            "gated": ".visible(" in rest,
        })

    modes = []
    for em in re.finditer(r'public enum (\w+)\s*\{([^}]*)\}', s):
        vals = [v.strip().split("(")[0] for v in em.group(2).split(",") if v.strip() and v.strip()[0].isupper()]
        if vals:
            modes.append((em.group(1), vals))

    return {"cat": cat, "name": name, "desc": desc, "settings": settings, "modes": modes}


mods = collections.defaultdict(list)
for p in sorted(glob.glob(os.path.join(MODS, "*.java"))):
    d = parse(p)
    if d:
        mods[d["cat"]].append(d)

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
    if cat not in mods:
        continue
    out.append(f"## {TITLES[cat]}\n")
    out.append(BLURB[cat] + "\n")
    for m in sorted(mods[cat], key=lambda x: x["name"]):
        out.append(f"### {m['name']}\n")
        out.append(m["desc"] + "\n")
        for en, vals in m["modes"]:
            out.append(f"**Modes** — {', '.join(vals)}\n")
        if not m["settings"]:
            out.append("*No settings.*\n")
            continue
        bygroup = collections.OrderedDict()
        for st in m["settings"]:
            bygroup.setdefault(st["group"], []).append(st)
        for gname, items in bygroup.items():
            out.append(f"**{gname}**\n")
            for st in items:
                bits = []
                if st["default"]:
                    bits.append(f"default `{st['default']}`")
                if st["gated"]:
                    bits.append("hidden until enabled")
                tail = f" — *{', '.join(bits)}*" if bits else ""
                out.append(f"- `{st['name']}`{tail}  \n  {st['desc']}")
            out.append("")
        out.append("")
open(os.path.join(ROOT, "MODULES.md"), "w", encoding="utf-8").write("\n".join(out))

# ---------------------------------------------------------------- README one-liner
# Only the backticked line under "## Every module…" is replaced. The counts, the category table and
# the per-category lists above it are laid out by hand and left alone.
names = []
for cat in ORDER:
    for m in sorted(mods.get(cat, []), key=lambda x: x["name"]):
        names.append(m["name"])
line = "`" + ", ".join(names) + "`"

readme_path = os.path.join(ROOT, "README.md")
readme = open(readme_path, encoding="utf-8").read()
head = re.search(r"^## Every module[^\n]*\n", readme, re.M)
if head:
    start = head.end()
    nxt = re.search(r"^(## |---)", readme[start:], re.M)
    end = start + (nxt.start() if nxt else len(readme) - start)
    section = readme[start:end]
    if re.search(r"^`.*`[ \t]*$", section, re.M):
        section = re.sub(r"^`.*`[ \t]*$", lambda _: line, section, count=1, flags=re.M)
    else:
        section = section.rstrip("\n") + "\n\n" + line + "\n\n"
    readme = readme[:start] + section + readme[end:]
else:
    readme = readme.rstrip("\n") + "\n\n---\n\n## Every module, one line\n\nFor searching the page with a single find:\n\n" + line + "\n"
open(readme_path, "w", encoding="utf-8").write(readme)

print(f"MODULES.md: {total} modules, {sum(len(m['settings']) for v in mods.values() for m in v)} settings documented")
