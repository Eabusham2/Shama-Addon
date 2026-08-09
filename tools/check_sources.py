#!/usr/bin/env python3
"""
Source checks for Shama Addon.

These are the sweeps used while building it, kept so the same mistakes get caught before a build
rather than after. They look for the things that have actually gone wrong here before:

  * unbalanced braces or brackets
  * imports that are unused, or listed twice
  * a setting declared and then never read, which means a menu option that does nothing
  * @EventHandler separated from its method, or stuck on something that is not an event handler
  * two settings in one module sharing a name, which Meteor cannot tell apart
  * a setting or group used above the line that declares it
  * .visible() pointed at a setting that is not a Boolean
  * a module missing its ++ suffix or its description
  * a mixin listed in a required config with no matching file, which crashes the client at startup

None of this replaces the compiler. It catches the layer underneath: things that compile but are
wrong, and things that break at runtime instead of at build time.
"""

import json
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")
RES = os.path.join(ROOT, "src", "main", "resources")
MODULES = os.path.join(SRC, "shama", "addon", "modules")

problems = []


def note(kind, where, detail):
    problems.append(f"{kind:<12} {where:<28} {detail}")


def java_files(path):
    for base, _, names in os.walk(path):
        for n in sorted(names):
            if n.endswith(".java"):
                yield os.path.join(base, n)


def strip_code(text):
    """Remove comments and string literals so counting is not thrown off by braces inside them."""
    out, i, n, quote = [], 0, len(text), None
    while i < n:
        c = text[i]
        if quote is None:
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                j = text.find("\n", i)
                i = n if j < 0 else j
                continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                j = text.find("*/", i + 2)
                i = n if j < 0 else j + 2
                continue
            if c in "\"'":
                quote = c
                i += 1
                continue
            out.append(c)
            i += 1
        else:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
            i += 1
    return "".join(out)


def check_structure(path, text):
    name = os.path.basename(path)
    code = strip_code(text)
    if code.count("{") != code.count("}"):
        note("BRACES", name, "unbalanced { }")
    if code.count("(") != code.count(")"):
        note("BRACKETS", name, "unbalanced ( )")


def check_imports(path, text):
    name = os.path.basename(path)
    body = "\n".join(l for l in text.split("\n") if not l.strip().startswith("import "))
    body = strip_code(body)
    imports = re.findall(r"^import ([\w.]+);$", text, re.M)

    for dup in [i for i, c in Counter(imports).items() if c > 1]:
        note("DUP-IMPORT", name, dup)

    for imp in imports:
        simple = imp.split(".")[-1]
        if simple == "*":
            continue
        if not re.search(r"(?<![\w.])" + re.escape(simple) + r"\b", body):
            note("UNUSED", name, imp)


def check_settings(path, text):
    name = os.path.basename(path)

    # a setting that is never read is a menu option that does nothing
    for m in re.finditer(r"Setting<[^>]+>\s+(\w+)\s*=.*?\.build\(\)\);", text, re.DOTALL):
        var = m.group(1)
        inside = len(re.findall(r"(?<![\w.])" + var + r"\.get\(\)", m.group(0)))
        reads = len(re.findall(r"(?<![\w.])" + var + r"\.get\(\)", text))
        refs = len(re.findall(r"(?<![\w.])" + var + r"::get", text))
        passed = len(re.findall(r"\(\s*" + var + r"\s*[,)]", text))
        if reads - inside + refs + passed < 1:
            note("DEAD", name, f"{var} is never read")

    # Meteor cannot tell two settings with the same name apart
    names = re.findall(r'\.name\("([a-z0-9 ()_\-]+)"\)', text)
    for dup in [n for n, c in Counter(names).items() if c > 1]:
        note("DUP-SETTING", name, dup)

    # every setting needs a description, since that is the only in-game explanation
    for m in re.finditer(r'\.name\("([a-z0-9 ()_\-]+)"\)(.*?)\.build\(\)', text, re.DOTALL):
        if ".description(" not in m.group(2):
            note("NO-DESC", name, m.group(1))

    # a field cannot be used above the line that declares it
    declared = {}
    for m in re.finditer(r"(?:Setting<[^>]+>|SettingGroup)\s+(\w+)\s*=", text):
        declared.setdefault(m.group(1), m.start())
    for m in re.finditer(r"(\w+)(?:::get|\.add\()", text):
        var = m.group(1)
        if var in declared and m.start() < declared[var]:
            note("FORWARD-REF", name, var)

    # .visible() needs something that returns a boolean
    types = {m.group(2): m.group(1) for m in re.finditer(r"Setting<(\w+)>\s+(\w+)\s*=", text)}
    for m in re.finditer(r"\.visible\((\w+)::get\)", text):
        t = types.get(m.group(1))
        if t is not None and t != "Boolean":
            note("VISIBLE", name, f"{m.group(1)} is Setting<{t}>, not Boolean")

    # a setting group has to exist before anything is added to it
    groups = set(re.findall(r"SettingGroup\s+(\w+)\s*=", text))
    for m in re.finditer(r"=\s*(sg\w*)\.add\(", text):
        if m.group(1) not in groups:
            note("NO-GROUP", name, m.group(1))


