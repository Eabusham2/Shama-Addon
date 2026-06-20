# Shama Addon

A Meteor Client addon for Minecraft 1.21.11 (Fabric).

A few modules I made for my own use. Putting it up in case it's useful to someone else. Built off the Meteor addon template.

## Modules

**Hostile ESP** — boxes/wireframe + optional tracers on hostile mobs. Range filter, per-type filtering, separate fill/line/tracer colors.

**Light ESP** — base finder. While you're above Y=0 it scans loaded chunks within render distance and flags any chunk that has block light below Y=0 (where vanilla terrain should be pitch-black deepslate). Draws a square outline on flagged chunks. Sensitivity setting controls how faint a light counts.

**Y Level Spoof (risky)** — sends position packets with a spoofed Y, then turns itself off. You don't actually move; the server just briefly sees you somewhere else. Most anti-cheats will flag this, hence the name.

**Ping Spoofer** — delays KeepAlive packets to raise your measured ping. Has a fake-lag toggle that delays all outgoing packets instead, for a blink-style desync. Ping spoof code is from the Vector addon.

There's also a latest.log filter that runs at launch (denylist by default, editable config at `config/shama-log.json`).

## Build

Needs JDK 21. Gradle 9.x + Loom 1.14.

```
gradlew build
```

Jar ends up in `build/libs/`. Drop it in your mods folder next to Meteor Client.

## Notes

Some of this is anti-cheat bait — the Y spoof and fake lag especially. Use on your own account at your own risk. Not meant for servers that care.

## Credits

- Ping spoof module from [Vector addon](https://github.com/cally72jhb/vector-addon)
- Meteor addon template
