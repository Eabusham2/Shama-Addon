# Module reference

Every one of the **95 modules**, what it is for, and what each of its settings does.

Settings marked *(hidden until enabled)* only appear once the option above them is switched on, so the menu stays short until you need the detail. Anything named **(risky)** fabricates movement, rotation or timing you did not actually perform — those are off by default and can get you rubber-banded or kicked.

---

## Finding bases, stashes & players

The core of the addon. These look for other people's things — bases, stashes, farms, hidden players — using chunk data, packets, block patterns and timing.

### active-chunk-detector++

Tells a chunk somebody is holding open from one nobody has touched, by reading the parts of its state that only move while a player is near it.

**General**

- `chunk-range` — *default `16`*  
  How far out to keep showing traces, in chunks.
- `chat` — *default `true`*  
  Print a message the first time each area is picked up.

**Methods**

- `held-in-memory` — *default `true`*  
  Flag chunks the server hands back too fast to have come off disk. Reading a chunk from storage takes real time; one already sitting in memory does not, and it is only in memory because something is keeping it there.
- `instant-threshold` — *default `3`, hidden until enabled*  
  A chunk arriving within this many milliseconds of the one before counts as instant. Lower is stricter.
- `instant-run` — *default `20`, hidden until enabled*  
  How many instant arrivals in a row before it counts. A couple happen naturally; a long run does not.
- `adaptive-timing` — *default `true`, hidden until enabled*  
  Work out what a normal arrival gap looks like on this server rather than using a fixed number. Servers that tick regions on separate threads — Folia and its forks — deliver chunks in bursts that a fixed threshold either misses entirely or fires on constantly. Learning the baseline first fixes both.
- `has-grown` — *default `true`*  
  Flag chunks holding crops, saplings or plants grown past the stage they reach on their own. Growth only happens while a chunk is being ticked, so advanced growth is proof the ground was held open — and because it is written into the block itself, it survives relogs, works at any distance and does not care how the server threads its regions. This is the one that cannot be fooled.
- `min-grown` — *default `12`, hidden until enabled*  
  How many well-grown plants a chunk needs before it counts. A couple grow naturally near spawn chunks; a field of them does not.
- `ignore-my-range` — *default `12`*  
  Ignore chunks this close to you, in chunks. Your own presence is what keeps nearby ground loaded and ticking, so anything inside your render distance would flag on you rather than on anybody else. Set it to roughly your render distance.
- `state-change` — *default `true`*  
  Compare a chunk against how it looked last time and see whether the parts that only move while it is loaded have moved. The clock the game keeps of how long players have spent in a chunk is the main one: it advances while somebody is in range and sits perfectly still otherwise. So if it has gone up between two sightings, a player was there in between. Nothing else can move it, which makes this proof rather than a guess.
- `min-gap` — *default `60`, hidden until enabled*  
  How long you must have been away before a comparison counts, in seconds. Too short and you catch normal ticking from your own presence.
- `min-change` — *default `3`, hidden until enabled*  
  How many seconds the inhabited clock has to gain before it counts. One or two can come from you passing through yourself; more than that means somebody else.
- `total-time-here` — *default `true`*  
  Flag chunks the game says players have spent a long time in. It is recorded per chunk and survives after they leave, so it points at where somebody actually lives rather than where they walked.
- `minutes-spent` — *default `120`, hidden until enabled*  
  How many minutes of recorded presence a chunk needs.
- `changes-unattended` — *default `true`*  
  Flag chunks whose blocks change while no player is visible to you. Something is running there — a farm ticking, or somebody out of render distance.
- `min-changes` — *default `20`, hidden until enabled*  
  How many changes with nobody visible before it counts.

**Render**

- `box-y` — *default `63`*  
  Height to draw the boxes at. Sea level by default so they sit where you can see them.
- `color` — *default `new SettingColor(90, 0, 160, 90`*  
  Colour of the trace boxes.
- `shape-mode` — *default `ShapeMode.Both`*  
  Outline only, filled sides only, or both.
- `tracers` — *default `false`*  
  Draw a line from you to each trace.


### anomaly-scan++

Learns what ordinary ground looks like on this server, then flags the chunks that do not match — finds builds made from plain terrain that block lists never catch.

**General**

- `deviation` — *default `3.0`*  
  How far from normal a chunk has to sit before it counts, measured in standard deviations. 2 flags roughly one chunk in twenty and finds plenty of nothing; 3 is the usual sweet spot; 4 only fires on the obvious.
- `learn-first` — *default `120`*  
  How many chunks to measure before it starts judging anything. It cannot know what is unusual until it has seen enough ordinary ground, and too few makes it flag everything.
- `min-signals` — *default `2`*  
  How many separate properties must be off at once. One odd number happens naturally all the time; two or three together rarely does.
- `min-y` — *default `-60`*  
  Lowest height to measure.
- `max-y` — *default `60`*  
  Highest height to measure. Keeping this under the surface avoids trees and hills skewing the baseline.
- `scan-rate` — *default `3`*  
  How many newly loaded chunks to measure each tick. Lower this if the game stutters while flying.
- `chat` — *default `true`*  
  Report each anomaly in chat, along with which properties were off.
- `relearn-on-dimension` — *default `true`*  
  Throw the baseline away when you change dimension. The Nether looks nothing like the Overworld, so a baseline learned in one is meaningless in the other.

**Render**

- `range` — *default `16`*  
  How far out to keep showing flagged chunks, in chunks.
- `box-y` — *default `63`*  
  Height to draw the boxes at. Sea level by default.
- `weak-color` — *default `new SettingColor(255, 220, 60, 70`*  
  Colour for a chunk that only just crosses the line.
- `strong-color` — *default `new SettingColor(255, 60, 60, 110`*  
  Colour for a chunk far outside normal — the ones worth flying to.
- `shape-mode` — *default `ShapeMode.Both`*  
  Outline only, filled sides only, or both.


### anti-anti-esp++

Recovers blocks the server left out of your chunk data. It listens to the messages that carry a real position — block entities, particles, sounds, live block changes — and keeps only the ones landing where your client shows plain stone, which is the server contradicting itself.

**General**

- `range` — *default `160`*  
  How far out to keep what has been recovered, in blocks.
- `forget-on-update` — *default `true`*  
  Keep a recovered position until the server actually says that block changed, instead of dropping it after a timer. Hiding a block from chunk data is easy, but when somebody breaks it the server has to send a block update or your world would be wrong — so no update means it is still there. Works on anything, not just amethyst, and it sends nothing.
- `remember-for` — *default `600`, hidden until enabled*  
  How long a recovered position stays on screen, in seconds. Only used when forget-on-update is off — with that on, a position is kept until the server says it changed, which needs no timer.
- `chat` — *default `false`*  
  Report each recovery in chat.

**Channels**

- `block-changes` — *default `true`*  
  Read single block changes. Something being placed inside ground the server told you was solid is that ground exposed as a lie. Blocks being broken are ignored — that is activity, not a hidden find, and deep-activity++ is the module for it.
- `bulk-changes` — *default `true`*  
  Read bulk section updates as well. Mining and explosions arrive this way rather than one block at a time, so leaving this off misses most of what people are doing.
- `block-entities` — *default `true`*  
  Read block entities. Chests, hoppers, spawners and signs travel in their own packet with a real position attached, and the protection does not touch it — this is the most reliable channel there is.
- `particles` — *default `true`*  
  Read particles. Amethyst, dripstone drips, portals and lava all give themselves away this way, and it works even when the block itself has been stripped out of the chunk you were sent.
- `sounds` — *default `true`*  
  Read sounds. Amethyst chimes, water drips and machinery all carry a position, so a place that is silent in your chunk data can still be noisy on this channel.

**Filter**

- `beyond-send-range` — *default `true`*  
  Trust anything reported from further away than the server says it sends chunks. It announces that distance itself, so a position past it cannot have come from chunk data at all — it can only have arrived on one of the honest channels. That makes it certain rather than probable, and it needs no guessing about what your client is drawing.
- `only-hidden` — *default `true`*  
  Only keep positions where your client currently shows deepslate, stone or air. That mismatch is the protection caught in the act — the server described something real in a place it told you was solid rock.
- `below-y-only` — *default `true`*  
  Only keep what is under the height below, which is where the ground gets replaced.
- `below-y` — *default `0`, hidden until enabled*  
  The height that limit uses.

**Force Data**

- `force-data` — *default `false`*  
  Try to make the server send the real thing rather than only listening for slips. It decides what to hide from two things it cannot verify: where you say you are, and where you say you are looking. Everything under this claims one of those is different from the truth, so all of it carries some risk — the options only appear once this is on.
- `look-down (risky)` — *default `false`, hidden until enabled*  
  Report your view as pointing straight down while your actual view stays put. If the rule is that you only get amethyst and dripstone you are looking at, then as far as the server is concerned you are always looking at the ground. Your aim on screen does not move, but rotation you never made is the classic thing anti-cheats watch for.
- `look-sweep (risky)` — *default `false`, hidden until enabled*  
  Turn the reported view right around between updates so every direction counts as looked at, not just downward. Covers far more ground than looking down alone, and looks correspondingly worse — a player does not spin like this.
- `claim-below-y (risky)` — *default `false`, hidden until enabled*  
  Report a height under the cutoff so the server stops swapping the ground for deepslate, then correct back the same tick. This is the one that gets you data from above ground, and it is also the most obvious — you are claiming to be somewhere you are not.
- `claim-y` — *default `-8`, hidden until enabled*  
  The height to report. It needs to sit under the cutoff to be worth anything.
- `force-rate` — *default `10`, hidden until enabled*  
  Ticks between attempts. Slower is quieter and less likely to be noticed.

**Render**

- `block-color` — *default `new SettingColor(0, 255, 140, 220`*  
  Colour for blocks recovered from change or block-entity packets.
- `hint-color` — *default `new SettingColor(255, 190, 0, 180`*  
  Colour for positions recovered from particles or sounds, where the exact block is unknown.
- `shape-mode` — *default `ShapeMode.Both`*  
  Outline only, filled sides only, or both.
- `tracers` — *default `false`*  
  Draw a line from you to each recovery.


### base-detector++

Flags chunks emitting heavy particle activity (working bases).

**General**

- `score-by-type` — *default `true`*  
  Score particles by how base-like they are (witch 4, flame/enchant 3, smoke/portal/campfire 2, redstone/note 1) instead of counting each one as 1.
- `block-scoring` — *default `false`*  
  Also score chunks by player-placed blocks (workstations/storage/farming/lighting), on top of particle activity.
- `block-scan-interval` — *default `40`, hidden until enabled*  
  Ticks between block-scoring passes.
- `threshold` — *default `25`*  
  Particle packets in the window to flag a chunk.
- `window-ticks` — *default `60`*  
  Length of the counting window, in ticks.
- `box-y` — *default `64`*  
  The Y height to draw the box/marker at.
- `line-color` — *default `new SettingColor(255, 90, 255, 220`*  
  Colour of the box outline.


### block-entity-debug++

Finds chests, hoppers, spawners and other containers below a set height — a stash finder.

**General**

- `y-threshold` — *default `40`*  
  Only keep block entities at or below this Y.
- `containers-only` — *default `true`*  
  Only storage (chests, barrels, shulkers, hoppers, furnaces...). Off = every block entity.
- `deduplicate` — *default `true`*  
  Keep one marker per position; don't re-add when a chunk reloads.
- `live-updates` — *default `true`*  
  Also read the raw BlockEntityUpdate packet stream, not just chunk packets.
- `catch-disguised-blocks` — *default `false`*  
  Flag block entities the server packs down to bedrock (Y<=spoof-y) to HIDE their real position. On servers that scrub stash coords, these low-Y ghosts are the tell that a stash exists nearby.
- `spoof-y` — *default `0`, hidden until enabled*  
  A block entity at or below this Y is treated as a hidden/spoofed position.
- `chat` — *default `true`*  
  Print each new find to chat.
- `alert-nbt` — *default `false`, hidden until enabled*  
  Include the block entity's NBT in the chat alert (observable data only).
- `max-stored` — *default `2000`*  
  Cap on finds kept/rendered.

**Render**

- `render-distance` — *default `256`*  
  Only draw finds within this many blocks.
- `tracers` — *default `true`*  
  Line from camera to each find.
- `shape-mode` — *default `ShapeMode.Both`*  
  Box fill/outline.
- `fill-color` — *default `new SettingColor(225, 0, 255, 40`*  
  Colour of the filled faces of each box.
- `line-color` — *default `new SettingColor(225, 0, 255, 255`*  
  Box outline.
- `tracer-color` — *default `new SettingColor(225, 0, 255, 160`*  
  Tracer line.
- `mark-deep-chunks` — *default `false`*  
  Draw a flat box at sea level over any chunk that holds a block entity below deep-y, once it's past box range. Lets you spot deep-stash chunks from high ground / across the map.
- `deep-y` — *default `5`, hidden until enabled*  
  A block entity below this Y makes its chunk get a box.
- `box-y` — *default `63`, hidden until enabled*  
  Y level to draw the chunk box at (sea level by default).
- `box-distance` — *default `1024`, hidden until enabled*  
  Max horizontal distance to draw deep-chunk boxes.
- `box-color` — *default `new SettingColor(255, 60, 60, 220`, hidden until enabled*  
  Deep-chunk box color.

**Hoppers**

- `hopper-signals` — *default `false`*  
  Read the comparator signal coming off nearby hoppers. A hopper that reports a changing signal is moving items, which gives away a sorting system or an active farm even when the storage behind it is walled in. Also available on its own as hopper-debug++; running both is harmless.
- `min-hoppers` — *default `4`, hidden until enabled*  
  How many hoppers must sit together before it's worth reporting.
- `hopper-color` — *default `new SettingColor(255, 200, 60, 200`, hidden until enabled*  
  Colour used for hopper clusters.

**Hidden Storage**

- `hidden-storage` — *default `false`*  
  Compare the containers the server tells you about against the ones your game is actually drawing. Anything the server sent but you can't see is buried, walled in, or behind a chunk that never rendered — which is exactly where stashes are.
- `min-hidden` — *default `4`, hidden until enabled*  
  How many unseen containers a chunk needs before it's reported.
- `pinpoint` — *default `true`, hidden until enabled*  
  Box each unseen container individually instead of just marking the chunk, so you know exactly where to dig.
- `hidden-color` — *default `new SettingColor(255, 0, 200, 220`, hidden until enabled*  
  Colour used for unseen containers.

**Dense Chunks**

- `stash-chunks` — *default `false`*  
  Also box whole chunks that are packed with block entities (a stash), not just the individual ones.
- `stash-threshold` — *default `30`, hidden until enabled*  
  How many block entities a chunk needs before it counts as a stash.
- `stash-marker-y` — *default `64`, hidden until enabled*  
  Y height to draw the dense-chunk marker at.
- `stash-color` — *default `new SettingColor(255, 0, 120, 200`, hidden until enabled*  
  Colour of the dense-chunk marker.

**Depth Guard**

- `depth-guard` — *default `false`*  
  Warn in chat as you approach the anti-cheat's Y limit, and remind you to re-log once you cross it. Chat only — it never disconnects you.
- `danger-y` — *default `-55`, hidden until enabled*  
  Y level that triggers the re-log reminder.
- `warn-distance` — *default `6`, hidden until enabled*  
  Blocks above danger-Y to start warning.


### break-indicators++

