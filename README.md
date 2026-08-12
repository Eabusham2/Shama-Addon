<h1 align="center">Shama Addon</h1>

<p align="center">
  <a href="https://github.com/Eabusham2/Shama-Addon/actions/workflows/build.yml"><img src="https://github.com/Eabusham2/Shama-Addon/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/Eabusham2/Shama-Addon/releases/latest"><img src="https://img.shields.io/github/v/release/Eabusham2/Shama-Addon?label=download" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.21.11-brightgreen" alt="Minecraft 1.21.11">
  <img src="https://img.shields.io/badge/loader-Fabric-blue" alt="Fabric">
  <img src="https://img.shields.io/badge/licence-GPL--3.0-orange" alt="GPL-3.0">
</p>

<p align="center">
  <b>95 modules for Meteor Client</b>, built around finding what other people have hidden —<br>
  bases, stashes, farms and players — with a full set of combat, movement and utility modules alongside.
</p>

---

## Download

**[⬇ Get the latest build](https://github.com/Eabusham2/Shama-Addon/releases/latest)**

Rebuilt automatically every time the code changes, so that link is always the newest version.

1. Install [Fabric](https://fabricmc.net/) for Minecraft 1.21.11 and [Meteor Client](https://meteorclient.com/)
2. Drop the `.jar` into your `mods` folder next to Meteor
3. Launch — the modules appear in the Meteor menu under their own categories

---

## What's in it

| Category | Modules | What it covers |
|---|---:|---|
| [Finding bases, stashes & players](#finding-bases-stashes--players) | 34 | Bases, stashes, farms and players — found from chunk data, packets, block patterns and timing. |
| [Combat](#combat) | 14 | Crystals, bows, auras and self-defence. |
| [Movement](#movement) | 13 | Flight, elytra, freecam and movement helpers. |
| [Player](#player) | 17 | Mining, inventory and interaction with the world. |
| [Misc](#misc) | 17 | Rendering, chat, HUD, timing and utilities. |
| **Total** | **95** | |

Every module has settings, and every setting explains itself when you hover over it in-game.
The full write-up of every module, setting and mode is in **[MODULES.md](MODULES.md)**.

---

## Worth starting with

- **anti-anti-esp++** — Reads the channels a server cannot fake — block entities, particles, sounds — and keeps only what lands where your client shows plain stone.
- **active-chunk-detector++** — Tells a chunk somebody is holding open from one nobody has touched, by reading the parts of its state that only move while a player is near.
- **anomaly-scan++** — Learns what ordinary ground looks like on your server, then flags what does not match. Finds builds made from plain terrain that block lists never catch.
- **find-log++** — Every find from every detection module in one list with coordinates, sorted by distance or by when it happened.
- **search-map++** — Remembers the ground you have already swept and points you at what you have not.

---

## All modules

### Finding bases, stashes & players

*Bases, stashes, farms and players — found from chunk data, packets, block patterns and timing.*

<details>
<summary><b>34 modules</b> — click to expand</summary>

- **active-chunk-detector++** — Tells a chunk somebody is holding open from one nobody has touched, by reading the parts of its state that only move while a player is near it.
- **anomaly-scan++** — Learns what ordinary ground looks like on this server, then flags the chunks that do not match — finds builds made from plain terrain that block lists never catch.
- **anti-anti-esp++** — Recovers blocks the server left out of your chunk data. It listens to the messages that carry a real position — block entities, particles, sounds, live block changes — and keeps only the ones landing where your client shows plain stone, which is the server contradicting itself.
- **base-detector++** — Flags chunks emitting heavy particle activity (working bases).
- **block-entity-debug++** — Finds chests, hoppers, spawners and other containers below a set height — a stash finder.
- **break-indicators++** — Boxes blocks being broken, shrinking with progress.
- **chunk-finder++** — Base/stash chunk finder with selectable detection methods (geology, entities, growth, velocity).
- **chunk-loader++** — Keeps the server sending you chunks instead of letting them settle. Safe methods ask politely; the risky ones claim a position you aren't at and load far more ground.
- **deep-activity++** — Everything block-update based, each its own tick: hidden chunk activity below a Y line, raw update positions anywhere, and mining-rate / tunnel alerts.
- **find-log++** — Collects every find from every detection module into one list with coordinates, sorted by distance or by when it happened.
- **geode-finder++** — Marks amethyst so you can tell a farmed geode from an untouched one — colour each crystal by how grown it is, or box the whole geode.
- **hidden-player-detect++** — Spots players moving around underground where you can't see them, by picking up the traces their movement leaves behind.
- **hole-finder++** — Finds shafts someone dug and then plugged behind them, and marks the exact block that caps each one.
- **hopper-debug++** — Finds hoppers and reads the signal strength around them, which gives away sorting systems and hidden storage.
- **light-debug++** — Everything to do with light in one place: light sources, where mobs can spawn, and pockets of darkness that shouldn't be there.
- **loaded-region-finder++** — Finds 512x512 regions that have an unusual number of chunks loaded — chunk loaders, big bases and other places the server is working hard.
- **logout-spots++** — Marks where other players log out.
- **ocean-monument-finder++** — Detects nearby ocean monuments by spotting guardian entities (their only habitat).
- **ore-sim++** — Predicts where ores are from the world seed, accurate to vanilla generation. Enter a seed or let it detect one.
- **ore-spotter++** — X-ray that highlights ores through terrain, kept smooth so it doesn't stutter as you move.
- **particle-esp++** — Highlights particles spawning underground while you're above ground (someone is down there).
- **player-detector++** — Popup / sound / chat when another player renders near you.
- **rare-finder++** — Highlights valuable items on the ground or in item frames, and can beam them so you spot them from far off.
- **region-map++** — Shows the server's region grid - every region numbered and shaded by which datacentre it runs on, with a marker for the one you're in.
- **rtp-finder++** — Teleports around with /rtp hunting for rare loot, marks anything it finds with a beacon, and stops itself once it does.
- **search-map++** — Remembers which ground you have already swept and points you at the ground you have not, so you stop covering the same area twice.
- **signal-scanner++** — Hunts for the redstone and wiring signatures that give away hidden bases and farms.
- **spawn-cluster-finder++** — Boxes chunks with unusually dense live hostile-mob counts.
- **spawner-finder++** — Highlights mob and trial spawners through terrain, at any height.
- **staff-detector++** — Alerts (popup/chat/sound) when likely staff go online or offline.
- **stronghold-finder++** — Works out where the stronghold is from two ender eye throws and marks the spot.
- **sus-chunk-finder++** — Finds chunks where plants and blocks have overgrown far past natural amounts — a sign someone has been living or AFKing nearby keeping the area loaded. Not a farm finder.
- **tunnel-finder++** — Finds long straight tunnels players have dug, including ones far underground you'd never stumble across.
- **voice-chat-sniffer++** — Highlights the chunk of a deep voice-chat transmitter (passive, no audio).

</details>

### Combat

*Crystals, bows, auras and self-defence.*

<details>
<summary><b>14 modules</b> — click to expand</summary>

- **attribute-swap++** — Swaps slots on attack to refresh weapon attributes/cooldown.
- **auto-city++** — Breaks the block beside a target in a hole to expose them.
- **auto-log++** — Auto-disconnect at low health or when a player approaches.
- **bombaura++** — Auto anchor/bed aura (place, charge, detonate on nearby enemies).
- **bow-aimbot++** — Auto-aims your bow at the nearest target while drawing.
- **combat-extras++** — Bow-spam / auto-exp / d-tap.
- **combat-macros++** — Anchor / wind-pearl combo macros on a keybind.
- **crystal-aura++** — Places and blows end crystals for you. Also carries the helpers: crystals you hit vanish on your client straight away so you can chain into the next one, and the best spot to place is highlighted. Turn place and break off to use it as helpers only.
- **crystal-optim++** — Makes crystals you hit vanish on your client straight away so you can chain into the next one, and highlights the best spot to place.
- **fast-bow++** — Draw bows and crossbows much faster than normal.
- **godmode++** — Makes you effectively unkillable — negates fall damage, knockback and other harm. How much applies depends on the server.
- **hit-particles++** — Spawns particles on the entity you attack.
- **self-defense++** — Self web / trap / anvil in one module.
- **wallbang++** — Lets you attack targets through walls. Whether it lands depends on the server.

</details>

### Movement

*Flight, elytra, freecam and movement helpers.*

<details>
<summary><b>13 modules</b> — click to expand</summary>

- **auto-wasp++** — Auto-fly pursuit that holds an offset over the nearest target.
- **click-tp++** — Blink forward along your look. Bind a key and tap it.
- **elytra-fly++** — Enhanced elytra flight with extra control and boost options.
- **fast-climb++** — Climb ladders/vines faster.
- **flight++** — Fly freely. How well it holds up depends on the server's anti-cheat.
- **freecam++** — Smooth free-flying detached camera with pathing / rotate / sneak options.
- **jumps++** — High / long / air / auto jump in one module.
- **movement-extras++** — GUI-move / entity-control / slippy / reverse-step in one module.
- **noclip++** — Lets you move freely through blocks. Works where the server doesn't correct your position (own worlds and lenient servers).
- **reverse-step++** — Fall down small ledges instantly.
- **snap-tap++** — When you hold two opposite movement keys at once, the most recently pressed one wins instead of both cancelling out.
- **speed++** — Move faster than normal. How much you can get away with depends on the server's anti-cheat.
- **trident-boost++** — Riptide-launch in any conditions with a trident.

</details>

### Player

*Mining, inventory and interaction with the world.*

<details>
<summary><b>17 modules</b> — click to expand</summary>

- **auto-sign++** — Copies your first sign's text onto every sign after.
- **autoer++** — Auto-tool + auto-mount (and more) in one module.
- **chunk-reloader++** — Runs a command sequence (delhome -> sethome -> rtp -> home) to force a chunk reload.
- **home-utils++** — Save/goto named coordinate homes; optional auto-walk.
- **instant-mine++** — Break blocks far faster, with several methods to choose from (speed multiplier, packets, abort, sequenced, or an area nuker). Strength depends on the server.
- **invisibility++** — Turns you invisible to other players and mobs. How complete the invisibility is depends on the server.
- **miner++** — Mines for you — vein mining, fast packet mining, or a wide excavator, all in one module.
- **nbt-adder++** — Copy item NBT by middle-click and apply custom NBT/components to held items (creative). Java component-system equivalent of Horion's NBT editor.
- **no-break-delay++** — Removes the cooldown between breaking blocks. Mine continuously with no pause.
- **no-cooldown++** — Removes the attack-swing delay and item cooldowns (like ender pearls) so you can act back-to-back. Strength depends on the server.
- **portal-inv++** — Access your inventory during portal transit loading.
- **reach++** — Extends attack range with selectable targeting. Rejected by strict server anti-cheats.
- **render-method++** — Forces chunks to reload so terrain the server sent but your client never drew shows up.
- **swing-speed++** — Controls how fast your arm swings — a set speed, a fixed duration, or auto-timed to whatever you're doing (mining a block, attacking, or using any item like fireworks).
- **timer++** — Speeds up or slows down your game clock, with several styles and fine control.
- **world-extras++** — Flamethrower / liquid-fill world helpers.
- **y-level-spoof++** — RISKY: Tries to fake your height to the server without moving you. Most anti-cheats will catch this.

</details>

### Misc

*Rendering, chat, HUD, timing and utilities.*

<details>
<summary><b>17 modules</b> — click to expand</summary>

- **anti-afk++** — Periodic actions to prevent AFK kicks.
- **book-bot++** — Writes the held writable book with your text.
- **bypass++** — Small client-side tweaks that make other modules look more legitimate to anti-cheats.
- **camera-tweaks++** — Custom FOV and view-bob overrides.
- **chat-extras++** — Chat spam + proximity message-aura.
- **fake-visuals++** — Client-side fakes for screenshots — a pay receipt that never sends, and a sidebar of your own invention.
- **force-commands++** — Give yourself operator permissions so all commands work even with cheats off. Works where you have that authority (your own worlds and servers that allow it).
- **hide-chat++** — Hides chat & history but still lets you type.
- **hostile-esp++** — ESP for hostile mobs with an optional tracer.
- **item-highlight++** — Highlights dropped items on the ground.
- **lag-detector++** — Finds places the server is struggling — a farm, a stash full of hoppers, or anything else eating server time. Watches server tick rate, your own framerate, and how bad the worst frames get.
- **notifiers++** — Rain + low-durability chat alerts.
- **packet-logger++** — Lists the network packets going past in chat — a debug tool for seeing what the server is actually sending.
- **ping-spoofer++** — Raises your measured ping by delaying KeepAlive packets, or delays all packets for fake lag.
- **swarm++** — Coordinate your own alt accounts (host/worker) over a port you control.
- **time-changer++** — Force a custom client-side time of day.
- **trail++** — A fading trail behind you.

</details>

---

## Every module, one line

For searching the page with a single find:

`active-chunk-detector++, anomaly-scan++, anti-anti-esp++, base-detector++, block-entity-debug++, break-indicators++, chunk-finder++, chunk-loader++, deep-activity++, find-log++, geode-finder++, hidden-player-detect++, hole-finder++, hopper-debug++, light-debug++, loaded-region-finder++, logout-spots++, ocean-monument-finder++, ore-sim++, ore-spotter++, particle-esp++, player-detector++, rare-finder++, region-map++, rtp-finder++, search-map++, signal-scanner++, spawn-cluster-finder++, spawner-finder++, staff-detector++, stronghold-finder++, sus-chunk-finder++, tunnel-finder++, voice-chat-sniffer++, attribute-swap++, auto-city++, auto-log++, bombaura++, bow-aimbot++, combat-extras++, combat-macros++, crystal-aura++, crystal-optim++, fast-bow++, godmode++, hit-particles++, self-defense++, wallbang++, auto-wasp++, click-tp++, elytra-fly++, fast-climb++, flight++, freecam++, jumps++, movement-extras++, noclip++, reverse-step++, snap-tap++, speed++, trident-boost++, auto-sign++, autoer++, chunk-reloader++, home-utils++, instant-mine++, invisibility++, miner++, nbt-adder++, no-break-delay++, no-cooldown++, portal-inv++, reach++, render-method++, swing-speed++, timer++, world-extras++, y-level-spoof++, anti-afk++, book-bot++, bypass++, camera-tweaks++, chat-extras++, fake-visuals++, force-commands++, hide-chat++, hostile-esp++, item-highlight++, lag-detector++, notifiers++, packet-logger++, ping-spoofer++, swarm++, time-changer++, trail++`

---

## Building it yourself

```bash
./gradlew build
```

The finished `.jar` lands in `build/libs/`. You will need Java 21.

---

## Licence

**GPL-3.0-or-later.** Meteor Client is GPL-3.0 and that licence is copyleft, so anything built
against it carries the same terms: use it, change it, share it, but keep it open. See [LICENSE](LICENSE).