def check_handlers(path, text):
    name = os.path.basename(path)
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if line.strip() != "@EventHandler":
            continue
        nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
        if not re.match(r"(private|public|protected).*\(", nxt):
            note("ANNOTATION", name, f"line {i + 1}: @EventHandler is not on a method")

    # an event handler with no event parameter never fires
    for m in re.finditer(r"@EventHandler\s*\n\s*(?:private|public|protected)[^\n(]*\(([^)]*)\)", text):
        if "Event" not in m.group(1):
            note("ANNOTATION", name, "@EventHandler on a method that takes no event")


def check_module_identity(path, text):
    name = os.path.basename(path)
    m = re.search(r'super\(shama\.addon\.ShamaAddon\.([A-Z]+),\s*"([^"]+)"\s*,\s*"([^"]*)"\)', text)
    if not m:
        return
    if not m.group(2).endswith("++"):
        note("NAME", name, f"'{m.group(2)}' does not end in ++")
    if len(m.group(3)) < 25:
        note("THIN-DESC", name, m.group(2))


def check_registration():
    addon = os.path.join(SRC, "shama", "addon", "ShamaAddon.java")
    if not os.path.exists(addon):
        note("MISSING", "ShamaAddon.java", "not found")
        return
    text = open(addon).read()
    registered = set(re.findall(r"Modules\.get\(\)\.add\(new (\w+)\(\)\)", text))
    on_disk = {os.path.basename(p)[:-5] for p in java_files(MODULES)}
    for missing in sorted(on_disk - registered):
        note("UNREGISTERED", missing, "module file is never registered")
    for ghost in sorted(registered - on_disk):
        note("GHOST", ghost, "registered but no module file")


def check_mixins():
    for cfg in sorted(f for f in os.listdir(RES) if f.endswith(".mixins.json")):
        data = json.load(open(os.path.join(RES, cfg)))
        pkg = data.get("package", "").replace("shama.addon.mixin", "").strip(".")
        base = os.path.join(SRC, "shama", "addon", "mixin", *(pkg.split(".") if pkg else []))
        entries = set(data.get("client", [])) | set(data.get("mixins", []))
        for e in sorted(entries):
            if not os.path.exists(os.path.join(base, e + ".java")):
                kind = "MIXIN-CRASH" if data.get("required") else "MIXIN-MISS"
                note(kind, cfg, f"{e} has no file")


def main():
    for path in java_files(SRC):
        text = open(path, encoding="utf-8", errors="replace").read()
        check_structure(path, text)
        check_imports(path, text)
        if os.path.dirname(path) == MODULES:
            check_settings(path, text)
            check_handlers(path, text)
            check_module_identity(path, text)

    check_registration()
    check_mixins()

    if problems:
        print(f"{len(problems)} problem(s) found:\n")
        for p in problems:
            print("  " + p)
        return 1

    print("All source checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