Boxes blocks being broken, shrinking with progress.

**General**

- `color-at-start` — *default `new SettingColor(255, 0, 0, 30`*  
  Colour at the start (0% progress).
- `color-when-done` — *default `new SettingColor(0, 255, 0, 60`*  
  Colour at the end (100% progress).
- `line-color` — *default `new SettingColor(255, 255, 255, 200`*  
  Colour of the box outline.
- `shape-mode` — *default `ShapeMode.Both`*  
  How boxes are drawn: outline only, filled sides only, or both.
- `timeout-ticks` — *default `100`*  
  Forget a break if no update arrives for this many ticks (in case the finish/cancel packet is missed).


### chunk-finder++

Base/stash chunk finder with selectable detection methods (geology, entities, growth, velocity).

**Modes** — All, Fast, Thorough, Custom

**General**

- `ignore-near-me` — *default `2`*  
  Never flag chunks this close to you. Mining or building changes the very blocks this looks for, so without it your own work keeps flagging the chunk you are standing in.
- `chunk-delay` — *default `0.0`*  
  Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.
- `sensitivity` — *default `3`*  
  How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.
- `scan-zone-size` — *default `1`*  
  Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.
- `custom-thresholds` — *default `false`*  
  Set each threshold by hand instead of letting the sensitivity slider work them out. Leave this off unless you want to fine-tune one particular detection.
- `methods` — *default `MethodPreset.All`*  
  Which detection methods to run. Fast = the cheap packet-based ones only (kind to your framerate). Thorough = everything including the block scans. All = same as Thorough. Custom = pick them yourself below.
- `scan-rate` — *default `4`*  
  How many newly loaded chunks to analyse each tick. Lower this if the game stutters while flying or after an RTP; raise it to find things sooner.
- `method-geology` — *default `true`, hidden until enabled*  
  Isolated mineral lines + deepslate anomalies (mined bases/tunnels).
- `method-entities` — *default `true`, hidden until enabled*  
  Kept pets / farm mobs above Y16 (villager, iron golem, fox, cat).
- `method-growth` — *default `true`, hidden until enabled*  
  Farm-scale kelp/vine columns.
- `method-velocity` — *default `false`, hidden until enabled*  
  Flag chunks that emit nonzero entity-velocity packets.
- `method-storage` — *default `true`, hidden until enabled*  
  Flag chunks packed with containers (stash / storage room).
- `storage-threshold` — *default `5`, hidden until enabled*  
  Containers in a chunk to flag it.
- `method-skylight` — *default `false`, hidden until enabled*  
  Roof detection (AnomalyColumnScanner method): flags surface columns that SHOULD see open sky but have blocked skylight above sea level = someone built a roof/overhang over them.
- `skylight-columns` — *default `20`, hidden until enabled*  
  Roofed columns in a chunk to flag it.
- `flat-clusters` — *default `false`*  
  Highlight patches of blocks laid out flat, the way a floor or a platform is. Terrain almost never produces a level slab of one material, so a flat patch is somebody having built one. The patch is drawn where it actually is rather than the whole chunk, and it can sit at any height: a floor, a roof, or a landing partway up a shaft.
- `flat-min-blocks` — *default `20`, hidden until enabled*  
  How many blocks a level patch needs before it counts. Small numbers pick up natural ledges; a proper floor is much bigger than that.
- `flat-color` — *default `new SettingColor(60, 130, 255, 90`, hidden until enabled*  
  Colour used for those patches.
- `method-unnatural` — *default `true`, hidden until enabled*  
  Blocks that do not generate underground: cobblestone, planks of any wood, torches, rails, ladders and crafting tables. None of it forms naturally down there, so a cluster of it is somebody's build. From the PlayerChunkFinder approach in the shared files.
- `unnatural-below-y` — *default `50`, hidden until enabled*  
  Only count those blocks below this height, where none of them belong.
- `method-redstone` — *default `true`, hidden until enabled*  
  Powered redstone: repeaters and comparators that are currently carrying a signal. Redstone only stays powered while something is driving it, so a live circuit underground is a base that is running right now rather than one somebody abandoned. Comes from the TuffChunkFinder approach in the shared files.
- `method-score` — *default `false`*  
  Weighted scoring (WizzyScanner / LarpDebug method): each signal in a chunk adds weighted points; flag if total >= min-score. Catches bases that are only mildly suspicious on any single signal but add up.
- `min-score` — *default `30`, hidden until enabled*  
  Total weighted score to flag a chunk.
- `min-column-height` — *default `25`, hidden until enabled*  
  Kelp/vine column height that counts as a farm .
- `entity-range` — *default `256`*  
  Max distance to react to a suspicious entity/velocity.

**Geology**

- `line-length` — *default `10`, hidden until enabled*  
  Min vertical run to count as a line.
- `isolation-radius` — *default `8`, hidden until enabled*  
  A line is only counted if no same block sits within this radius (natural rock is clustered).
- `detect-normal-deepslate` — *default `true`, hidden until enabled*  
  Look for ordinary deepslate placed where it shouldn't be. Deepslate only forms deep underground, so finding it up high usually means someone put it there.
- `deepslate-threshold` — *default `2`, hidden until enabled*  
  How many suspicious deepslate blocks (of the types ticked above) must be in a chunk before it's flagged. Lower = more sensitive, more false alarms.
- `detect-cobbled-deepslate` — *default `true`, hidden until enabled*  
  Look for cobbled deepslate (the cracked kind you get from mining it). It doesn't occur naturally, so a pile of it means player mining/building.
- `detect-rotated-deepslate` — *default `true`, hidden until enabled*  
  Look for deepslate turned on its side. Natural deepslate always stands upright, so sideways ones were placed by a player.
- `deepslate-min-y` — *default `16`, hidden until enabled*  
  Normal deepslate at/above this Y is suspicious (it's naturally below 0).
- `ignore-exposed` — *default `true`, hidden until enabled*  
  Ignore blocks touching air/fluid (natural exposure).
- `trial-chamber-skip` — *default `40`, hidden until enabled*  
  Skip a chunk with at least this many trial-chamber blocks.

**Render**

- `range` — *default `8`*  
  How far out to look for and show flagged chunks, measured in chunks around you. 0 = only the chunk you stand in, 8 = a 17x17 chunk area. Bigger = see more but heavier.
- `ignore-holey` — *default `false`*  
  Don't highlight a flagged chunk if it has an exposed vertical hole (cuts natural cave false positives).
- `require-min-evidence` — *default `false`*  
  Also require a chunk to hold at least 'sensitivity' evidence blocks (vines, kelp, or placed deepslate) before showing it. Filters out chunks that tripped on one weak signal.
- `loaded-ticks` — *default `false`*  
  Also flag chunks that stay loaded near you for a long time (persistent = base).
- `loaded-ticks-required` — *default `600`, hidden until enabled*  
  Ticks a chunk must stay loaded to flag.
- `render-y` — *default `64`*  
  Y level to draw the chunk box.
- `tracers` — *default `false`*  
  Draw a line from you to each flagged chunk so you can see the direction at a glance.
- `chat` — *default `true`*  
  Print a message in chat when a new suspicious chunk is found.
- `shape-mode` — *default `ShapeMode.Both`*  
  How the boxes are drawn: outline only, filled sides only, or both.
- `fill-color` — *default `new SettingColor(0, 255, 255, 90`*  
  The colour of the filled/shaded part of the box.
- `line-color` — *default `new SettingColor(0, 255, 255, 255`*  
  The colour of the box outline.
- `highlight-blocks` — *default `false`*  
  Instead of only a chunk box, colour each individual anomaly block (deepslate/cobbled/rotated) inside a flagged chunk.
- `highlight-color` — *default `new SettingColor(255, 255, 0, 160`, hidden until enabled*  
  The colour used when highlighting the individual suspicious blocks inside a chunk.


### chunk-loader++

Keeps the server sending you chunks instead of letting them settle. Safe methods ask politely; the risky ones claim a position you aren't at and load far more ground.

**General**

- `rate` — *default `10`*  
  Ticks between each round of requests. Faster keeps more chunks alive but sends more packets; slower is quieter.
- `only-when-still` — *default `false`*  
  Pause while you're actually walking. Moving already makes the server stream chunks, so this saves traffic when it isn't needed.

**Safe Methods**

- `request-chunks` — *default `false`*  
  Tell the server a low view distance and then immediately the real one again. It answers by re-sending the true contents of everything that came back into range, which is how you get past a server that fills distant chunks with fake deepslate. Your own render distance never changes — the value is restored in the same tick, so nothing you see is affected and no frames are lost.
- `request-every` — *default `60`, hidden until enabled*  
  Rounds between each re-request. Every reload costs a burst of traffic, so keep this comfortably above the rate.
- `request-distance` — *default `2`, hidden until enabled*  
  The low value reported to the server during a request. Lower means more chunks get re-sent when the real value is reported again a moment later.
- `view-refresh` — *default `false`*  
  Ask the client to rebuild its terrain, which makes it request anything the server sent but never drew. Purely local — nothing is sent to the server at all, so this is the safest option here.
- `refresh-every` — *default `40`, hidden until enabled*  
  Rounds between each rebuild. Rebuilding is not free, so keep this well above the rate.

**Risky Methods**

- `enable-risky` — *default `false`*  
  Show the methods that claim a position you are not at. They load far more ground than the safe ones, and they are the only things here an anti-cheat has any reason to object to — a rubber-band or a kick is possible, occasionally on a false positive. The options stay hidden until you turn this on.
- `look-around (risky)` — *default `false`, hidden until enabled*  
  Sweep where you're looking. Servers that send chunks in the direction you face will keep feeding you the whole circle instead of one arc. This is just a normal look packet, so it's completely safe.
- `stay-active (risky)` — *default `false`, hidden until enabled*  
  Send an ordinary position update every round even when you haven't moved. Some servers stop streaming to players they think have gone idle; this keeps you counted as active.
- `ground-flip (risky)` — *default `false`, hidden until enabled*  
  Alternate the on-ground flag between rounds. Standing and falling run through different checks on the server, so flipping it makes both re-examine your position. Vanilla sends both states constantly.
- `micro-move (risky)` — *default `false`, hidden until enabled*  
  Add a hair of movement to each position so packets reporting no change aren't discarded. The distance is far too small to look like cheating.
- `height-ping (risky)` — *default `false`, hidden until enabled*  
  Tell the server you're high above the terrain, then correct straight back. From up there nothing blocks its view so it streams chunks to your full distance. This is the strongest method by far — and it's a position you aren't really at, which anti-cheats can read as flying. Expect the odd rubber-band, and a kick on strict servers.
- `above-y` — *default `320`, hidden until enabled*  
  The height to claim. It needs to clear the terrain around you to be worth anything.


### deep-activity++

Everything block-update based, each its own tick: hidden chunk activity below a Y line, raw update positions anywhere, and mining-rate / tunnel alerts.

**General**

- `sensitivity` — *default `3`*  
  How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.
- `custom-thresholds` — *default `false`*  
  Set each threshold by hand instead of letting the sensitivity slider work them out. Leave this off unless you want to fine-tune one particular detection.
- `y-level` — *default `0`*  
  Only count block activity below this Y.
- `ignore-self` — *default `true`*  
  Ignore block changes you cause yourself, so your own mining doesn't flag the chunk you're standing in.
- `self-radius` — *default `6`, hidden until enabled*  
  Block changes closer than this to you count as yours and are ignored.
- `scan-zone-size` — *default `1`*  
  Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.
- `min-updates` — *default `3`, hidden until enabled*  
  Block updates in a chunk (below the Y level) before it's highlighted.
- `dropped-items` — *default `true`*  
  A newly-appeared dropped item/block below the Y level flags the chunk instantly, ignoring the minimum.
- `ignore-after-load` — *default `3`*  
  Ignore a chunk's block updates for this long after it loads — skips the anti-xray reveal burst.
- `updates-per-second-cap` — *default `200`*  
  Max block updates counted per chunk per second — high so real farms count fully; only absurd bursts get capped.
- `forget-when-close` — *default `48`*  
  Clear a chunk's highlight once you're this close (it's rendered now).
- `farm-guard` — *default `true`*  
  Stop processing a zone once it floods you with updates. Farms fire thousands of block changes a second, and handling every one is what makes the game stutter near them. The zone is still flagged — it just stops being re-counted.
- `updates-per-zone-cap` — *default `400`, hidden until enabled*  
  Updates from one zone in a single tick before it's treated as a farm and skipped.
- `popup` — *default `false`*  
  Show a popup on screen. Off by default — the chat line is usually enough and popups get intrusive when several chunks trip at once.
- `chat` — *default `true`*  
  Print a message in chat.
- `sound` — *default `true`*  
  Play a sound alert.

**Amethyst**

- `amethyst-highlight` — *default `false`*  
  Colour amethyst by how far it has grown. A geode nobody touches fills up with fully-grown clusters; one being harvested is stripped back to bare budding blocks, because the grown ones keep getting taken. That difference tells you somebody is working it.
- `show-budding` — *default `true`, hidden until enabled*  
  Include budding amethyst — the block crystals grow out of. It cannot be mined, so a geode down to bare budding blocks is a farmed one.
- `show-blocks` — *default `false`, hidden until enabled*  
  Include plain amethyst blocks too. Geodes are full of them, so this gets noisy.
- `amethyst-range` — *default `48`, hidden until enabled*  
  How far out to look for amethyst, in blocks.
- `amethyst-scan-ticks` — *default `40`, hidden until enabled*  
  Ticks between amethyst sweeps.
- `budding-color` — *default `new SettingColor(255, 90, 220, 220`, hidden until enabled*  
  Colour for budding amethyst.
- `small-color` — *default `new SettingColor(120, 80, 200, 200`, hidden until enabled*  
  Colour for a small bud — just started.
- `medium-color` — *default `new SettingColor(160, 90, 230, 210`, hidden until enabled*  
  Colour for a medium bud.
- `large-color` — *default `new SettingColor(200, 110, 245, 220`, hidden until enabled*  
  Colour for a large bud — nearly grown.
- `grown-color` — *default `new SettingColor(240, 150, 255, 235`, hidden until enabled*  
  Colour for a fully-grown cluster, the one worth breaking.
- `block-color` — *default `new SettingColor(150, 110, 190, 120`, hidden until enabled*  
  Colour for plain amethyst blocks.

**Mobs**

- `mob-cluster` — *default `true`*  
  Flag places where the same mob piles up far past natural numbers — an enderman swarm in the End, or a packed mob farm anywhere else. Works even when the farm itself is out of sight, because the mobs are still sent to you.
- `min-mobs` — *default `14`, hidden until enabled*  
  How many of one kind must be packed together before it counts.
- `cluster-radius` — *default `12`, hidden until enabled*  
  How close together they must be, in blocks.
- `mobs-at-any-height` — *default `true`, hidden until enabled*  
  Check for mob clusters at any height. Enderman farms in the End sit high up, so the usual below-Y rule would miss them.
- `mob-scan-ticks` — *default `40`, hidden until enabled*  
  Ticks between mob sweeps.

**Dropped Items**

- `drop-burst` — *default `15`*  
  How many items have to appear at once before it counts. A player breaking a stash drops a pile in one go; one or two items on their own are usually just a mob death or something that came in with the chunk.
- `drop-window-ticks` — *default `20`, hidden until enabled*  
  How long that pile has to land in, in ticks.
- `ignore-on-chunk-load` — *default `true`, hidden until enabled*  
  Ignore items that show up in the same moment a chunk loads. Those were already lying there — the server is just telling you about them now, and without this every chunk full of old drops looks like fresh activity.
- `load-grace-ticks` — *default `40`, hidden until enabled*  
  How long after a chunk arrives to keep ignoring its items.

**Lock**

- `base-lock` — *default `false`*  
  Once a zone has flagged several times it's almost certainly a base, not a passer-by. Lock it so it stays highlighted even after the activity stops and you move away.
- `lock-after` — *default `3`, hidden until enabled*  
  How many separate flags a zone needs before it locks.
- `locked-color` — *default `new SettingColor(255, 60, 60, 90`, hidden until enabled*  
  Colour used for locked zones.

**Raw Positions**

- `raw-positions` — *default `false`*  
  Box every individual block-update position (any Y), fading out over time. Shows distant players building/mining live.
- `raw-below-y-only` — *default `true`, hidden until enabled*  
  Only box raw positions that are below the Y level above. Turn off to see updates at any height.
- `fade-ticks` — *default `100`, hidden until enabled*  
  How long each position stays visible, in ticks.
- `update-box-color` — *default `new SettingColor(0, 255, 255, 200`, hidden until enabled*  
  Colour of the raw update boxes.

**Mining Alerts**

- `mining-alerts` — *default `false`*  
  Warn in chat when someone else is breaking blocks fast nearby.
- `breaks-per-window` — *default `6`, hidden until enabled*  
  How many blocks must break inside the window to warn.
- `mining-window-ticks` — *default `40`, hidden until enabled*  
  Length of the counting window, in ticks.
- `min-distance` — *default `6`, hidden until enabled*  
  Ignore breaks closer than this to you (your own mining).
- `mining-below-y-only` — *default `true`, hidden until enabled*  
  Only count breaks that happen below the Y level above. Without this, someone clearing trees on the surface trips the mining warning.
- `tunnel-shape` — *default `false`, hidden until enabled*  
  Only warn when the breaks form a long, narrow, flat run (a dug tunnel) rather than scattered digging or a vertical shaft.
- `min-tunnel-length` — *default `8`, hidden until enabled*  
  How long the run must be to count as a tunnel.

**Render**

- `render-distance` — *default `256`*  
  How far away (in blocks) things are still drawn.
- `line-color` — *default `new SettingColor(255, 60, 60, 220`*  
  Colour of the box outline.
- `fill-color` — *default `new SettingColor(255, 60, 60, 45`*  
  Colour of the filled part of the box.


### find-log++

Collects every find from every detection module into one list with coordinates, sorted by distance or by when it happened.

**General**

- `sort` — *default `Sort.Distance`*  
  Closest first when you are deciding where to walk, newest first when you are watching things happen.
- `rows` — *default `10`*  
  How many finds to list at once.
- `keep-for` — *default `30`*  
  Drop a find from the list after this many minutes. Set it high if you want a record of a whole session.
- `max-distance` — *default `0`*  
  Ignore finds further away than this, in blocks. 0 keeps everything however far it was.

**Panel**

- `panel` — *default `true`*  
  Show the list on screen.
- `x` — *default `6`, hidden until enabled*  
  Distance from the left of the screen.
- `y` — *default `200`, hidden until enabled*  
  Distance from the top of the screen.
- `show-source` — *default `true`, hidden until enabled*  
  Put the module that found it in front of each row.
- `show-age` — *default `true`, hidden until enabled*  
  Show how long ago each find happened.
- `background` — *default `new SettingColor(0, 0, 0, 140`, hidden until enabled*  
  Colour behind the list.
- `text-color` — *default `new SettingColor(190, 235, 255, 255`, hidden until enabled*  
  Colour of the rows.

**In World**

- `markers` — *default `false`*  
  Box every logged find in the world, so a whole session's discoveries stay visible at once even after each module has forgotten its own.
- `beams` — *default `false`, hidden until enabled*  
  Shoot a beam up from each one so you can see them over terrain.
- `marker-color` — *default `new SettingColor(120, 220, 255, 90`, hidden until enabled*  
  Colour of those markers.


### geode-finder++

Marks amethyst so you can tell a farmed geode from an untouched one — colour each crystal by how grown it is, or box the whole geode.

**General**

- `chunk-delay` — *default `0.0`*  
  Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.
- `mode` — *default `Mode.Crystals`*  
  Crystals colours every cluster by how grown it is, so you can read whether a geode is being harvested. Geode boxes the whole structure once it looks suspicious. Both does each at the same time.
- `chunk-range` — *default `8`*  
  How far out to keep showing geodes, in chunks.
- `scan-rate` — *default `4`*  
  How many newly loaded chunks to check each tick. Lower this if the game stutters while flying.
- `chat` — *default `false`*  
  Print a message the first time each geode is found.

**Crystals**

- `budding` — *default `true`*  
  Show budding amethyst — the block the crystals grow out of. It can't be mined, so a geode stripped down to bare budding blocks is one somebody farms.
- `amethyst-blocks` — *default `false`*  
  Show plain amethyst blocks too. Geodes are full of them, so this gets noisy.
- `budding-color` — *default `new SettingColor(255, 90, 220, 220`, hidden until enabled*  
  Colour for budding amethyst.
- `small-color` — *default `new SettingColor(120, 80, 200, 200`*  
  Colour for a small bud — just started growing.
- `medium-color` — *default `new SettingColor(160, 90, 230, 210`*  
  Colour for a medium bud.
- `large-color` — *default `new SettingColor(200, 110, 245, 220`*  
  Colour for a large bud — nearly grown.
- `grown-color` — *default `new SettingColor(240, 150, 255, 235`*  
  Colour for a fully-grown cluster, the one worth breaking.
- `block-color` — *default `new SettingColor(150, 110, 190, 120`, hidden until enabled*  
  Colour for plain amethyst blocks.
- `clean-clusters` — *default `false`*  
  Mark clusters that still have growing buds on them in their own colour. A cluster nobody has touched keeps its part-grown shards; one that gets harvested is snapped off the moment it matures, so leftover partial growth means that spot is being left alone.
- `clean-color` — *default `new SettingColor(80, 255, 180, 230`, hidden until enabled*  
  Colour for those untouched clusters.
- `stripped-geodes` — *default `false`*  
  Mark geodes with no budding amethyst left at all. Budding blocks cannot be mined with anything ordinary, so a geode without them has been deliberately cleared out — that is somebody working it, not natural.
- `stripped-color` — *default `new SettingColor(180, 0, 0, 200`, hidden until enabled*  
  Colour for a geode that has been stripped of its budding blocks.
- `anti-growth-esp-bypass` — *default `true`*  
  Keep showing amethyst the server has stopped sending. Some servers only send it while you are level with it or looking down at it, so it vanishes the moment you rise above. This remembers what was there and keeps drawing it rather than letting it blink out.
- `forget-on-update` — *default `true`*  
  Only drop a crystal or a dripstone tip when the server actually says it changed, instead of after a timer. The server has to send a block update when somebody breaks something — it cannot hide that and still keep your world correct — so a position that has had no update is still there, however long ago you saw it. Covers amethyst and dripstone both, and sends nothing, so there is no risk in it.
- `remember-for` — *default `120`, hidden until enabled*  
  How long to keep showing a find after the server stops sending it, in seconds. Only used when forget-on-update is off — with that on, a find is kept until the server says it changed, which needs no timer at all.

**Dripstone**

- `dripstone` — *default `false`*  
  Also mark pointed dripstone. Servers hide it the same way they hide amethyst, and a farmed cave gets its tips broken off constantly, so what is left tells you the same story. Uses the bypass above, so it stays visible once seen.
- `only-overgrown` — *default `true`, hidden until enabled*  
  Only mark dripstone that has grown longer than it naturally would. A stalactite only lengthens while its chunk stays loaded, so a long one means somebody has been holding that ground open — the same tell as overgrown kelp or sugar cane. Natural caves are full of short dripstone, so leave this on.
- `min-length` — *default `5`, hidden until enabled*  
  How many blocks long a stalactite or stalagmite must be to count. Natural growth rarely passes four without somebody keeping the chunk loaded.
- `dripstone-color` — *default `new SettingColor(200, 160, 120, 200`, hidden until enabled*  
  Colour used for dripstone.

**Geode**

- `pillar` — *default `false`*  
  Shoot a beam up from each geode so you can spot one from across the map instead of only when you are on top of it. Taken from the shared AmethystESP.
- `pillar-color` — *default `new SettingColor(180, 100, 255, 90`, hidden until enabled*  
  Colour of that beam.
- `toast` — *default `false`*  
  Raise a popup in the corner when a geode is found, with the cluster count and an amethyst icon. Quieter than a title across the middle of the screen.
- `group-whole-geodes` — *default `true`*  
  Group connected amethyst into whole geodes rather than counting loose blocks in a chunk. A geode is one connected lump, so this reports it as one find and gets its size right even when it straddles a chunk edge.
- `geode-threshold` — *default `12`, hidden until enabled*  
  How many connected amethyst blocks make a geode worth reporting.
- `scan-min-y` — *default `-58`*  
  Lowest height to scan for amethyst.
- `scan-max-y` — *default `30`*  
  Highest height to scan for amethyst.
- `min-amethyst` — *default `12`*  
  How much amethyst a chunk needs before it counts as a geode at all.
- `only-suspicious` — *default `true`*  
  Only box geodes that look farmed rather than every one you pass. A natural geode is thick with grown clusters; a harvested one is mostly bare budding blocks, because the grown ones keep getting taken.
- `max-grown-percent` — *default `25`, hidden until enabled*  
  Below this share of fully-grown clusters, the geode is treated as harvested. Natural ones sit high; a farmed one is stripped down.
- `underground-only` — *default `false`*  
  Ignore geodes near the surface. Those get found by accident; a deep one somebody has been working is the interesting case.
- `below-y` — *default `20`, hidden until enabled*  
  What counts as underground.
- `tracers` — *default `false`*  
  Draw a line from you to each geode. From the shared AmethystESP, and useful when a geode is behind terrain and the beam alone is hard to place.
- `tracer-color` — *default `new SettingColor(180, 100, 255, 160`, hidden until enabled*  
  Colour of those lines.
- `geode-color` — *default `new SettingColor(255, 60, 255, 90`*  
  Colour of the box drawn around a whole geode.


### hidden-player-detect++

Spots players moving around underground where you can't see them, by picking up the traces their movement leaves behind.

**Inventory**

- `confirm-threshold` — *default `3`*  
  Score needed to confirm a chunk.

**General**

- `chat` — *default `true`*  
  Print a message in chat.
- `color` — *default `new SettingColor(255, 40, 40, 120`*  
  Highlight colour.

**Vanished Staff**

- `containers-opening` — *default `true`*  
  Watch for chests and doors opening near you with nobody visible to have opened them. The server has to send the opening animation so your client can draw it, and it carries the exact position — so an invisible staff member rifling through a chest announces where they are standing.
- `unexplained-sounds` — *default `true`*  
  Watch for player noises — footsteps, doors, containers, breaking blocks — arriving from places where nobody is visible. A spectator makes no sound, but staff watching in survival do, and the sound carries a position even when the player does not.
- `attribute-radius` — *default `24`*  
  How close a visible player has to be for a nearby signal to be put down to them. Anything happening further than this from every player you can see is unexplained, and that is the whole basis of this group.
- `unseen-entities` — *default `false`*  
  Watch the entity numbers the server hands out. They climb steadily, so a jump means entities were created near you that you were never shown — which is what happens when somebody hidden moves into range.
- `min-gap` — *default `40`, hidden until enabled*  
  How big a jump in those numbers has to be before it counts. Small gaps happen naturally from arrows and dropped items.
- `chunks-held-open` — *default `true`*  
  Watch for ground staying loaded with nobody visible near it. A spectator still holds chunks open exactly like a normal player, and that is the one thing vanishing cannot hide — it is the strongest signal here.
- `report-vanished` — *default `true`*  
  Say in chat when one of these trips.


### hole-finder++

Finds shafts someone dug and then plugged behind them, and marks the exact block that caps each one.

**General**

- `chunk-delay` — *default `0.0`*  
  Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.
- `sensitivity` — *default `5`*  
  How many covered holes a chunk needs before they're shown, out of 20. This also sets how deep a shaft has to be: five blocks at 1, twenty at 20. Low catches shallow plugs and single holes, high only reports serious dug-out shafts.
- `chunk-range` — *default `16`*  
  How far out to keep showing holes, in chunks.
- `scan-rate` — *default `4`*  
  How many newly loaded chunks to check each tick. Lower this if the game stutters while flying.
- `max-width` — *default `3`*  
  Widest tunnel to treat as dug rather than natural. 3 covers everything people actually make: 1x1 ladder shafts, 2x1 and 3x1 corridors, and 3x3 rooms. Raise it for wider excavations, lower it to only catch tight shafts.
- `surface-cap-y` — *default `50`*  
  Above this height, a cap with open sky over it still counts — that is what a hole plugged flush with the ground looks like. Below it, a block with air above is just something sitting inside a shaft that is already open, so it is ignored.
- `obscure-fills` — *default `true`*  
  Also count caps made of blocks that never form underground — planks, wool, concrete, terracotta, glass, bricks, copper, quartz and the rest. None of it generates down there, so a single one sealing a shaft is somebody's doing whatever the depth or shape. People plug a hole with whatever is in their hotbar, and it is usually not cobblestone.
- `stairs` — *default `true`*  
  Also catch staircases. They step sideways as they go down, so their walls sit further out than a straight shaft's and would otherwise be missed.
- `chat` — *default `false`*  
  Print a message the first time each chunk's holes are found.

**Sealed Pockets**

- `sealed-pockets` — *default `false`*  
  Find air that has been walled in completely — blocks on every side including above and below. Nothing in the world generates like that, so a sealed pocket is somebody hiding something: a buried chest, a covered entrance, or a stash sealed behind stone.
- `max-height` — *default `2`, hidden until enabled*  
  Tallest pocket to count, in blocks. One or two is the giveaway — anything taller is usually just a cave.
- `pocket-min-y` — *default `-64`, hidden until enabled*  
  Ignore pockets above this height. Sealed air near the surface is usually part of a build rather than something hidden.
- `pocket-color` — *default `new SettingColor(255, 220, 0, 220`, hidden until enabled*  
  Colour used for sealed pockets.

**Render**

- `fill-color` — *default `new SettingColor(40, 90, 255, 90`*  
  Colour of the filled faces on each capping block.
- `line-color` — *default `new SettingColor(60, 120, 255, 230`*  
  Colour of the outline on each capping block.
- `shape-mode` — *default `ShapeMode.Both`*  
  Outline only, filled sides only, or both.
- `tracers` — *default `false`*  
  Draw a line from you to each hole.


### hopper-debug++

Finds hoppers and reads the signal strength around them, which gives away sorting systems and hidden storage.

**General**

- `scan-radius` — *default `6`*  
  Chunk scan radius.
- `max-y` — *default `0`*  
  Maximum Y level to scan.
- `min-hoppers` — *default `1`*  
  Minimum hoppers to flag a chunk.
- `chunks-per-tick` — *default `3`*  
  Chunks scanned per tick.
- `under-deepslate` — *default `false`*  
  Only count hoppers with deepslate above.
- `min-deepslate` — *default `2`, hidden until enabled*  
  Minimum deepslate blocks above.
- `signal-detect` — *default `false`*  
  Also watch hopper comparator signals and flag ones that CHANGE (active item flow = a running farm).
- `signal-delta` — *default `1`, hidden until enabled*  
  How much a hopper's signal must change to flag it.

**Render**

- `chunk-y-level` — *default `55.0`*  
  Y level for chunk mark.
- `color` — *default `new SettingColor(255, 140, 0, 120`*  
  Highlight colour.


### light-debug++

Everything to do with light in one place: light sources, where mobs can spawn, and pockets of darkness that shouldn't be there.

**Modes** — Block, Sky, Total

**General**

- `light-type` — *default `LightMode.Block`*  
  Block = placed light sources (torches/lava). Sky = daylight exposure. Total = the higher of the two.
- `min-light` — *default `1`*  
  Only highlight blocks at/above this light level (0 shows everything with any light).
- `light-sources` — *default `false`*  
  Also box blocks that EMIT light (torches, lava, glowstone) for base finding.
- `floor-only` — *default `true`*  
  Only highlight lit air on top of a solid block (lit floors) — much cleaner than filling the whole volume.
- `horizontal-radius` — *default `24`*  
  How far out sideways to scan (blocks).
- `vertical-radius` — *default `8`*  
  How far up/down to scan (blocks).
- `scan-ticks` — *default `20`*  
  Ticks between rescans.

**Render**

- `fill-alpha` — *default `70`*  
  Highlight transparency.
- `shape-mode` — *default `ShapeMode.Both`*  
  How boxes are drawn: outline only, filled sides only, or both.

**Spawn Overlay**

- `spawn-overlay` — *default `false`*  
  Draw a cross on every block a mob could spawn on. Red = spawns any time (no sky light), yellow = only at night.
- `spawn-radius` — *default `12`, hidden until enabled*  
  How far around you to check for spawn spots.
- `spawn-max-light` — *default `0`, hidden until enabled*  
  A block counts as spawnable if its light level is at or below this. 0 = only fully dark blocks (vanilla spawning rule).
- `spawn-boxes` — *default `false`, hidden until enabled*  
  Draw a flat box on each spawnable block instead of a cross (easier to see when spawn-proofing).
- `always-color` — *default `new SettingColor(255, 50, 50, 200`, hidden until enabled*  
  Colour for spots that spawn mobs any time of day.
- `night-color` — *default `new SettingColor(255, 220, 60, 200`, hidden until enabled*  
  Colour for spots that only spawn mobs at night.

**Dark Chunks**

- `dark-chunks` — *default `false`*  
  While you're above the Y line, flag whole chunks below it that contain ANY block light — a carved, lit space underground usually means a base.
- `scan-below-y` — *default `0`, hidden until enabled*  
  Only look at blocks below this Y for hidden light.
- `dark-sensitivity` — *default `1`, hidden until enabled*  
  How faint a light counts. 1 = catch everything, higher = only brighter sources.
- `dark-color` — *default `new SettingColor(255, 160, 0, 200`, hidden until enabled*  
  Colour used for flagged dark chunks.


### loaded-region-finder++

Finds 512x512 regions that have an unusual number of chunks loaded — chunk loaders, big bases and other places the server is working hard.

**General**

- `min-loaded-chunks` — *default `16`*  
  Loaded chunks in a region to flag it.

**Render**

- `box-y` — *default `64`*  
  The Y height to draw the box/marker at.
- `render-distance` — *default `2048`*  
  How far away (in blocks) things are still drawn.
- `line-color` — *default `new SettingColor(0, 255, 0, 200`*  
  Colour of the box outline.


### logout-spots++

Marks where other players log out.

**General**

- `chat` — *default `true`*  
  Print a chat message on a new find.
- `color` — *default `new SettingColor(255, 0, 255, 90`*  
  Highlight colour.


### ocean-monument-finder++

Detects nearby ocean monuments by spotting guardian entities (their only habitat).

**General**

- `scan-radius` — *default `128`*  
  How far out to scan, in chunks.
- `color` — *default `new SettingColor(0, 180, 255, 200`*  
  Highlight colour.


### ore-sim++

Predicts where ores are from the world seed, accurate to vanilla generation. Enter a seed or let it detect one.

**Modes** — ON_LOAD, RECHECK, OFF

**General**

- `mc-version` — *default `OreVersion.CURRENT`*  
  Which Minecraft version's ore generation to simulate. Current = the running game (1.21.x), registry-accurate like Nora/Rejects. Older versions restore the cross-version pick; validate them in singleplayer.
- `use-server-registry` — *default `false`, hidden until enabled*  
  Off (default): rebuild vanilla ore generation locally on the client, so Current stays seed-exact even on servers that don't share worldgen data (DonutSMP etc.). On: read the server's synced registry instead (only useful on modded servers with custom ore placement).
- `auto-seed` — *default `true`*  
  Use the current singleplayer world's seed automatically. Off = type a seed (for servers).
- `seed` — *default `""`, hidden until enabled*  
  World seed (numbers or text). Used when auto-seed is off.
- `chunk-range` — *default `5`*  
  Range of chunks to render around you.
- `air-check-mode` — *default `AirCheck.RECHECK`*  
  Checks for air at simulated ore positions to drop exposed ones.
- `recheck-interval` — *default `200`, hidden until enabled*  
  Ticks between line-of-sight rechecks. On these, a box on a block you can actually see (in your FOV, with an exposed face and no obstruction) that isn't ore gets removed. Mined ores clear every tick regardless. 20 ticks = 1s.
- `baritone` — *default `false`*  
  Expose the simulated ore positions as Baritone mining goals so #mine / Baritone can path to them. Requires Baritone (built into Meteor).
- `glow` — *default `0`*  
  How strongly each find glows through terrain. 0 is off; higher makes the outline brighter and more solid so it stands out at distance.

**Render**

- `color-by-ore` — *default `true`*  
  Color-code each box by ore type (diamond cyan, gold yellow, redstone red, etc.). Off = use the single fill/line colors below for every ore.
- `shape-mode` — *default `ShapeMode.Lines`*  
  Draw outlines, filled sides, or both.
- `fill-opacity` — *default `40`, hidden until enabled*  
  Fill opacity used when color-by-ore is on (and shape-mode includes sides).
- `fill-color` — *default `new SettingColor(255, 255, 255, 40`, hidden until enabled*  
  Box fill color (used when color-by-ore is off).
- `line-color` — *default `new SettingColor(255, 255, 255, 200`, hidden until enabled*  
  Box outline color (used when color-by-ore is off).


### ore-spotter++

X-ray that highlights ores through terrain, kept smooth so it doesn't stutter as you move.

*No settings.*

### particle-esp++

Highlights particles spawning underground while you're above ground (someone is down there).

**Modes** — Custom, ByParticleType

**General**

- `particles-below-y` — *default `0`*  
  Only care about particles that spawn at or below this Y. Default 0 = underground only.
- `only-above-y` — *default `3`*  
  Only run while YOU are above this Y, so your own underground particles don't trigger it. Default 3.
- `lifetime` — *default `200`*  
  How many ticks a spotted particle stays highlighted before fading out.
- `max-tracked` — *default `400`*  
  Safety cap on how many particle spots are kept at once.

**Filters**

- `merge-close` — *default `true`*  
  Group particles that spawn near each other into one box instead of many overlapping ones.
- `merge-radius` — *default `2.0`, hidden until enabled*  
  How close (in blocks) two particles must be to count as the same spot.
- `min-particles` — *default `3`*  
  How many particles must pile up at a spot before it's shown. 1 = show every single one.
- `ignore-near-me` — *default `true`*  
  Ignore particles right next to you (your own blocks/effects).
- `ignore-radius` — *default `8.0`, hidden until enabled*  
  How close counts as 'near me' (blocks).
- `max-distance` — *default `256.0`*  
  Don't track particles further away than this (blocks).

**Render**

- `box` — *default `true`*  
  Draw a box on each particle spot.
- `block-shape` — *default `true`, hidden until enabled*  
  Fill the whole block the particles came from instead of drawing a small box floating at the exact point. A solid block is far easier to pick out through terrain, and it tells you which block to actually go and break.
- `box-size` — *default `0.6`, hidden until enabled*  
  How big each box is, in blocks. Only used when block-shape is off.
- `grow-with-count` — *default `true`, hidden until enabled*  
  Make the box bigger the more particles pile up at that spot. Only used when block-shape is off.
- `shape-mode` — *default `ShapeMode.Both`, hidden until enabled*  
  Outline only, filled sides only, or both.
- `tracers` — *default `false`*  
  Draw a line from you to each particle spot.
- `fade-out` — *default `true`*  
  Fade the highlight as the spot gets older.
- `color-mode` — *default `ColorMode.ByParticleType`*  
  Custom = one colour you pick. By-particle-type = each particle type gets its own colour, guessed from what that particle actually looks like (flame = orange, smoke = grey, etc).
- `color` — *default `new SettingColor(0, 255, 200, 220`, hidden until enabled*  
  The colour used when colour-mode is Custom.
- `real-color-blend` — *default `60`, hidden until enabled*  
  For particles that carry a real colour in the packet (redstone dust, potion effects...), how much of that real colour to use. 100 = the particle's actual colour, 0 = ignore it and use the hand-picked colour for that type. In between = a mix. Types whose real colour is already exactly right (dust, potion effects) always use it fully.
- `fill-alpha` — *default `50`*  
  How see-through the filled part of the box is.

**Notifications**

- `chat` — *default `false`*  
  Print a chat message when a new particle spot is found.
- `notify-cooldown` — *default `60`, hidden until enabled*  
  Seconds before the same area can notify again (stops chat spam).
- `sound` — *default `false`*  
  Play a ping on a new particle spot.


### player-detector++

Popup / sound / chat when another player renders near you.

**General**

- `alert-cooldown` — *default `30`*  
  Don't alert on the same player again for this many seconds. Without it, somebody walking in and out of range — or an entity flickering as you cross a chunk boundary — sets off a fresh alert every time.
- `real-players-only` — *default `true`*  
  Only count entities that also appear in the server's player list. NPCs, shop holograms and other fake players look identical to real ones in the world, and they are the usual reason this module cries wolf.
- `range` — *default `128`*  
  Alert when a player is within this many blocks.
- `popup` — *default `true`*  
  Show an on-screen title popup.
- `chat` — *default `true`*  
  Send a chat message.
- `volume` — *default `1.0`*  
  How loud the alert sound is (0.1 = quiet, 1.0 = full).
- `highlight-chunks` — *default `false`*  
  Box the chunk each nearby player is standing in.
- `fill-chunk` — *default `true`, hidden until enabled*  
  Fill the player's chunk box instead of just outlining it.
- `chunk-color` — *default `new SettingColor(255, 60, 60, 180`, hidden until enabled*  
  Colour of the player's chunk box.
- `sound` — *default `true`*  
  Play an alert sound.


### rare-finder++

Highlights valuable items on the ground or in item frames, and can beam them so you spot them from far off.

**General**

- `find-dropped` — *default `true`*  
  Highlight rare items lying on the ground.
- `find-placed` — *default `true`*  
  Also find rare blocks that have been placed in the world — a beacon someone built, a sponge wall, heads on display, gilded blackstone. These never appear as dropped items, so without this they're invisible.
- `placed-range` — *default `64`, hidden until enabled*  
  How far out to look for placed rare blocks, in blocks.
- `placed-color` — *default `new SettingColor(255, 160, 0, 255`, hidden until enabled*  
  Colour used for placed rare blocks.
- `find-framed` — *default `true`*  
  Highlight rare items displayed in item frames — people put their best gear on show.
- `scan-ticks` — *default `20`*  
  Ticks between sweeps for dropped and framed items.

**Alerts**

- `chat` — *default `true`*  
  Log each new rare item in chat.
- `popup` — *default `false`*  
  Throw a title on screen when one turns up.
- `sound` — *default `false`*  
  Play a sound when one turns up.
- `volume` — *default `2.0`, hidden until enabled*  
  How loud that sound is.

**Render**

- `beacon` — *default `false`*  
  Shoot a beam up from each rare item. Two within five blocks share one beam placed between them, so a pile doesn't become a wall of beams.
- `beacon-color` — *default `new SettingColor(255, 215, 0, 180`, hidden until enabled*  
  Colour of those beams.
- `item-color` — *default `new SettingColor(0, 255, 170, 255`, hidden until enabled*  
  Colour used for items on the ground.
- `frame-color` — *default `new SettingColor(200, 90, 255, 255`, hidden until enabled*  
  Colour used for items in frames.
- `shape-mode` — *default `ShapeMode.Both`*  
  Outline only, filled sides only, or both.
- `tracers` — *default `false`*  
  Draw a line from you to each find.


### region-map++

Shows the server's region grid - every region numbered and shaded by which datacentre it runs on, with a marker for the one you're in.

**General**

- `x` — *default `12`*  
  Distance from the left of the screen.
- `y` — *default `148`*  
  Distance from the top of the screen.
- `cell-size` — *default `10`*  
  How big each region square is, in pixels.
- `cell-gap` — *default `1`*  
  Gap between squares, in pixels.

**Show**

- `region-numbers` — *default `true`*  
  Print each region's number inside its square.
- `legend` — *default `true`*  
  Show the colour key listing each datacentre.
- `player-marker` — *default `true`*  
  Mark where you are on the grid.
- `current-region` — *default `true`*  
  Write the region you're standing in, and its datacentre, under the map.
- `highlight-current` — *default `true`*  
  Outline the square you're currently in.

**Colors**

- `transparency` — *default `210`*  
  How solid the region squares are.
- `background` — *default `new SettingColor(0, 0, 0, 150`*  
  Colour behind the map.
- `player-color` — *default `new SettingColor(255, 255, 255, 255`, hidden until enabled*  
  Colour of your marker.
- `text-color` — *default `new SettingColor(255, 255, 255, 255`*  
  Colour of the numbers and labels.


### rtp-finder++

Teleports around with /rtp hunting for rare loot, marks anything it finds with a beacon, and stops itself once it does.

**Command**

- `rtp-command` — *default `"rtp"`*  
  The command to send, without the slash. Usually just rtp.
- `min-wait-seconds` — *default `20`*  
  Shortest gap between attempts. The real gap is picked at random between this and the maximum, so the timing never looks mechanical.
- `max-wait-seconds` — *default `45`*  
  Longest gap between attempts.
- `load-timeout-seconds` — *default `20`*  
  Give up waiting for chunks to finish loading after this long and search anyway.

**What To Look For**

- `check-dropped` — *default `true`*  
  Count rare items lying on the ground.
- `check-placed` — *default `true`*  
  Count rare blocks that have been placed in the world — a built beacon, a sponge wall, heads on display. These never drop as items, so without this a base full of them looks empty.
- `check-item-frames` — *default `true`*  
  Look inside item frames too — people display their best gear.
- `storage-cluster` — *default `false`*  
  Stop when a lot of containers sit close together. This counts containers rather than reading what's inside them, because a server won't tell you a chest's contents until you open it — a dense pile is a stash regardless.
- `min-storage` — *default `12`, hidden until enabled*  
  How many containers must be nearby to count as a stash.
- `frames-and-stands` — *default `false`*  
  Also stop on clusters of the little things players leave behind — item frames, armour stands and similar.
- `min-frames-stands` — *default `6`, hidden until enabled*  
  How many of those must be nearby to count.
- `search-radius` — *default `96`*  
  How far around you to search after each teleport, in blocks.

**After A Find**

- `set-home` — *default `false`*  
  Save a home at the spot so you can come back. Off by default because it overwrites whichever home slot you pick.
- `home-number` — *default `1`, hidden until enabled*  
  Which home slot to overwrite. It runs delhome on this slot first, then sethome.
- `log-out` — *default `false`*  
  Disconnect afterwards. With set-home on it waits out one more random gap after saving; with set-home off it just waits that gap and then leaves.

**Pacing**

- `wait-longer-on-lag` — *default `true`*  
  When the region you land in is lagging, add extra time before the next teleport rather than hammering a struggling server.
- `extra-wait-seconds` — *default `15`, hidden until enabled*  
  How much time to add when that happens.
- `low-tps-below` — *default `15.0`, hidden until enabled*  
  What counts as lagging.

**Alerts**

- `popup` — *default `true`*  
  Throw a title across the screen on a find.
- `sound` — *default `true`*  
  Play a loud alert on a find, so you hear it from across the room.
- `volume` — *default `5.0`, hidden until enabled*  
  How loud that alert is.
- `chat` — *default `true`*  
  Log what happened in chat: each teleport, each wait, and the find itself.
- `beacon` — *default `true`*  
  Shoot a beam up from the find so you can walk back to it.
- `beacon-color` — *default `new SettingColor(255, 215, 0, 200`, hidden until enabled*  
  Colour of that beam.


### search-map++

Remembers which ground you have already swept and points you at the ground you have not, so you stop covering the same area twice.

**General**

- `pause-when-still` — *default `false`*  
  Stop recording while you are standing still, so sitting in one spot does not keep marking the same ground as freshly searched.
- `forget-after` — *default `0`*  
  Treat ground as unsearched again after this many minutes. Bases get built, so old sweeps go stale — 0 means never forget.

**Where To Go**

- `suggest-direction` — *default `true`*  
  Work out which compass direction has the most unsearched ground within range and say so. This is the part that stops you covering the same ground twice.
- `look-ahead` — *default `24`, hidden until enabled*  
  How far out to weigh each direction, in chunks. Larger looks at the bigger picture; smaller reacts to the gap right in front of you.
- `draw-arrow` — *default `true`, hidden until enabled*  
  Draw a marker on the ground pointing the way it suggests.

**Overlay**

- `overlay` — *default `true`*  
  Draw the searched area as a small map on screen.
- `map-radius` — *default `24`, hidden until enabled*  
  How many chunks either side of you the map covers.
- `cell-size` — *default `3`, hidden until enabled*  
  How many pixels wide each chunk is on the map.
- `x` — *default `6`, hidden until enabled*  
  Distance from the left of the screen.
- `y` — *default `420`, hidden until enabled*  
  Distance from the top of the screen.
- `stats` — *default `true`, hidden until enabled*  
  Write how much ground you have covered under the map.
- `covered-color` — *default `new SettingColor(60, 200, 120, 150`, hidden until enabled*  
  Colour for ground you have already been over.
- `gap-color` — *default `new SettingColor(40, 40, 40, 120`, hidden until enabled*  
  Colour for ground you have not.
- `player-color` — *default `new SettingColor(255, 255, 255, 255`, hidden until enabled*  
  Colour of your marker on the map.

**In World**

- `frontier` — *default `false`*  
  Outline the edge where searched ground meets unsearched ground, so you can fly along it and sweep cleanly instead of zig-zagging.
- `frontier-y` — *default `120`, hidden until enabled*  
  Height to draw that edge at.
- `frontier-color` — *default `new SettingColor(255, 200, 40, 140`, hidden until enabled*  
  Colour of the edge.


### signal-scanner++

Hunts for the redstone and wiring signatures that give away hidden bases and farms.

**General**

- `threshold` — *default `8`*  
  Redstone components per chunk to flag.
- `hopper-esp` — *default `false`*  
  Also box individual hoppers/droppers/dispensers (merged from HopperESP).
- `light-anomalies` — *default `true`*  
  Also add score for light-emitting blocks below Y0 (hidden underground lighting = base).

**Render**

- `box-y` — *default `70`*  
  The Y height to draw the box/marker at.
- `render-distance` — *default `512`*  
  How far away (in blocks) things are still drawn.
- `line-color` — *default `new SettingColor(255, 0, 0, 230`*  
  Colour of the box outline.

**Connected Scan**

- `connected-scan` — *default `false`*  
  Live-scan the blocks below a Y line for clusters of connected redstone/rails/hoppers (contraptions you haven\'t rendered the chunk-data for yet).
- `conn-scan-radius` — *default `6`, hidden until enabled*  
  Chunk radius for the live connected-scan.
- `conn-max-y` — *default `0`, hidden until enabled*  
  Only scan blocks at or below this Y.
- `conn-min-connected` — *default `2`, hidden until enabled*  
  Connected blocks a chunk needs to flag.
- `conn-rails` — *default `true`, hidden until enabled*  
  Count rails.
- `conn-redstone` — *default `true`, hidden until enabled*  
  Count redstone components (wire/repeaters/pistons…).
- `conn-hoppers` — *default `true`, hidden until enabled*  
  Count hoppers.
- `connected-scan-color` — *default `new SettingColor(0, 200, 255, 200`, hidden until enabled*  
  Colour for connected-scan chunks.


### spawn-cluster-finder++

Boxes chunks with unusually dense live hostile-mob counts.

**General**

- `min-spawns` — *default `3`*  
  Minimum hostile mobs in a chunk at once to flag it.
- `rescan-ticks` — *default `40`*  
  Ticks between full rescans.
- `color` — *default `new SettingColor(255, 0, 0, 90`*  
  Highlight colour.


### spawner-finder++

Highlights mob and trial spawners through terrain, at any height.

**General**

- `mob-spawners` — *default `true`*  
  Classic mob spawners (dungeons, XP farms).
- `trial-spawners` — *default `true`*  
  1.21 trial spawners (trial chambers).
- `below-y-only` — *default `false`*  
  Only report spawners under the height below. Handy when you're hunting stashes and don't care about surface dungeons.
- `below-y` — *default `0`, hidden until enabled*  
  The height that cut-off uses.
- `chat` — *default `true`*  
  Print each new spawner to chat.

**Render**

- `sound` — *default `false`*  
  Play a ping when a new spawner is found.
- `render-distance` — *default `256`*  
  How far away (in blocks) things are still drawn.
- `beam` — *default `false`*  
  Draw a tall vertical beam above each spawner.
- `nametags` — *default `false`*  
  Distance label over each spawner.
- `chunk-outline` — *default `false`*  
  Outline the chunk each spawner is in.
- `tracers` — *default `true`*  
  Draw a line from you to each highlighted thing.
- `shape-mode` — *default `ShapeMode.Both`*  
  How boxes are drawn: outline only, filled sides only, or both.
- `fill-color` — *default `new SettingColor(255, 130, 0, 45`*  
  Colour of the filled part of the box.
- `line-color` — *default `new SettingColor(255, 130, 0, 255`*  
  Colour of the box outline.
- `tracer-color` — *default `new SettingColor(255, 130, 0, 160`, hidden until enabled*  
  Colour of the tracer lines.


### staff-detector++

Alerts (popup/chat/sound) when likely staff go online or offline.

**General**

- `spectator` — *default `true`*  
  Flag tab-list players in spectator mode (common for staff watching you).
- `creative` — *default `true`*  
  Flag tab-list players in creative mode.
- `detect-roles` — *default `true`*  
  Flag anyone whose tab-list name shows a staff tag like (Admin)/(Mod)/[Staff]/(Dev).
- `detect-prefixes` — *default `true`*  
  Turn prefix/name detection on or off. Uses the blacklist and whitelist below.
- `flag-odd-names` — *default `false`, hidden until enabled*  
  On top of the blacklist, also flag any name with a space or a character a normal Minecraft name can't have (coloured/bracketed staff names). Still respects the whitelist.

**Alerts**

- `online-alert` — *default `true`*  
  Alert when a flagged player comes online / appears.
- `offline-alert` — *default `true`*  
  Alert when a flagged player goes offline / leaves.
- `popup` — *default `false`*  
  Show a title/subtitle popup on screen. Off by default — chat is less intrusive when several staff log on at once.
- `chat` — *default `true`*  
  Print the alert in chat.
- `sound` — *default `false`*  
  Play a sound on alert.

**Panel**

- `panel` — *default `true`*  
  Show a small on-screen list of the flagged staff who are currently online.
- `panel-x` — *default `4`, hidden until enabled*  
  Panel position from the left of the screen.
- `panel-y` — *default `80`, hidden until enabled*  
  Panel position from the top of the screen.
- `panel-text-color` — *default `new SettingColor(255, 80, 80, 255`, hidden until enabled*  
  Colour of the names in the panel.


### stronghold-finder++

Works out where the stronghold is from two ender eye throws and marks the spot.

**General**

- `auto-detect-throws` — *default `true`*  
  Pick up eye throws automatically as you make them. Turn off to only record throws with the bind.
- `min-separation` — *default `200`*  
  How far apart two throws must be (in blocks) before a guess is trusted. Throws made too close together give wildly inaccurate results.
- `chat` — *default `true`*  
  Print the estimated coordinates in chat.

**Render**

- `render` — *default `true`*  
  Draw a marker at the estimated stronghold.
- `marker-y` — *default `64`, hidden until enabled*  
  Height to draw the marker at.
- `color` — *default `new SettingColor(0, 255, 200, 220`, hidden until enabled*  
  Colour of the marker.
- `tracer` — *default `true`, hidden until enabled*  
  Draw a line from you to the estimate.


### sus-chunk-finder++

Finds chunks where plants and blocks have overgrown far past natural amounts — a sign someone has been living or AFKing nearby keeping the area loaded. Not a farm finder.

**Modes** — All, Growables, Containers, Custom

**Types**

- `chunk-delay` — *default `0.0`*  
  Hold each newly arrived chunk this long before scanning it. Flying somewhere new delivers chunks faster than they can be checked, and the queue starts dropping the oldest ones before they are ever looked at. A short delay lets the backlog clear first, so nothing is thrown away unscanned. 0 scans as fast as it can.
- `scan-zone-size` — *default `1`*  
  Group chunks into zones this many across before deciding anything. 1 keeps every chunk separate, which is the most precise. Higher merges neighbours into one find, so a base spread over several chunks reports once instead of lighting up a whole grid. 6 is the widest, which covers a large base without swallowing unrelated ground.
- `custom-thresholds` — *default `false`*  
  Set each threshold by hand instead of letting the sensitivity slider work them out. Leave this off unless you want to fine-tune one particular detection.
- `types-preset` — *default `TypePreset.Custom`*  
  Quick way to pick what counts as suspicious. All = every type below. Growables = only plants that overgrow when a chunk stays loaded. Containers = storage and player-placed blocks. Custom = use the individual tickboxes.
- `scan-rate` — *default `4`*  
  How many newly loaded chunks to analyse each tick. Lower this if the game stutters while flying or after an RTP; raise it to find things sooner.
- `kelp` — *default `true`, hidden until enabled*  
  Flag chunks with lots of kelp — usually an underwater kelp farm.
- `kelp-at-full-height` — *default `true`, hidden until enabled*  
  Flag kelp only when its columns have grown all the way to the ocean surface. Wild ocean kelp sits at random heights; a chunk kept loaded by a nearby base grows it all to max over time. The exception-amount is how many columns are allowed to still be short.
- `kelp-surface-y` — *default `62`, hidden until enabled*  
  Y a kelp column's top must reach to count as grown-to-max (ocean surface is 62).
- `natural-tolerance` — *default `2`*  
  Extra tolerance (added on top of sensitivity) for naturally-occurring types (kelp, bamboo, vines, cave growth, amethyst, etc.) so wild growth isn't flagged. Global like sensitivity, applied per-type at flag time: an excepted type won't trip a chunk on its own, but any OTHER type over its threshold still will (e.g. tolerable bamboo + a real kelp farm still flags on kelp).
- `vines` — *default `true`, hidden until enabled*  
  Flag chunks with long vine growth — often a hidden vine/XP farm.
- `cocoa` — *default `true`, hidden until enabled*  
  Flag chunks with many cocoa pods — a cocoa bean farm.
- `cave-vines` — *default `true`, hidden until enabled*  
  Flag chunks with lots of glow-berry cave vines — a cave-vine farm.
- `amethyst` — *default `true`, hidden until enabled*  
  Flag chunks with lots of amethyst — someone farming a geode.
- `rotated-deepslate` — *default `true`, hidden until enabled*  
  Deepslate placed on a horizontal axis — natural deepslate is always vertical, so this means someone placed it.
- `bamboo` — *default `true`, hidden until enabled*  
  Flag chunks with tall/dense bamboo — a bamboo farm.
- `bee-nest` — *default `true`, hidden until enabled*  
  Flag chunks with several bee nests/hives grouped together — a bee/honey farm.
- `dripstone` — *default `true`, hidden until enabled*  
  Pointed dripstone / dripstone blocks — dripstone farms.
- `sculk` — *default `true`, hidden until enabled*  
  Sculk sensors/catalysts/shriekers — deep-dark harvesting or XP setups.
- `sugar-cane` — *default `true`, hidden until enabled*  
  Flag chunks with lots of sugar cane — a sugar-cane farm.
- `containers` — *default `true`, hidden until enabled*  
  Chests/barrels/shulkers/furnaces packed into a chunk (storage density).
- `obsidian` — *default `false`, hidden until enabled*  
  Obsidian (bases/anchors/portals).
- `moss` — *default `true`, hidden until enabled*  
  Moss blocks/carpet (lush-cave harvest / bonemeal farms).
- `azalea` — *default `true`, hidden until enabled*  
  Azalea / flowering azalea (lush caves above ground = base garden).
- `glow-lichen` — *default `false`, hidden until enabled*  
  Flag chunks with lots of glow lichen — harvested lush caves / a base garden.
- `spore-blossom` — *default `true`, hidden until enabled*  
  Flag chunks with spore blossoms — a decorated lush-cave base above ground.
- `big-dripleaf` — *default `true`, hidden until enabled*  
  Flag chunks with big dripleaf plants — a lush-cave farm/base.
- `glow-ink` — *default `true`, hidden until enabled*  
  Dropped glow ink sacs piling up (glow squid farm).
- `egg` — *default `true`, hidden until enabled*  
  Dropped eggs (chicken farm) - uses half the sensitivity (stronger signal).
- `turtle-scute` — *default `true`, hidden until enabled*  
  Dropped turtle scutes (turtle farm).
- `armadillo-scute` — *default `true`, hidden until enabled*  
  Dropped armadillo scutes (armadillo farm).
- `cactus` — *default `true`, hidden until enabled*  
  Cactus grown to full height — like sugar cane, it only stacks up when the chunk stays loaded, so tall cactus means someone is active nearby.
- `sweet-berries` — *default `true`, hidden until enabled*  
  Flag chunks where sweet-berry bushes have overgrown from long player presence.
- `sensitivity` — *default `6`*  
  How many separate detections a place needs before it gets flagged, out of 20. 1 means a single hit is enough — noisy but misses nothing. 10 means it wants ten before it says anything. This is the only number most people need to touch.
- `covered-holes` — *default `false`*  
  Count plugged-up shafts as one of the signals for a suspicious chunk. This is a lighter, chunk-level version of hole-finder++ — that module marks each hole exactly, this one just adds them to the evidence here. Both can run at once.
- `range` — *default `8`*  
  How far out to look for and show flagged chunks, measured in chunks around you. 0 = only the chunk you stand in, 8 = a 17x17 chunk area. Bigger = see more but heavier.
- `chat` — *default `false`*  
  Print a chat message when a new chunk is flagged.

**Player Traces**

- `active-chunk` — *default `false`*  
  Flag chunks somebody else is holding open. A chunk read from disk takes real time to hand over; one already in memory for a player comes back almost instantly, so a run of instant arrivals means someone is keeping it live. Available on its own as active-chunk-detector++, which reads the chunk's inhabited clock as well and gives you far more detail.
- `instant-threshold` — *default `3`, hidden until enabled*  
  A chunk arriving within this many milliseconds of the one before counts as instant.
- `instant-run` — *default `20`, hidden until enabled*  
  How many instant arrivals in a row before the chunk is flagged.
- `trace-color` — *default `new SettingColor(90, 0, 160, 90`, hidden until enabled*  
  Colour used for chunks flagged this way.

**Indirect (packet-based)**

- `long-loaded-chunks` — *default `false`*  
  Flag chunks the server has kept loaded/inhabited for a long time (a base players stay near). Uses the chunk's inhabited-time counter.
- `weighted-scoring` — *default `false`*  
  Their ChunkScanner weighted scoring, added on top: vine-length + amethyst-cluster-size + full beehives combined into one score.
- `weighted-min-score` — *default `6`, hidden until enabled*  
  Combined scanner score to flag a chunk.
- `inhabited-minutes` — *default `180`, hidden until enabled*  
  Minimum inhabited minutes to flag a chunk (180 = three hours).
- `min-column-height` — *default `25`, hidden until enabled*  
  A vine/cave-vine/bamboo/sugar-cane/kelp column grown at least this tall flags the chunk. Runs alongside the per-type counts.
- `extreme-column-height` — *default `100`, hidden until enabled*  
  A column this long is 'extreme' overgrowth — the chunk is drawn in the extreme colour instead.
- `overgrowth-score` — *default `true`*  
  Also flag on a weighted overgrowth score (vines x0.5 + dripstone x0.75), not just column length or per-type counts.
- `base-lock` — *default `true`*  
  A chunk flagged this many times gets locked as a confirmed base and stays highlighted (in the locked colour) even if the growth stops matching.
- `lock-after` — *default `3`, hidden until enabled*  
  Times a chunk must be flagged before it locks as a base.
- `amethyst-from-packets` — *default `false`*  
  Also catch amethyst from live block-update packets, which reveals it even in chunks you never fully scan.
- `amethyst-packet-threshold` — *default `4`, hidden until enabled*  
  How many amethyst block-updates in a chunk before it flags.
- `score-unseen-chunks` — *default `false`*  
  Also score chunks from entity-spawn / sound / chunk-load packets, so a chunk can be flagged even when you can't see its blocks. Runs alongside the block counting.
- `entity-score` — *default `2`, hidden until enabled*  
  Points added per entity-spawn packet.
- `sound-score` — *default `1`, hidden until enabled*  
  Points added per sound packet.
- `score-decay-ticks` — *default `200`, hidden until enabled*  
  How often scores drop by 1 (0 = never).
- `only-unseen-chunks` — *default `true`, hidden until enabled*  
  Only score chunks that aren't loaded on your client. The point of indirect detection is finding activity you can't see, so chunks already rendering around you are skipped.
- `indirect-beam` — *default `true`, hidden until enabled*  
  Draw a vertical beam on chunks flagged this way, so you can spot them from a distance.
- `indirect-floor` — *default `false`, hidden until enabled*  
  Shade the whole chunk footprint on chunks flagged this way.
- `indirect-beam-color` — *default `new SettingColor(255, 130, 0, 180`, hidden until enabled*  
  Colour of the beam.
- `indirect-floor-color` — *default `new SettingColor(255, 130, 0, 45`, hidden until enabled*  
  Colour of the chunk shading.
- `flag-score` — *default `10`, hidden until enabled*  
  Score a chunk needs before it's flagged this way.

**Amethyst**

- `amethyst-min-clusters` — *default `8`, hidden until enabled*  
  How many amethyst blocks a chunk needs to flag on amethyst (used instead of the global sensitivity for this one type).
- `amethyst-include-budding` — *default `true`, hidden until enabled*  
  Count budding amethyst (the block that grows the crystals).
- `amethyst-include-block` — *default `false`, hidden until enabled*  
  Count plain amethyst blocks too (noisier — geodes are full of them).
- `amethyst-grown-only` — *default `false`, hidden until enabled*  
  Only count fully-grown clusters, ignoring the small/medium/large buds.
- `amethyst-require-deep` — *default `false`, hidden until enabled*  
  Only count amethyst below a Y line (surface geodes are natural; deep ones are usually farmed).
- `amethyst-max-y` — *default `0`, hidden until enabled*  
  Only count amethyst at or below this Y.
- `whole-geode-scan` — *default `false`*  
  Group connected amethyst into whole geodes instead of only counting blocks. Uses the same scanner as geode-finder++, so a chunk flags on the size of the geode in it rather than on how much amethyst happens to be scattered about.
- `geode-threshold` — *default `12`, hidden until enabled*  
  How many connected amethyst blocks make a geode worth flagging the chunk for.

**Render**

- `box-y` — *default `63`*  
  Height to draw the chunk box at. Defaults to sea level so boxes sit where you can actually see them.
- `shape-mode` — *default `ShapeMode.Both`*  
  How the boxes are drawn: outline only, filled sides only, or both.
- `fill-color` — *default `new SettingColor(255, 200, 0, 40`*  
  The colour of the filled/shaded part of the box.
- `tracers` — *default `false`*  
  Draw a line from you to each flagged chunk.
- `amethyst-color` — *default `new SettingColor(190, 120, 255, 90`*  
  Colour used for chunks flagged because of amethyst, so a geode being farmed stands out from the other finds.
- `extreme-color` — *default `new SettingColor(255, 40, 40, 200`, hidden until enabled*  
  Colour for chunks with extreme overgrowth.
- `locked-color` — *default `new SettingColor(255, 0, 255, 200`, hidden until enabled*  
  Colour for chunks locked as confirmed bases.
- `line-color` — *default `new SettingColor(255, 200, 0, 220`*  
  The colour of the box outline.


### tunnel-finder++

Finds long straight tunnels players have dug, including ones far underground you'd never stumble across.

**General**

- `1x1` — *default `true`*  
  Crawl tunnels, one block wide and one high. The cheapest way to cover distance underground, so people digging long-haul routes use them and they are easy to miss.
- `1x2` — *default `true`*  
  The ordinary walking tunnel, one wide and two high. By far the most common thing you will find.
- `2x2` — *default `true`*  
  Two wide and two high — a main corridor rather than a branch, so it usually leads somewhere worth following.
- `3x3` — *default `true`*  
  Three by three. Nobody digs this by hand for fun, so it means a highway, a nether route or something built to move a lot of material.
- `min-length` — *default `8`*  
  Minimum straight air-corridor length to flag as a tunnel.
- `max-y` — *default `40`*  
  Only scan below this Y (surface has too many false positives).

**Render**

- `render-distance` — *default `256`*  
  How far away (in blocks) things are still drawn.
- `line-color` — *default `new SettingColor(0, 200, 255, 220`*  
  Colour of the box outline.


### voice-chat-sniffer++

Highlights the chunk of a deep voice-chat transmitter (passive, no audio).

**General**

- `channel-namespace` — *default `"voicechat"`*  
  Only react to custom payloads in this namespace.
- `max-y` — *default `0`*  
  Only highlight chunks of voices below this Y.
- `forget-seconds` — *default `15`*  
  Seconds before a silent source is forgotten.
- `fill-color` — *default `new SettingColor(0, 200, 255, 45`*  
  Colour of the filled part of the box.
- `line-color` — *default `new SettingColor(0, 200, 255, 220`*  
  Colour of the box outline.


## Combat

Fighting tools. Anything that fabricates movement or timing is marked (risky) and off by default, because those are what an anti-cheat objects to.

### attribute-swap++

Swaps slots on attack to refresh weapon attributes/cooldown.

**General**

- `swap-slot` — *default `9`*  
  Hotbar slot to bounce through (1-9).


### auto-city++

Breaks the block beside a target in a hole to expose them.

**General**

- `target-range` — *default `5`*  
  How far away a target can be (blocks).
- `timing-jitter` — *default `0`*  
  Randomly space out the block-break so it isn't a perfect fixed rhythm (%).
- `break-range` — *default `5`*  
  How far away you can break (blocks).


### auto-log++

Auto-disconnect at low health or when a player approaches.

**General**

- `health` — *default `6`*  
  Disconnect at/below this health (0 = off).
- `on-player` — *default `false`*  
  Also disconnect when a player comes within range.
- `player-range` — *default `32`, hidden until enabled*  
  How close a player must be to react (blocks).


### bombaura++

Auto anchor/bed aura (place, charge, detonate on nearby enemies).

**Modes** — Anchor, Bed

**General**

- `mode` — *default `Mode.Anchor`*  
  Anchor (any dim) or Bed (Nether/End).
- `target-range` — *default `5`*  
  How far away a target can be (blocks).
- `place-range` — *default `5`*  
  How far away you can place (blocks).
- `step-delay` — *default `2`*  
  Ticks between place/charge/detonate.
- `min-damage` — *default `6`*  
  Only detonate if it would deal at least this to the target.
- `max-self-damage` — *default `8`*  
  Never detonate if it would deal more than this to you.

**Bypass**

- `miss-chance` — *default `0`*  
  Chance (%) to skip a step this cycle, like a human hesitating. 0 = never (fastest but most robotic).
- `timing-jitter` — *default `0`*  
  Randomly vary the step delay by +/- this % so it isn't a perfect fixed rhythm.


### bow-aimbot++

Auto-aims your bow at the nearest target while drawing.

**General**

- `range` — *default `40`*  
  How far out (in blocks) this reaches.
- `aim-noise` — *default `0.0`*  
  Add a tiny random wobble (degrees) to the auto-aim so it doesn't lock on with robotic precision.


### combat-extras++

Bow-spam / auto-exp / d-tap.

**General**

- `bow-spam` — *default `false`*  
  Rapidly fire the bow.
- `auto-exp` — *default `false`*  
  Automatically throw XP bottles.
- `d-tap` — *default `false`*  
  Double-tap to trigger.


### combat-macros++

Anchor / wind-pearl combo macros on a keybind.

**Modes** — Anchor, WindPearl

**General**

- `macro` — *default `Which.Anchor`*  
  The command/text the macro runs.


### crystal-aura++

Places and blows end crystals for you. Also carries the helpers: crystals you hit vanish on your client straight away so you can chain into the next one, and the best spot to place is highlighted. Turn place and break off to use it as helpers only.

**General**

- `target-range` — *default `8`*  
  How far away a target can be (blocks).
- `place-range` — *default `5`*  
  How far away you can place (blocks).
- `break-range` — *default `5`*  
  How far away you can break (blocks).
- `max-speed` — *default `true`*  
  Place and break as fast as the delays allow.
- `multi-crystal` — *default `false`*  
  Place on several bases around the target each tick.
- `multi-max` — *default `3`, hidden until enabled*  
  Most crystals to place at once.
- `walk-through` — *default `true`*  
  Place/break without line of sight.
- `place` — *default `true`*  
  Place crystals automatically.
- `break` — *default `true`*  
  Break crystals automatically.
- `place-delay` — *default `0`*  
  Ticks between placements.
- `break-delay` — *default `0`, hidden until enabled*  
  Ticks between breaks.
- `max-self-damage` — *default `8`*  
  Don't act if it would deal more than this much damage to you.

**Helpers**

- `desync-fix` — *default `true`*  
  Remove crystals you attack on your client straight away, so you can hit the next one without waiting for the server. Also available as the standalone crystal-optim++ module; running both is harmless.
- `remove-range` — *default `6`, hidden until enabled*  
  Range to client-side remove crystals within (blocks).
- `show-placement` — *default `false`*  
  Highlight the single best obsidian/bedrock base to place a crystal on against the nearest target.
- `placement-color` — *default `new SettingColor(0, 255, 120, 200`, hidden until enabled*  
  Colour of the best-placement highlight.

**Bypass**

- `miss-chance` — *default `0`*  
  Chance (%) to skip an attack this tick, like a human misclicking. 0 = never miss (fastest but most robotic).
- `timing-jitter` — *default `0`*  
  Randomly vary the delays by +/- this % so they aren't a perfect fixed rhythm.


### crystal-optim++

Makes crystals you hit vanish on your client straight away so you can chain into the next one, and highlights the best spot to place.

**General**

- `desync-fix` — *default `true`*  
  Client-side remove crystals you attack (faster crystals).
- `remove-range` — *default `6`, hidden until enabled*  
  How close a crystal must be for the desync fix to remove it on your client (blocks).
- `show-placement` — *default `false`*  
  Highlight the optimal crystal base around the nearest target.
- `target-range` — *default `8`, hidden until enabled*  
  How far away a player can be and still be targeted (blocks).
- `max-self-damage` — *default `8`, hidden until enabled*  
  Never suggest a placement that would deal more than this much damage to you.
- `color` — *default `new SettingColor(0, 255, 0, 90`, hidden until enabled*  
  Colour of the best-placement highlight.


### fast-bow++

Draw bows and crossbows much faster than normal.

**Modes** — Full, Instant, Rapid, Desync

**General**

- `mode` — *default `Mode.Full`*  
  Release strategy. Full = server-safe full power; Instant = fastest release where the server allows it; Rapid = max fire rate; Desync = experimental server trick.
- `crossbows` — *default `true`*  
  Affect crossbows.
- `charge-time` — *default `0`, hidden until enabled*  
  Instant mode on a REMOTE server: ticks to draw before releasing (0 = weakest/instant, 20 = full vanilla). Ignored where you control the server.
- `spam-delay` — *default `1`*  
  Ticks between shots for Rapid/Desync/spam.


### godmode++

Makes you effectively unkillable — negates fall damage, knockback and other harm. How much applies depends on the server.

*No settings.*

### hit-particles++

Spawns particles on the entity you attack.

**Modes** — Crit, Damage, Flame, Heart, Explosion

**General**

- `particle` — *default `Particle.Crit`*  
  Which particle to use.
- `count` — *default `12`*  
  How many particles to spawn.


### self-defense++

Self web / trap / anvil in one module.

**General**

- `web` — *default `false`*  
  Trap attackers in cobwebs.
- `web-double` — *default `false`, hidden until enabled*  
  Also web your upper hitbox.
- `trap` — *default `false`*  
  Obsidian around you.
- `anvil` — *default `false`*  
  Anvil two blocks above you.


### wallbang++

Lets you attack targets through walls. Whether it lands depends on the server.

**Modes** — Nearest, LowestHealth, LowestArmor, ClosestAngle, Random

**Modes** — None, Client, Silent

**General**

- `target-priority` — *default `Target.Nearest`*  
  Which entity to hit when several are in range.
- `attack-delay` — *default `10`*  
  Ticks between attacks (lower = faster).
- `miss-chance` — *default `0`*  
  Chance (%) to skip an attack this cycle, like a human misclicking. 0 = never miss.
- `timing-jitter` — *default `0`*  
  Vary the attack delay by +/- this % so it isn't a perfect fixed rhythm.
- `players-only` — *default `true`*  
  Only target players.


## Movement

Getting around. Flight, elytra, freecam and movement helpers.

### auto-wasp++

Auto-fly pursuit that holds an offset over the nearest target.

**General**

- `target-range` — *default `64`*  
  How far away a target can be (blocks).
- `horizontal-speed` — *default `1.5`*  
  Horizontal speed (blocks/tick).
- `vertical-speed` — *default `0.8`*  
  Vertical speed (blocks/tick).
- `offset-y` — *default `3`*  
  Height to hold above the target.
- `avoid-landing` — *default `true`*  
  Don't dive into the ground.


### click-tp++

Blink forward along your look. Bind a key and tap it.

**General**

- `distance` — *default `6`*  
  Maximum distance in blocks.
- `steps` — *default `4`*  
  Packets to split the jump into (more = smoother/safer).


### elytra-fly++

Enhanced elytra flight with extra control and boost options.

**Modes** — Vanilla, Pitch40, Packet, Bounce

**General**

- `mode` — *default `Mode.Vanilla`*  
  Which mode to use.
- `horizontal-speed` — *default `1.0`, hidden until enabled*  
  Glide speed (Vanilla/Packet).
- `vertical-speed` — *default `0.5`, hidden until enabled*  
  Up/down speed on jump/sneak (Vanilla/Packet).
- `fall-multiplier` — *default `0.01`, hidden until enabled*  
  How fast you sink when not holding jump (Vanilla/Packet).
- `sprint` — *default `true`*  
  Keep sprinting while flying.

**Pitch40**

- `upper-bounds` — *default `120`, hidden until enabled*  
  Climb until you reach this Y, then dive.
- `lower-bounds` — *default `70`, hidden until enabled*  
  Dive until you reach this Y, then climb.
- `rotation-speed-up` — *default `3`, hidden until enabled*  
  How fast it pitches up when climbing.
- `rotation-speed-down` — *default `3`, hidden until enabled*  
  How fast it pitches back down when diving.

**Extras**

- `auto-take-off` — *default `false`*  
  Start gliding automatically when you hold jump in the air with an elytra.
- `auto-hover` — *default `false`*  
  Hold altitude when you're not pressing anything (Vanilla/Packet).
- `no-crash` — *default `false`*  
  Cut horizontal speed before you fly into a wall.
- `crash-look-ahead` — *default `6`, hidden until enabled*  
  Blocks ahead to check for no-crash.
- `auto-firework` — *default `false`*  
  Fire a rocket by itself whenever you drop below the speed below, so a long flight keeps going without you holding it up. Rockets come from your hotbar and it only fires while you are actually gliding.
- `min-speed` — *default `20.0`, hidden until enabled*  
  Speed you have to fall under before another rocket goes off, in blocks a second.
- `min-y` — *default `80`, hidden until enabled*  
  Do not fire below this height — no point burning rockets while you are still climbing out of a hole.
- `firework-delay` — *default `40`, hidden until enabled*  
  Ticks to wait between rockets.
- `boost` — *default `false`*  
  Extra forward burst while holding sprint, no rockets.
- `boost-amount` — *default `1.5`, hidden until enabled*  
  How much extra speed each boost adds.


### fast-climb++

Climb ladders/vines faster.

**General**

- `speed` — *default `0.5`*  
  How fast (higher = faster).


### flight++

Fly freely. How well it holds up depends on the server's anti-cheat.

**Modes** — Creative, Velocity, Motion, Glide, Jetpack, Bounce, Smooth, Static, Packet, Vanilla

*No settings.*

### freecam++

Smooth free-flying detached camera with pathing / rotate / sneak options.

**General**

- `speed` — *default `0.5`*  
  Camera fly speed (blocks per tick).
- `vertical-speed` — *default `1.2`*  
  Multiplies how fast you rise and fall against your normal speed. Under 1 makes it easier to hold a height while you look around; over 1 gets you up and down a shaft quickly.
- `vertical-affects-keys` — *default `true`*  
  Apply the multiplier to jump and sneak, so tapping up or down moves you at the boosted rate.
- `vertical-affects-look` — *default `false`*  
  Apply it to the up-and-down part of flying where you look, too. Leave this off if you want flying forward to feel exactly as before and only the jump and sneak keys to be quicker.
- `scroll-remembers` — *default `true`*  
  Keep whatever you scrolled to for next time instead of snapping back to the slider value when you close the camera. Off means the scroll only lasts for that session.
- `click-action` — *default `Click.Ignore`*  
  What a mouse click does while the camera is out. Your body is not where the camera is, so a click used to swing at whatever happened to be in front of the camera, which is usually thin air. Ignore drops the click entirely; Real Body mines and uses where your actual character is looking, which is what you would get with the camera closed.
- `hold-to-mine` — *default `true`, hidden until enabled*  
  Mine only while the button is held, rather than latching on from one press. Off leaves the click held down until you press again, which is how it behaved before and is easy to forget about.
- `smoothing` — *default `0.05`*  
  How much the camera eases into a start and out of a stop. A little takes the jerkiness off without making it feel like it is floating; 0 is instant, the way Meteor moves.
- `fly-toward-crosshair` — *default `true`*  
  Move along the direction you're actually looking, so looking down and holding forward takes you down. Off = stay level and only rise/fall with jump and sneak.
- `look-sensitivity` — *default `0.15`*  
  Mouse sensitivity for steering the camera.
- `scroll-speed` — *default `true`*  
  Scroll the mouse wheel to change fly speed live (like Meteor's freecam).
- `scroll-step` — *default `1.1`, hidden until enabled*  
  How much each scroll notch multiplies the speed.
- `keep-inputs` — *default `true`*  
  Lock the attack/use you held on entry so you keep mining/using at your character's view.
- `mine-at-character` — *default `true`*  
  A mouse press in freecam mines/interacts where your CHARACTER looks, not the camera.

**Extras**

- `reload-chunks` — *default `false`*  
  Reload all chunks when freecam toggles (fixes render gaps).
- `show-hands` — *default `true`*  
  Stay in first person so your hands/held item render at the camera. Off forces third person so you see your body.
- `rotate` — *default `false`*  
  Rotate your character's body to follow the camera direction.
- `keep-sneaking` — *default `false`*  
  Hold the player sneaking the whole time so it can't walk off an edge.
- `allow-pathing` — *default `false`*  
  Don't lock movement keys, so Baritone / the player can still path while the camera flies free.


### jumps++

High / long / air / auto jump in one module.

**General**

- `high-jump` — *default `false`*  
  Jump higher than normal.
- `jump-power` — *default `0.7`, hidden until enabled*  
  How high the high-jump goes.
- `long-jump` — *default `false`*  
  Jump further forward.
- `long-power` — *default `1.2`, hidden until enabled*  
  How far the long-jump goes.
- `air-jump` — *default `false`*  
  Allow jumping again in mid-air.
- `auto-jump` — *default `false`*  
  Jump automatically whenever possible.


### movement-extras++

GUI-move / entity-control / slippy / reverse-step in one module.

**General**

- `gui-move` — *default `false`*  
  Keep moving while a screen is open.
- `entity-control` — *default `false`*  
  Steer the entity you're riding.
- `slippy` — *default `false`*  
  Make movement slippery like ice.
- `slip-amount` — *default `1.5`, hidden until enabled*  
  How slippery movement is.
- `reverse-step` — *default `false`*  
  Step down off edges smoothly instead of falling.


### noclip++

Lets you move freely through blocks. Works where the server doesn't correct your position (own worlds and lenient servers).

**Modes** — Flying

*No settings.*

### reverse-step++

Fall down small ledges instantly.

**General**

- `fall-speed` — *default `3.0`*  
  Downward speed applied when dropping (blocks/tick).
- `fall-distance` — *default `3.0`*  
  Only trigger when ground is within this many blocks below.
- `vehicles` — *default `false`*  
  Also affect vehicles you're riding.


### snap-tap++

When you hold two opposite movement keys at once, the most recently pressed one wins instead of both cancelling out.

**General**

- `left-right` — *default `true`*  
  Apply it to strafing (A / D).
- `forward-back` — *default `true`*  
  Apply it to forward / back (W / S).
- `release-restores` — *default `true`*  
  When you let go of the newer key while still holding the older one, snap straight back to the older direction instead of stopping.


### speed++

Move faster than normal. How much you can get away with depends on the server's anti-cheat.

**Modes** — Simple

*No settings.*

### trident-boost++

Riptide-launch in any conditions with a trident.

**General**

- `power` — *default `2.5`*  
  Strength of the effect.


## Player

Things that act on your own character — mining, inventory, interaction.

### auto-sign++

Copies your first sign's text onto every sign after.

**General**

- `front` — *default `true`*  
  Write on the front side of the sign.


### autoer++

Auto-tool + auto-mount (and more) in one module.

**General**

- `auto-tool` — *default `true`*  
  Swap to the best tool for the block you're mining.
- `auto-mount` — *default `false`*  
  Mount the nearest rideable animal you look at.
- `auto-weapon` — *default `false`*  
  Swap to your best sword/axe when attacking a mob.
- `auto-shear` — *default `false`*  
  Shear nearby wooly sheep (needs shears in hand).
- `auto-breed` — *default `false`*  
  Feed nearby breedable animals with the food you're holding.
- `auto-fish` — *default `false`*  
  Auto-reel and recast when a fish bites.
- `mount-bypass` — *default `false`*  
  Force-mount the animal you look at even if you have a vehicle already / server quirks.
- `auto-smelter` — *default `false`*  
  With a furnace open: auto-collect finished output and top up coal fuel.
- `auto-brewer` — *default `false`*  
  With a brewing stand open: auto-collect finished potions and top up blaze powder fuel.
- `auto-nametag` — *default `false`*  
  Name-tag nearby un-named animals (needs a name tag in hotbar).
- `auto-armor` — *default `false`*  
  Equip the best armor from your inventory into empty armor slots.


### chunk-reloader++

Runs a command sequence (delhome -> sethome -> rtp -> home) to force a chunk reload.

**General**

- `auto-disable` — *default `true`*  
  Automatically disable the module after the command sequence completes.
- `client-reload` — *default `false`*  
  Also force the client to redraw all chunks locally when the sequence finishes (or immediately, if the command sequence is off).
- `command-sequence` — *default `true`*  
  Run the server command sequence below (delhome -> sethome -> rtp -> home) to force a real chunk reload.
- `chat` — *default `true`*  
  Show progress messages in chat.
- `loop` — *default `false`, hidden until enabled*  
  Continuously loop the command sequence while active.

**Commands**

- `command-1` — *default `"delhome 1"`*  
  First command.
- `command-2` — *default `"sethome 1"`*  
  Second command.
- `command-3` — *default `"rtp"`*  
  Third command.
- `command-4` — *default `"home 1"`*  
  Fourth command.

**Timing**

- `delay-ticks` — *default `15`*  
  Base delay between commands (20 ticks = 1s).
- `random-jitter` — *default `5`*  
  Random extra ticks added to each delay.
- `loop-cooldown` — *default `40`, hidden until enabled*  
  Extra delay before restarting when looping.


### home-utils++

Save/goto named coordinate homes; optional auto-walk.

**General**

- `name` — *default `"base"`*  
  Name for save/goto/remove.
- `walk-to-home` — *default `false`*  
  Hold forward toward the current home until you arrive.


### instant-mine++

Break blocks far faster, with several methods to choose from (speed multiplier, packets, abort, sequenced, or an area nuker). Strength depends on the server.

**Modes** — Multiplier, Packet, Abort, Vanilla, Sequenced, Nuker, SpeedMine

*No settings.*

### invisibility++

Turns you invisible to other players and mobs. How complete the invisibility is depends on the server.

*No settings.*

### miner++

Mines for you — vein mining, fast packet mining, or a wide excavator, all in one module.

**General**

- `vein-miner` — *default `true`*  
  Break all connected same-type blocks around the target.
- `packet-mine` — *default `false`*  
  Fire raw start+stop destroy packets (instant on lenient servers).
- `excavator` — *default `false`*  
  Break every block in a radius around the target (nuker-style area).
- `infinity-miner` — *default `false`*  
  Auto-hold mine on whatever block you look at (no need to hold click).
- `radius` — *default `3`*  
  Vein/excavator search radius.
- `blocks-per-tick` — *default `16`*  
  Max blocks to send per tick (kick safety).


### nbt-adder++

Copy item NBT by middle-click and apply custom NBT/components to held items (creative). Java component-system equivalent of Horion's NBT editor.

*No settings.*

### no-break-delay++

Removes the cooldown between breaking blocks. Mine continuously with no pause.

*No settings.*

### no-cooldown++

Removes the attack-swing delay and item cooldowns (like ender pearls) so you can act back-to-back. Strength depends on the server.

*No settings.*

### portal-inv++

Access your inventory during portal transit loading.

*No settings.*

### reach++

Extends attack range with selectable targeting. Rejected by strict server anti-cheats.

**Modes** — Crosshair, Nearest, LowestHealth, LowestArmor, Random

**Modes** — None, Client, Silent

**General**

- `target-priority` — *default `Target.Crosshair`, hidden until enabled*  
  Crosshair = only what you aim at; others auto-select within range.
- `auto-attack` — *default `false`*  
  Automatically attack the selected target within range.
- `hard-cap` — *default `false`*  
  Also raise the vanilla attack-range attribute directly (in addition to the extended-range targeting above), for servers that read that attribute instead of just validating hit distance.
- `attack-delay` — *default `10`, hidden until enabled*  
  Ticks between auto-attacks (lower = faster).
- `players-only` — *default `false`*  
  Only target players.


### render-method++

Forces chunks to reload so terrain the server sent but your client never drew shows up.

**General**

- `manual` — *default `false`*  
  While this module is on, hold render distance at 'low-distance'. Turn the module off to restore.
- `y-drop` — *default `false`*  
  Snap render distance down to 'low-distance' when you go below 'trigger-y', and restore your original when you come back above it.
- `auto-refresh` — *default `true`*  
  Each time you cross below 'trigger-y', briefly drop then restore render distance to force nearby chunks to reload.
- `krypton-light-finder` — *default `false`*  
  Turn Krypton's light-finder on while active (only works if the Krypton mod is installed).
- `second-line` — *default `true`*  
  Refresh again at a deeper height. One pass near the surface and another further down catches things the first crossing was too high to reach.
- `second-y` — *default `-45`, hidden until enabled*  
  The deeper height. Same rule: it fires on the way down only.
- `catch-teleports` — *default `true`*  
  Also fire when you arrive below a line without having walked through it — an ender pearl, or the server moving you. Those skip the crossing entirely, so without this a pearl straight down never refreshes.
- `trigger-y` — *default `-4`*  
  The height that triggers a refresh. It fires the moment you cross it going down — you do not have to stay there, and it will fire again next time you come back down through it.
- `low-distance` — *default `2`*  
  The reduced render distance (in chunks) used by the methods above.
- `refresh-ticks` — *default `20`, hidden until enabled*  
  How many ticks auto-refresh stays collapsed before restoring.
- `expand-delay` — *default `0`, hidden until enabled*  
  Ticks y-drop waits before restoring after you go back above trigger-y.

**Visualiser**

- `visualise` — *default `false`*  
  Shade the ground this module has forced through, anchored to the spot where it last ran rather than following you. Lets you see exactly what you've covered. Off by default.
- `area-chunks` — *default `8`, hidden until enabled*  
  How far the shading reaches from that spot, in chunks. Match it to your render distance to see the real coverage.
- `shade-y` — *default `63`, hidden until enabled*  
  Height to draw the shading at. Sea level by default so it sits where you can see it.
- `chunk-grid` — *default `false`, hidden until enabled*  
  Draw the individual chunk squares instead of one solid block of shading.
- `mark-centre` — *default `true`, hidden until enabled*  
  Mark the chunk the coverage is measured from.
- `area-color` — *default `new meteordevelopment.meteorclient.utils.render.color.SettingColor(0, 200, 255, 35`, hidden until enabled*  
  Colour of the shaded area.
- `centre-color` — *default `new meteordevelopment.meteorclient.utils.render.color.SettingColor(255, 255, 0, 220`, hidden until enabled*  
  Colour of the centre marker.


### swing-speed++

Controls how fast your arm swings — a set speed, a fixed duration, or auto-timed to whatever you're doing (mining a block, attacking, or using any item like fireworks).

**Modes** — Speed, Custom, Auto

**Modes** — AttackCooldown, CustomTicks

**General**

- `mode` — *default `Mode.Speed`*  
  Speed = a multiplier on the normal swing. Custom = fixed ticks. Auto = time to mine the block you look at.
- `speed` — *default `1.0`, hidden until enabled*  
  Their SwingSpeed multiplier: duration = round(vanilla / speed). Higher = faster.
- `auto-idle` — *default `AutoIdle.CustomTicks`, hidden until enabled*  
  In Auto, when you are NOT aimed at a block. Custom ticks keeps the swing fast and steady; attack cooldown ties it to your weapon's recharge, which slows the animation right down between hits.
- `swing-ticks` — *default `3`, hidden until enabled*  
  Swing duration in ticks (lower = faster). Vanilla is 6.
- `auto-interact` — *default `true`, hidden until enabled*  
  In Auto mode, swing fast whenever you use or right-click ANY item — fireworks, ender pearls, bows, fishing rods, food, buckets and so on — instead of timing the swing to your attack cooldown.
- `interact-ticks` — *default `3`, hidden until enabled*  
  How fast the swing is when you use an item in Auto mode (lower = faster).
- `auto-cap` — *default `60`, hidden until enabled*  
  Clamp auto swings to at most this many ticks (very hard blocks like obsidian).


### timer++

Speeds up or slows down your game clock, with several styles and fine control.

**Modes** — Constant, Pulse, Ramp, Direct, Packets, Advance, Smart

**General**

- `mode` — *default `Mode.Constant`, hidden until enabled*  
  Constant/Pulse/Ramp scale the client clock. Direct adds packet spam. Packets is spam-only. Advance sends forward-stepped positions. Smart only spams while you're acting.
- `pulse-off` — *default `10`, hidden until enabled*  
  Pulse: ticks at normal speed each cycle.
- `ramp-ticks` — *default `20`, hidden until enabled*  
  Ramp: ticks to ease from normal up to the full multiplier.


### world-extras++

Flamethrower / liquid-fill world helpers.

**General**

- `flamethrower` — *default `false`*  
  Flint-and-steel the block you look at rapidly.
- `liquid-fill` — *default `false`*  
  Place your held bucket liquid at the crosshair.


### y-level-spoof++

RISKY: Tries to fake your height to the server without moving you. Most anti-cheats will catch this.

**Modes** — Burst, Constant

**Modes** — Fixed, Oscillate, Jitter, Descend

*No settings.*

## Misc

Everything else: rendering, chat, HUD, timing and utilities.

### anti-afk++

Periodic actions to prevent AFK kicks.

**General**

- `jump` — *default `true`*  
  Jump periodically.
- `swing` — *default `false`*  
  Swing your hand.
- `sneak` — *default `false`*  
  Sneak/unsneak quickly.
- `strafe` — *default `false`*  
  Alternate left/right steps.
- `spin` — *default `false`*  
  Continuously rotate your view.
- `spin-speed` — *default `10`, hidden until enabled*  
  How fast to spin while AFK.
- `delay` — *default `20`*  
  Ticks between actions.


### book-bot++

Writes the held writable book with your text.

**General**

- `page-text` — *default `"shama"`*  
  Text to put on the page.
- `pages` — *default `50`*  
  How many pages to fill.
- `sign` — *default `false`*  
  Sign the book with a title.
- `title` — *default `"shama"`, hidden until enabled*  
  Title of the book.


### bypass++

Small client-side tweaks that make other modules look more legitimate to anti-cheats.

**General**

- `rotation-smoothing` — *default `true`*  
  How smoothly rotations are applied.
- `max-rotation-step` — *default `35`, hidden until enabled*  
  Max degrees the view may snap per tick.
- `rotation-noise (risky)` — *default `false`*  
  Add a tiny, natural wobble to your aim so it never sits perfectly still or moves in perfectly straight lines — the kind of thing that flags aim-assist.
- `noise-amount` — *default `1.0`, hidden until enabled*  
  How much wobble to add, in degrees (keep it small — 0.5 to 2 feels natural).


### camera-tweaks++

Custom FOV and view-bob overrides.

**General**

- `custom-fov` — *default `true`*  
  Force a specific FOV while active.
- `fov` — *default `90`, hidden until enabled*  
  The field-of-view value to force.
- `no-view-bob` — *default `false`*  
  Disable view bobbing.


### chat-extras++

Chat spam + proximity message-aura.

**General**

- `repeat-message` — *default `false`*  
  Repeat a chat message automatically.
- `spam-message` — *default `"shama on top"`, hidden until enabled*  
  The message to repeat.
- `spam-delay` — *default `200`, hidden until enabled*  
  Ticks between spam messages.
- `message-aura` — *default `false`*  
  Send a message when a player comes near.
- `aura-message` — *default `"caught in 4k"`, hidden until enabled*  
  The message the aura sends.
- `aura-range` — *default `6`, hidden until enabled*  
  How far the message-aura reaches (blocks).


### fake-visuals++

Client-side fakes for screenshots — a pay receipt that never sends, and a sidebar of your own invention.

**Fake Pay**

- `fake-pay` — *default `false`*  
  Catch your own pay command before it is sent and print a receipt that looks real. Nothing reaches the server, so no money moves and the other person sees nothing at all.
- `command` — *default `"pay"`, hidden until enabled*  
  The command to catch, without the slash.
- `currency` — *default `"$"`, hidden until enabled*  
  Symbol to put in front of the amount.
- `block-command` — *default `true`, hidden until enabled*  
  Stop the command from being sent at all. Turning this off sends it for real, which defeats the point.

**Fake Sidebar**

- `sidebar` — *default `false`*  
  Draw a sidebar of your own with whatever numbers you like. It is painted by this addon, so the server has no idea it is there.
- `title` — *default `"Stats"`, hidden until enabled*  
  Heading at the top of the sidebar.
- `x` — *default `1400`, hidden until enabled*  
  Distance from the left of the screen.
- `y` — *default `120`, hidden until enabled*  
  Distance from the top of the screen.
- `background` — *default `new SettingColor(0, 0, 0, 140`, hidden until enabled*  
  Colour behind the sidebar.
- `text-color` — *default `new SettingColor(255, 255, 255, 255`, hidden until enabled*  
  Colour of the rows.


### force-commands++

Give yourself operator permissions so all commands work even with cheats off. Works where you have that authority (your own worlds and servers that allow it).

*No settings.*

### hide-chat++

Hides chat & history but still lets you type.

*No settings.*

### hostile-esp++

ESP for hostile mobs with an optional tracer.

**Modes** — Off, Outline, Fill, Both

**Modes** — Box, Wireframe

*No settings.*

### item-highlight++

Highlights dropped items on the ground.

**General**

- `color-by-rarity` — *default `true`*  
  Colour each item by how rare it is instead of using one flat colour: white common, yellow uncommon, pink rare, purple epic. Enchanted items get the rare colour too.
- `line-color` — *default `new SettingColor(255, 255, 0, 220`, hidden until enabled*  
  Colour of the box outline when rarity colouring is off.
- `fill-color` — *default `new SettingColor(255, 255, 0, 40`, hidden until enabled*  
  Colour of the box sides when rarity colouring is off.
- `common-color` — *default `new SettingColor(220, 220, 220, 220`, hidden until enabled*  
  Colour for ordinary items.
- `uncommon-color` — *default `new SettingColor(255, 255, 85, 220`, hidden until enabled*  
  Colour for uncommon items.
- `rare-color` — *default `new SettingColor(255, 105, 180, 220`, hidden until enabled*  
  Colour for rare and enchanted items.
- `epic-color` — *default `new SettingColor(180, 80, 255, 220`, hidden until enabled*  
  Colour for epic items.
- `fill-alpha` — *default `40`, hidden until enabled*  
  How solid the filled sides are when colouring by rarity.
- `tracers` — *default `false`*  
  Draw a line from you to each highlighted thing.
- `shape-mode` — *default `ShapeMode.Both`*  
  How boxes are drawn: outline only, filled sides only, or both.


### lag-detector++

Finds places the server is struggling — a farm, a stash full of hoppers, or anything else eating server time. Watches server tick rate, your own framerate, and how bad the worst frames get.

**General**

- `tps-threshold` — *default `15.0`*  
  Alert when estimated server TPS drops below this.
- `trigger-seconds` — *default `5`*  
  How many seconds TPS must stay low before alerting.
- `re-alert-cooldown` — *default `200`*  
  Minimum seconds between repeat alerts.
- `popup` — *default `true`*  
  Show an on-screen title popup.
- `chat` — *default `true`*  
  Send a chat message.
- `sound` — *default `true`*  
  Play an alert sound.
- `server-tps` — *default `true`*  
  Watch how fast the server is ticking. A sustained drop is the clearest sign something nearby is working it hard — usually a farm.
- `your-fps` — *default `false`*  
  Watch your own framerate as well. Some farms flood you with entities and particles rather than costing the server much, so your frames tank while the tick rate looks fine.
- `fps-below` — *default `40`, hidden until enabled*  
  Framerate that counts as struggling. Compare it against what you normally get, not a fixed idea of good.
- `worst-frames` — *default `false`*  
  Watch the worst one percent of frames rather than the average. Entity-heavy farms cause hitching that a steady average hides completely — this is what catches those.
- `worst-frames-below` — *default `50`, hidden until enabled*  
  How far below your normal framerate the worst frames must fall, as a percent. 50 means a stutter down to half your usual rate counts.
- `check-anywhere` — *default `true`*  
  Keep checking wherever you are, not just while chunks are streaming in. Leave this on if you want to find farms by standing near them rather than only noticing lag as you fly past.
- `settle-time` — *default `1.0`*  
  Extra wait after the last chunk lands, on top of the loaded check above. The client keeps building chunk meshes for a moment after the data arrives, and that costs frames — this rides it out so the counter starts on a settled world.
- `chunk-delay` — *default `0.0`*  
  Hold chunks you are flying towards for this long before letting them load. Flying forward loads the next chunks constantly, and every arrival restarts the settle timer, so a reading never lasts long enough to report. Delaying them lets the timer run out first, then they all load at once. 0 lets everything through immediately.
- `ready-radius` — *default `3`*  
  How many chunks around you must be loaded before readings count. This is what tells the module the world has actually finished arriving, rather than assuming a fixed loading time.
- `min-duration` — *default `8`*  
  How long the problem has to last before it's reported. Short spikes happen constantly and mean nothing; something sustained means something is actually there.
- `ignore-client-lag` — *default `true`*  
  Throw away TPS samples taken while your own game was stuttering. Without this, your own framerate dips look exactly like server lag and cause false alerts.
- `smoothing` — *default `0.3`*  
  How heavily to average the TPS estimate. Higher is steadier but slower to react; low values react fast but flicker.

**Already Loaded**

- `already-loaded` — *default `false`*  
  Flag areas the server already had in memory before you got there. A chunk it has to read from disk or generate takes real time to send; one somebody is keeping alive comes back almost instantly. A burst of instant arrivals means a chunk loader, a farm, or someone living there — and it works even when nothing is visible.
- `instant-threshold` — *default `3`, hidden until enabled*  
  A chunk arriving within this many milliseconds of the one before it counts as already loaded. Lower is stricter.
- `instant-run` — *default `24`, hidden until enabled*  
  How many instant arrivals in a row before it's reported. A couple happen naturally; a long run does not.

**Region Highlight**

- `highlight-region` — *default `true`*  
  While TPS is low, box the 32x32 chunk region you're standing in (the lagging region).
- `box-after-seconds` — *default `10`, hidden until enabled*  
  Only draw the region box once TPS has stayed low for this long. Stops the box flashing on brief dips.
- `region-size` — *default `32`*  
  Region size in chunks (Folia default here is 32).


### notifiers++

Rain + low-durability chat alerts.

**General**

- `rain` — *default `true`*  
  Notify when it starts raining.
- `low-durability` — *default `true`*  
  Warn when a tool is nearly broken.
- `durability-threshold` — *default `20`, hidden until enabled*  
  Warn once durability drops below this.


### packet-logger++

Lists the network packets going past in chat — a debug tool for seeing what the server is actually sending.

**General**

- `incoming` — *default `true`*  
  Log packets coming from the server.
- `outgoing` — *default `false`*  
  Log packets you send.
- `name-filter` — *default `""`*  
  Only log packets whose class name contains this (blank = all).


### ping-spoofer++

Raises your measured ping by delaying KeepAlive packets, or delays all packets for fake lag.

**General**

- `fake-lag (risky)` — *default `false`*  
  Lag EVERYTHING — every packet in both directions is held, not just KeepAlive. This is full blink-style desync: the world freezes and your actions land in a burst when it releases. Off = only spoof the ping number.
- `delay` — *default `250`, hidden until enabled*  
  Baseline hold time (ms). Realistic fluctuation moves around this value. Keep well under ~15s or the server may time you out.
- `max-percent` — *default `120`, hidden until enabled*  
  Highest the delay may drift to, as a percent of the baseline.

**Ticks**

- `outgoing-ticks` — *default `5`, hidden until enabled*  
  How many ticks to hold outgoing packets (20 ticks = 1 second). Used instead of the ms delay when ticks are on.
- `delay-inbound (risky)` — *default `false`*  
  Also hold packets you receive, so the world updates late too. Makes the lag look symmetrical rather than one-sided.
- `inbound-ticks` — *default `5`, hidden until enabled*  
  How many ticks to hold incoming packets before applying them.
- `use-ticks` — *default `true`*  
  Use the tick counts above instead of the millisecond delay below.


### swarm++

Coordinate your own alt accounts (host/worker) over a port you control.

**Modes** — Host, Worker

**General**

- `mode` — *default `Mode.Worker`*  
  Which mode to use.
- `host-ip` — *default `"127.0.0.1"`, hidden until enabled*  
  Worker: host to connect to.
- `port` — *default `25566`*  
  Network port to use.


### time-changer++

Force a custom client-side time of day.

**General**

- `time` — *default `6000`*  
  Time of day to force (0 = dawn, 6000 = noon, 18000 = midnight).


### trail++

A fading trail behind you.

**General**

- `length` — *default `80`*  
  How many points to keep.
- `color` — *default `new SettingColor(120, 200, 255, 200`*  
  Highlight colour.
- `fade` — *default `true`*  
  Fade the tail out.

