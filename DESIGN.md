# Ore Radar 2.0: design contract

Ore Radar is a handheld radar. It finds the nearest block of the kind the player chooses, then
guides them to it with three aids: see-through outlines of the vein, an arrow and distance
readout, and a beep. It is a standalone mod for Modrinth, and it should be light, non-invasive
and work alongside other mods.

This file is the contract every module implements. If code and this file disagree, fix one of
them on purpose; don't let them drift.

Ore Radar 1.0.0 (Forge 1.20.1 only) was a private mod, not in this repository.
It is live on the operator's DeceasedCraft server, so 2.0 must load its items unchanged (§9).
Use its code as reference; don't edit it.

## 1. Targets and identity

| Module | Minecraft | Loader / API | Java | Jar |
|---|---|---|---|---|
| `mc1201/forge` | 1.20.1 | Forge 47.4.x (also loads on NeoForge 47.1 for 1.20.1) | 17 | `oreradar-2.0.0+1.20.1-forge.jar` |
| `mc1201/fabric` | 1.20.1 | Fabric Loader ≥0.15, Fabric API | 17 | `oreradar-2.0.0+1.20.1-fabric.jar` |
| `mc1211/neoforge` | 1.21.1 | NeoForge 21.1.x | 21 | `oreradar-2.0.0+1.21.1-neoforge.jar` |
| `mc1211/fabric` | 1.21.1 | Fabric Loader ≥0.15, Fabric API | 21 | `oreradar-2.0.0+1.21.1-fabric.jar` |
| `paper` | 1.20.1 → latest (26.x) | Paper API, compiled against 1.20.1 | 17 | `oreradar-2.0.0-paper.jar` |

- Mod id `oreradar`, display name **Ore Radar**, version `2.0.0`, license MIT. Base package
  `net.loserver.oreradar`; the pure-Java core is `net.loserver.oreradar.core`.
- Modrinth slug `ore-radar` was free on 2026-10-30. The jars accept exactly their Minecraft version
  (Fabric: `"minecraft": "1.20.1"`, not `~`, which would also accept 1.20.2+).
- **Dependencies stay light.**
  - Fabric: Fabric API, plus Team Reborn Energy, jar-in-jar (`teamreborn:energy`: 3.0.0 on 1.20.1,
    4.1.0 on 1.21.1; check what is current for each version).
  - Forge and NeoForge: nothing.
  - Paper: nothing.
  - No Architectury API, Cloth Config or any other config library at runtime. The ModMenu
    integration is compile-only and optional.
- Every asset is original: textures are drawn by our own stdlib Python scripts, as
  `tools/textures.py` did in 1.0. Vanilla textures may be **referenced** at runtime (for
  example `minecraft:textures/gui/...`), but never copied into the jar.
- 26.x mod loaders are out of scope for this pass. That will be a later port. The Paper plugin
  does run on 26.x.

## 2. Repository layout

```
~/oreradar/
  DESIGN.md  LICENSE  build.sh (builds everything, copies jars to dist/)
  README.md  CHANGELOG.md   for players and server owners
  modrinth/        the Modrinth page, icon, gallery and version list; PUBLISHING.md (manual steps)
  core/            pure Java 17, no Minecraft, no dependencies; JUnit 5 tests
  mc1201/common/   vanilla 1.20.1 code shared by forge and fabric (+ core sources)
  mc1201/forge/    mc1201/fabric/
  mc1211/common/   vanilla 1.21.1 code shared by neoforge and fabric (+ core sources)
  mc1211/neoforge/ mc1211/fabric/
  shared/          code and assets identical on every MC version, added to both commons
  paper/           Paper plugin (+ core sources)
  tools/           texture/icon generators (stdlib Python), test helpers
  dist/            built jars + SHA256SUMS
```

- Core is compiled **into** each jar by adding `core/src/main/java` as an extra source directory.
  It is not shaded or relocated; the package is unique.
- Each MC version follows the MultiLoader pattern. `common` holds the logic and reaches loader
  specifics through a small `Services` interface (ServiceLoader). The loader modules hold only
  that glue.
- Builds run under `nice` and must not need the network once dependencies are cached.

## 3. Game content

| Id | What |
|---|---|
| `oreradar:ore_radar` | item, stack size 1 (same id as 1.0) |
| `oreradar:charging_station` | block, block item, block entity, menu type |
| `#oreradar:untrackable` | block tag, empty by default (same as 1.0) |

**Recipes** use vanilla items only, so the progression is early-mid game:

- **Radar:**
  ```
   L        L = lightning_rod   G = glass_pane
  IGI       I = iron_ingot      R = redstone
  RCR       C = compass
  ```
- **Charging station:**
  ```
  ILI       L = lightning_rod   I = iron_ingot
  CBC       C = copper_ingot    B = redstone_block
  III
  ```

Packs override the recipes with datapacks or KubeJS, as usual.

### Item data

| | 1.20.1 NBT | 1.21.1 data component | Paper PDC (`oreradar:` keys) |
|---|---|---|---|
| target spec | `RadarTarget` string | `oreradar:target` string | `target` string |
| switched off | `RadarOff` bool | `oreradar:off` bool | `off` byte |
| charge | `RadarCharge` int | `oreradar:charge` int | `charge` int |
| marks a radar | (item id) | (item id) | `radar` byte = 1 on a `minecraft:compass` |
| keeps radars apart | (stack size 1) | (stack size 1) | `id` long, random per radar; from 1.20.5 also the `max_stack_size` 1 component |

- **Radars never stack**, on Paper too. A Paper recipe's result is an id-less template; each craft
  gets a fresh `id`. The id also lets the plugin settle owed drain on the right radar after it
  moved (hand swaps).
- **A missing charge key means full.** Radars from 1.0 and freshly crafted ones come charged.
  Write the value the first time charge is drained.
- **Don't re-bob the item for these writes.** Charge and target changes must not replay the
  re-equip animation:
  - Forge / NeoForge: `shouldCauseReequipAnimation`
  - Fabric: `allowNbtUpdateAnimation` / `allowComponentsUpdateAnimation`
- **Durability bar:** the item bar shows charge (`isBarVisible` / `getBarWidth` /
  `getBarColor`), cyan going to red. It is hidden when energy is disabled.
- **Tooltip:** the target, the charge and the use hints. Below 10% charge it also says how to
  recharge: in a Charging Station, or another mod's charger when `acceptExternalChargers` is on.

### Item use

- **Use (right-click):** open the Radar screen (§6) for that hand.
- **Sneak + use on a block:** track it. That means its group if it is in one (§4), else that
  block, or "untrackable" if the rules forbid it.
- **Sneak + use in the air:** switch the radar off or on. Apply a 10-tick cooldown so holding the
  key does not toggle twice (1.0 behaviour). On Paper the cooldown covers every radar action (open,
  track, toggle), because the client follows an unconsumed block click with an air click.
- **Choosing a target** (screen or sneak-use) also switches the radar on, as 1.0 did.

### Charging station

- **Slots:** two, a radar slot and a fuel slot, plus an internal charge buffer.
- **Each tick:**
  - It burns one fuel item into the buffer when the item fits. The first matching `fuels` entry
    gives its value.
  - It pushes up to `chargeRatePerTick` from the buffer into a radar in the slot.
- **Energy in:**
  - Forge / NeoForge: FE, on every side.
  - Fabric: Team Reborn `EnergyStorage`.
  - Both are converted at the server-config ratio and capped at `maxInputPerTick`.
- **Automation:** hoppers and pipes insert fuel from the top and sides, insert radars from the
  top, and extract only a fully charged radar from the bottom. On 1.20.1 implement
  `WorldlyContainer`; on Forge / NeoForge also expose the item handler capability.
- **Comparator output:** the buffer level.
- **On break:** it drops its contents and keeps the buffer charge in the dropped item. Use
  BlockEntityTag on 1.20.1 and the `block_entity_data` component on 1.21.1. On 1.20.1 the tag
  also holds the block entity's `id`, because 1.21.1 drops block entity data without one when it
  upgrades the item. An empty buffer is not saved and gets no tag, so a broken empty station
  stacks with new ones. The station item's tooltip shows only the stored charge (the buffer
  capacity is not in the config packet).
- **Block state:** `facing` (horizontal) and `lit` (true while charging), shown by a glowing
  front. Generate the textures.
- **Menu:** vanilla container style, with the player inventory, the two slots and a vertical
  charge bar. The bar tooltip shows "Buffer: 12,345 / 200,000 ⚡".

### Radar powered by other mods' chargers

When `energy.acceptExternalChargers` is on, the radar item exposes an FE item capability
(Forge / NeoForge) or a TR Energy item storage (Fabric). It converts at the same ratio, so other
mods' chargers fill it directly.

## 4. Targets, designations and categories (Vein Mining syntax)

**Designation entry:** `namespace:id` (a block) or `#namespace:path` (a block tag). Lists are
TOML string arrays.

**Categories are the allow-list.** The server config's `[categories.<id>]` tables, each with
`name`, `icon` (a block id) and `blocks` (designations), are the tabs of the radar screen, in file
order. They also decide what radars can track: a block is trackable only if a category lists it.
`allowAll = true` makes every block except air trackable; the categories are then tabs only.

- **Built-in categories:**
  - `ores`: `#c:ores`, `#forge:ores`; `#minecraft:coal_ores`, `copper_ores`, `iron_ores`,
    `gold_ores`, `redstone_ores`, `lapis_ores`, `emerald_ores`, `diamond_ores`;
    `minecraft:nether_quartz_ore`, `minecraft:ancient_debris`
  - `stone`: `#minecraft:base_stone_overworld`, `#minecraft:base_stone_nether`, `#c:stones`,
    `#forge:stone`
  - `wood`: `#minecraft:logs`
  - `nature`: `#minecraft:flowers`, `#minecraft:leaves`, `minecraft:amethyst_cluster`,
    `minecraft:budding_amethyst`, `#minecraft:crystal_sound_blocks`
  - `fluids`: `minecraft:water`, `minecraft:lava`
  - `utility`: `minecraft:spawner`, `#minecraft:beds`, `minecraft:chest`, `minecraft:barrel`
- **The default file** has only `ores`. The other five follow it as commented-out examples, so
  "ores plus spawners and chests" means uncommenting one table. A built-in category that keeps its
  English name reaches players as a translation key.
- **Visibility:** a tab shows only trackable entries; empty tabs are hidden.

**Exclusions.** `denyList` and the `#oreradar:untrackable` tag are never trackable, even when a
category lists them or `allowAll` is on.

**Groups.** A group makes one target find every variant. It keeps only its trackable blocks, and
one with none is dropped. Sources, merged in this order:
1. **Auto ore groups** (always on). Each material `<m>` from the tags below becomes one group,
   id `ores/<m>`:
   - `#c:ores/<m>` and `#forge:ores/<m>`
   - 1.20.1 Fabric's `#c:<m>_ores`
   - vanilla `#minecraft:<m>_ores`
2. **The config's `groups`.** An array of arrays of designations, e.g.
   `[["#c:ores/zinc", "create:zinc_ore"]]`, with ids `custom/<key>`.
3. **Datapack groups.** Any `data/<ns>/oreradar/groups.json`, **and also Vein Mining's
   `data/<ns>/veinmining/groups.json`** (`{"replace": bool, "groups": [[...], ...]}`), so
   existing packs' groups work for free. Ids are `pack/<key>`.

For `custom` and `pack` groups, `<key>` comes from the group's **first designation**: drop the
`#` and turn `:` into `.`, so `#c:ores/zinc` becomes `pack/c.ores/zinc`. A radar's stored
`@group` therefore survives reordering of the list or of the datapacks. If two groups would get
the same key, the later one gets `~2`, `~3` and so on, with a warning.

**Target spec,** as stored on the item:
- `@<groupId>`: a group
- `#ns:path`: a tag
- `ns:id`: one block

1.0 specs (`#forge:ores/iron`, `minecraft:spawner`) stay valid. A tag no loader defines any more
(1.21.1 has no `forge:` tags) finds the auto group it would feed, so `#forge:ores/iron` tracks
`@ores/iron`. The server resolves a spec to a `Set<Block>`; anything not allowed is refused. **Only the server evaluates specs for scanning.**

**Catalog.** The server builds it at start and after reloads. It is the full list of selectable
entries:
- every group, then every allowed single block not covered by a group;
- skipping air and blocks with no item, unless the block is in some category.

With `allowAll` a big modpack has thousands (10,320 in DeceasedCraft): about 1 MB sent to each
player with the mod at join and after every reload, trimmed by core to one packet (§7).

## 5. Scanning (server-authoritative)

The server scans and the client only draws. This keeps the server's limits real and works with
Paper's anti-xray (a client-side scan would see fake ores). It is also what lets vanilla clients
on Paper be served at all.

**Which radar is active, per player:**
- the first radar found that has a target, is switched on and has charge (or energy is off);
- searched in the order main hand → off hand → hotbar → main inventory;
- limited to `min(server activeFromMax, client activeFrom)`, i.e. `HANDS` < `HOTBAR` < `INVENTORY`.

**Scan cadence.** Every `scanIntervalTicks`, staggered by entity id. Creative players never use
charge, and an empty radar still works for them. On the mods, a player who never said hello is not
scanned and their radar does not drain (Paper serves every player as a vanilla client, §8).

**Early scans.** A change of radar, target, dimension, prefs or on/off state, and a radar becoming
active (switched on, recharged, moved into reach), is scanned for at once instead of at the
player's next cadence slot. The server notices these changes itself while ticking; packets never
force a scan. At most 3 such scans run back to back, then one per `scanIntervalTicks`, so a client
sending select or prefs every tick cannot make the server scan for it every tick. Core's
`EarlyScans` keeps this count for every platform.

Each scan:

1. If the dimension is denied by `dimensionsDeny`, return status `DIMENSION_DISABLED`.
2. Visit loaded chunks only (never load or generate), in rings, nearest first, out to
   `maxRadius` (blocks, horizontal and vertical, clamped to world height).
3. Pre-filter each section with its palette (`PalettedContainer.maybeHas` / Paper
   `Chunk#contains`). Iterate the blocks only in sections that pass.
4. Find the nearest match, distance `d`. The outline radius is
   `min(ceil(d) + probeChunks×16, maxRadius)`, using the player's preferred `probeChunks` capped at
   the server's.
5. Collect matches within the outline radius, nearest first, up to `maxMarkers`. Set
   `truncated` if more exist.
6. **Time budget.** `scanBudgetMicros` (default 2000) is the scan time of one server tick,
   **shared by every player's scan**, so radars add at most that to a tick however many players
   scan. A scan that runs out keeps its object and continues on a later tick (before any new scan
   for that player); its result is published when it completes, and the previous result stands
   meanwhile. So a heavy scan (tracking stone) arrives late but always arrives, and a continued
   scan ends with exactly the result of one unbounded step. Core's `ScanBudget` implements this
   for every platform:
   - the players are visited in an order that rotates by one each tick, so one heavy scan early in
     the list cannot take every tick's budget;
   - the scan checks its deadline only between 16×16×16 sections, so the deadline leaves room for
     one more: the longest section seen lately, decaying ~2% a tick, at most a quarter of the
     budget. Measured on 1.20.1: longest tick 1,963 µs against 2,000.

**Charge** drains `drainPerSecond` while a radar is active. The mods write it to the item every
100 ticks (5 s), not every tick, so the inventory sync stays rare; Paper has its own write rule
(§8). At zero the status becomes `NO_CHARGE`. A radar in a denied dimension does not drain. A radar
whose target the rules no longer allow (or whose stored spec does not parse) counts as no radar,
so the client sees `IDLE`; the protocol has no status for it.

**What the server sends.** It sends only what the features allow:
- no markers when outlines are off;
- no nearest position when arrow, beep and distance are all off;
- the nearest position rounded to `arrow.precisionBlocks` (1 = exact; 16 = the chunk centre);
- when no markers are sent, an `outlineRadius` measured to that rounded nearest position (even
  when the nearest itself is withheld), because the exact radius would give away the exact
  distance the rounding hides. `totalMatches` is still counted within the exact radius.

## 6. Client

### Radar screen (opened by use, or by the "Open Ore Radar" keybind, unbound by default)

It is inventory-style and does not pause the game; the world shows behind it, lightly dimmed. The
panel uses a vanilla-like beveled grey container look, drawn from our own generated nine-slice.

- **Left: vertical category tabs** (icons, as in the creative inventory):
  - "All", left out when a single category tab already lists every entry (the default, ores
    only)
  - the server's categories
  - "★ Favourites" and "⟲ Recent", kept client-side per server address (singleplayer: per world
    name) in the sidecar `config/oreradar-client-data.json`, written atomically. They are not in
    the TOML. Addresses are compared lowercased, with `:25565` dropped.
  - the ⚙ settings tab. All tabs share one column; when they outnumber the grid's rows the grid
    grows rows, and on a small screen the tabs close up and then scroll.
- **Top: a search box** that is focused on open. It matches the name or id, and also takes
  `@mod` to filter by namespace and `#tag` to match a tag. Esc clears it, then closes the screen.
- **Centre: a scrollable grid of 9×5 slots** showing each entry's block item:
  - Hover shows a tooltip with the name, id or spec, "N variants", and the mod name.
  - Left-click tracks the entry. Right-click toggles favourite, shown by a star in the slot corner.
  - The entry being tracked has a highlighted slot frame.
- **Right: the preview panel.**
  - The current or hovered target as a large, slowly rotating 3D block.
  - Its name and mod.
  - Status, from the last scan: "Found 14 within 38 m · nearest 12 m ↓4", "Nothing within 96 m",
    "Out of charge", "Off", or "Disabled in this dimension".
  - A vertical charge bar with "⚡ 31,200 / 36,000 (52 min)".
  - **Toggle buttons:** outlines, arrow, beep and power on/off. A button the server forbids is
    greyed out, with a "Disabled by this server" tooltip.
  - A **probe-range slider**, 0…server `probeChunks` chunks; its maximum stores −1, so it keeps
    following the server's value.
  - A **Clear target** button.
- **Settings:** a gear tab opens a client-settings page (colours, HUD anchor, beep volume and so
  on) that edits `oreradar-client.toml` live.
- **Entry points:** the ModMenu entrypoint (Fabric, optional) and the NeoForge / Forge
  config-screen extension point open this same settings page.
- The "Open Ore Radar" key works without a radar too: selection is disabled with "Hold a radar to
  choose its target". The screen reopens on the session's last tab (the sidecar holds only the
  lists). The client TOML is re-read on every join, so hand edits apply without a restart.

### HUD

- An arrow under the crosshair (or at `hud.anchor`) points to the nearest match, with the
  distance and ↑/↓ height difference. It shows the status for nothing found, out of charge and
  dimension disabled, and hides for `OFF` and `IDLE`.
- An optional small charge indicator.

### Outlines

Merged vein silhouettes are drawn through walls, with a faint fill, and the nearest vein brighter
(port 1.0's merger and projection). `nearestPos` may be rounded, so "the nearest vein" is the one
touching the exact nearest marker (diagonals included), drawn in `nearestColor`, thicker and
pulsing; the arrow and distance use the rounded position.

- **Forge 1.20.1 (47):** capture the camera at `AFTER_PARTICLES` / `AFTER_WEATHER`. Forge 47
  fires `AFTER_LEVEL` with the projection PoseStack, which mirrors the overlay.
- **Fabric:** `WorldRenderEvents`. Fabric API for 1.20.1 and 1.21.1 has no hook before the HUD
  (`HudRenderCallback` runs after chat), so a mixin at the head of `Gui.render` draws the
  through-walls layer.
- **NeoForge 1.21.1:** `RenderLevelStageEvent` at `AFTER_PARTICLES` / `AFTER_WEATHER`, with the
  event's model-view matrix (its pose stack holds no camera on 1.21.1).

1.0 projected to a HUD in clip space so the outlines survive shader packs (Oculus / Iris); keep
that approach, or prove a world-space pass survives Iris and Oculus too. When `throughWalls` is
off, depth-test the outlines: the same clip-space projection drawn during world rendering with
depth (Forge and NeoForge `AFTER_WEATHER`, rebinding the main target under Fabulous; Fabric
`WorldRenderEvents.LAST`).

### Beep

The interval shrinks with distance. It is never faster than the server's `beep.minIntervalTicks`
and is silent beyond `beep.maxRange`. The client chooses the sound, volume and pitch.

### Client limits are capped by the server

Anything the server forbids is off, whatever the client file says. The client's `probeChunks`,
`maxOutlines` and `activeFrom` are clamped to the server's values.

**Energy settings in singleplayer.** The item bar, tooltip and drain estimate use the server's
capacity and drain, which the client learns from the config packet. While a server runs in the
same JVM (singleplayer, LAN host) that server owns these shared settings from start to stop, and
the client's apply/reset on join and leave change nothing: the client leaves the world before the
integrated server writes the radars' last drain.

### On a server without Ore Radar (or an incompatible protocol)

The radar does nothing, and the screen says "This server does not run Ore Radar". There is no
client-only scanning; the radar never x-rays where the server has not agreed. Singleplayer works
through the integrated server. A Paper server with the plugin is such a server too: it serves
every player as a vanilla client (§8).

**The other way round,** a server with the mod needs the mod on every client: a client without it
cannot load a chunk that holds a charging station. Forge 1.20.1 refuses such a client at login
with its own message. Fabric and NeoForge let it in, so the mods' description and the README say
"install it on the server and on every client; for players without mods, use the Paper plugin".

## 7. Network protocol (version 1)

**Wire format.** Every payload is raw bytes encoded and decoded by core's `RadarBuf` codec, which
is the single implementation, so every mod jar speaks the same bytes:
- VarInt as in Minecraft;
- strings as VarInt byte length + UTF-8, 32767 max;
- long as 8 bytes big-endian;
- `BlockPos.asLong` packing: X 26 bits << 38 | Z 26 bits << 12 | Y 12 bits.

**Loaders.**
- 1.20.1: one channel per id below. Fabric uses `ServerPlayNetworking`; Forge uses an
  **EventNetworkChannel** (raw payload), because `SimpleChannel` would add a discriminator byte.
  Either way the bytes are core's alone.
- 1.21.1: `CustomPacketPayload` types with a `StreamCodec` that reads or writes the remaining
  bytes through core.
- Register NeoForge payloads `optional()`, so a client with the mod can join servers without it.

**C→S packets:**

| Channel | Body |
|---|---|
| `oreradar:hello` | varint protocol(=1), byte activeFrom, varint probeChunks (−1 = server max), byte flags (bit0 wantsOutlines, bit1 wantsArrow, bit2 wantsBeep) |
| `oreradar:prefs` | same body as hello; sent whenever the client changes these |
| `oreradar:select` | varint slot (0–35 inventory, 40 offhand), string spec ("" = clear) |
| `oreradar:power` | varint slot, bool on |

**S→C packets:**

**`oreradar:config`.** Sent once per session after the handshake below, and again after
`/oreradar reload` and every datapack reload:

- varint protocol
- the limits:
  - varint maxRadius, varint probeChunks, varint maxMarkers, varint scanIntervalTicks
  - byte activeFromMax
  - varint featureFlags:
    - bit0 outlines
    - bit1 outlinesThroughWalls
    - bit2 arrow
    - bit3 distance
    - bit4 beep
    - bit5 energy
    - bit6 externalChargers
  - varint arrowPrecisionBlocks, varint beepMinIntervalTicks, varint beepMaxRange
  - varint chargeCapacity, varint drainPerSecond
- the categories: varint n, then per category:
  string id, string name, bool nameIsTranslationKey, string iconBlockId
- the catalog: varint n, then per entry:
  string spec, string iconBlockId, string name, bool nameIsTranslationKey, varlong categoryMask,
  varint memberCount

**`oreradar:scan`.** Sent after each scan of a player who has an active radar, and once with
status `IDLE` when a radar stops being active:

- byte status: 0 IDLE, 1 OFF, 2 NO_CHARGE, 3 NONE_FOUND, 4 FOUND, 5 DIMENSION_DISABLED
- string spec
- varint slot (−1 = none)
- varint charge
- if status = 4 (FOUND):
  - byte hasNearest, then long nearestPos if set
  - varint outlineRadius, varint totalMatches, bool truncated
  - varint n, then n × long pos

**Handshake: hello, then config.** The mods talk only to mod servers.
- The client sends `hello` once per connection, after joining. The server answers with `config`.
- `config` goes out once per session, in answer to the first hello, and again after
  `/oreradar reload` and every datapack reload. A repeated hello, or identical prefs, change
  nothing; new prefs apply from the next scan.
- A client that never sends hello gets nothing.
- The Paper plugin registers no `oreradar:` channels and serves every player as a vanilla client
  (§8). A client with the mod gets no `config` there, so it stays in its "no server" state (§6).
- On a protocol mismatch the server logs it once per session and sends nothing more; the mod
  shows "incompatible server version" and stays inert.

**Size.** Vanilla caps a server-to-client custom payload at 1 MiB. Core trims the catalog to
`MAX_CONFIG_PAYLOAD_BYTES` (1,000,000) with a warning, dropping single blocks from the end before
any group; only `allowAll` in a big modpack gets there.

**Errors.** A malformed packet is ignored with a log line that cannot flood the log (debug level,
or once per channel and connection); it never disconnects the player or reaches the game.

## 8. Paper plugin

**Commands and permissions.**
- Command `/oreradar` with:
  - `give <player> [radar|station]`
  - `reload`
  - `info`
- Permissions:
  - `oreradar.admin` (op): give, reload, target and charge
  - `oreradar.use` (default true): use a radar, and `info`

**Radar item.** A `minecraft:compass` with PDC `radar=1` and a random `id` (§3), a custom name and
`CustomModelData` `7701`, plus lore showing the target and charge. It never stacks. The plugin
ships no resource pack; a server's own pack can give the radar a model through `7701`. A radar is
never a compass ingredient: any crafting grid holding one makes nothing.

**Recipes.** The same shapes as §3, registered as Bukkit `ShapedRecipe`s with a
result-item factory.

**Charging station.** A `minecraft:lodestone` item with PDC `station=1`.
- **Storage:** the item keeps `buffer`; each station is an entry `station/<x>/<y>/<z>` in its
  chunk's PDC holding `buffer`, `radar_slot` and `fuel_slot` (serialized items).
- **Placing it** records the location in the chunk PDC.
- **Breaking it** drops the station item (keeping its buffer in the PDC) and its contents.
  Explosions always drop it; pistons and mobs cannot move it.
- **Right-click** opens a 9-slot GUI:
  - a radar slot and a fuel slot;
  - filler panes that show the buffer and a charge bar in their names and lore.
- Fuel only, with the same fuel table; Paper has no energy API. The radar slot holds exactly one
  radar.
- Hoppers, comparators and the lit front do not apply to a plain lodestone.
- Only the plugin can read what a station holds. Before removing the plugin, take the radars and
  fuel out of the stations; otherwise they come back only when the plugin does.
- **Cancel vanilla lodestone and compass linking** when the item is a radar or the block is a
  station.

**Vanilla clients**, which is how Paper serves every player:

| Feature | How |
|---|---|
| GUI | A paged chest GUI. "All" and the category tabs sit in the top row ("All" left out as on the mods, §6) and catalog icons fill the middle. The bottom row holds previous / next page, power, clear and search. Search prompts in chat: "type a name, or `cancel`". |
| Arrow | The compass needle itself, in every world: the radar's own lodestone target, set to the nearest match with `lodestoneTracked = false`, rounded per `precisionBlocks`. The player's compass target is never touched, so plain compasses and other plugins stay as they were. The needle stays put while its block still matches and the new nearest is within one needle frame (11.25°). From 1.20.5 the radar does not shimmer like a lodestone compass. Also an action bar line, `⛏ Iron Ore · 23 m ↓5 · ⚡ 64%`, refreshed every second while searching; a reason the radar is not searching (off, out of charge, disabled here) is shown once. |
| Outlines | Glowing `BlockDisplay` entities, one per marker up to `paper.maxDisplayOutlines` (default 48). Each shows the matched block at scale 1.01 with glow colour from the config. They are visible only to that player (`setVisibleByDefault(false)` + `showEntity`), are removed and reused on each scan, are not persistent (`setPersistent(false)`), and are cleaned up on quit, plugin disable and world change. |
| Beep | `player.playSound` at the player's position in the Players sound category, with the rate driven by distance. |

Every player gets core's default prefs, so on Paper the radar works from the hands only.
Built-in category names reach them as translation keys with an English fallback. With
`throughWalls=false` the glow cannot be hidden behind walls, so the plugin turns it off and only
the enlarged block shows.

**Charge writes.** Each item write (charge, needle) replays the hand's equip animation on a
vanilla client, so the drain of the radar in use is kept in memory and written to the item only:
- when the lore's 5% step changes (about every 3 minutes at the defaults), or the radar runs empty;
- when it stops being the radar in use (put away, switched off), and on quit, world change and
  plugin disable.

A radar that leaves the player first (dropped, put in a chest, dropped at death) keeps that debt
under its id and pays it when it is next in use, by anyone; a server restart forgets it. From
1.21.11 the item bar follows the charge live, because a change of damage alone does not replay
the animation.

**Clients with the mod.** The plugin registers no `oreradar:` channels and serves every player as
a vanilla client. A client with the mod stays in its "no server" state (§6).

**Scanning** runs on the main thread in one task for all players, with the same shared per-tick
budget (core's `ScanBudget`, §5) and early-scan limit (`EarlyScans`). Each chunk first gets a
palette check, `Chunk#contains(BlockData)` for every state of the target (on 1.20.1 worked out
from known properties; a target with an unknown property skips the check, slower but correct);
chunks that pass are copied once into a snapshot and read block by block. Never load chunks.

**Groups** come from ore tags and the config's `groups` (§4). Paper reads no datapack groups: it
has no API for files inside datapacks, and Vein Mining is a mod.

**Config** lives at `plugins/OreRadar/oreradar-server.toml`: the schema of §10 without the keys
marked "mods only", plus a `[paper]` table. Folia is unsupported; say so in `plugin.yml`
(`folia-supported: false`). The plugin's own description in `plugin.yml` carries the "empty the
stations before removing the plugin" note.

## 9. Compatibility with 1.0 (the live DeceasedCraft world, Forge 1.20.1)

- The item id is unchanged, and so are the NBT keys `RadarTarget` / `RadarOff`. Existing spec
  strings parse. A missing `RadarCharge` means full.
- 1.0's client config file was also `config/oreradar-client.toml`; 2.0 replaces it (§10).

## 10. Config files (TOML, comment-preserving, written with defaults when missing)

- **Implementation.** Core has a small TOML reader and writer covering what this schema uses:
  - tables and dotted tables;
  - strings, ints, floats, bools;
  - arrays, including arrays of arrays;
  - comments.
- **Rewrites.** When it rewrites, it keeps the user's values and adds missing keys with their
  comments.
- **Bad values.** An unknown key is kept and warned about. A value out of range is clamped with a
  warning. A parse error keeps the previous config, logs the line, and **never crashes the
  game**.
- **A 1.0 client file** (ForgeConfigSpec's `[detection]`, `[outline]`, `[hud]`) is kept as
  `oreradar-client.toml.bak` and replaced with a fresh file; its settings are not carried over.

**Locations:**
- Mods: `config/oreradar-server.toml` (read on the server, the integrated one included) and
  `config/oreradar-client.toml`.
- Paper: §8.

### `oreradar-server.toml`

| Key | Default | Meaning |
|---|---|---|
| **[radar]** | | |
| `maxRadius` | 96 | blocks; hard cap on any scan (16–256) |
| `probeChunks` | 2 | max outline reach beyond the nearest match, in chunks (0–8); clients may choose less |
| `scanIntervalTicks` | 20 | 5–200 |
| `scanBudgetMicros` | 2000 | 200–20000; scan time per server tick for all players together (§5) |
| `maxMarkers` | 256 | 0–1024 |
| `activeFromMax` | `"INVENTORY"` | `HANDS` / `HOTBAR` / `INVENTORY` |
| `dimensionsDeny` | `[]` | e.g. `["minecraft:the_end"]` |
| **[features.outlines]** | | |
| `enabled` | true | |
| `throughWalls` | true | |
| **[features.arrow]** | | |
| `enabled` | true | |
| `precisionBlocks` | 1 | |
| `showDistance` | true | |
| **[features.beep]** | | |
| `enabled` | true | |
| `minIntervalTicks` | 4 | |
| `maxRange` | 64 | |
| **[targets]** | | |
| `allowAll` | false | every block is trackable, not only those the categories list (§4); its comment warns of the ~1 MB catalog in big packs |
| `denyList` | `[]` | never trackable |
| `groups` | `[]` | §4 |
| `readVeinMiningGroups` | true | mods only; also read `veinmining/groups.json` |
| **[categories.\<id\>]** | `ores` only; the other built-ins commented out (§4) | `name`, `icon`, `blocks`; also the allow-list |
| **[energy]** | | |
| `enabled` | true | |
| `capacity` | 36000 | |
| `drainPerSecond` | 10 | a full radar lasts 1 h of active use |
| `acceptExternalChargers` | true | mods only |
| `fePerCharge` | 10 | mods only |
| `trEnergyPerCharge` | 4 | mods only |
| **[station]** | | |
| `bufferCapacity` | 200000 | |
| `chargeRatePerTick` | 200 | |
| `maxInputPerTick` | 2000 | mods only; in charge units |
| `fuels` | see below | |
| **[paper]** | Paper only | `maxDisplayOutlines` = 48, `glowColor` = `"#E8F2FF"` |

The default `fuels`:
- `minecraft:redstone=4000`, `minecraft:redstone_block=36000`
- `minecraft:coal=1600`, `minecraft:charcoal=1600`, `minecraft:coal_block=16000`
- `minecraft:glowstone_dust=2000`
- `#c:dusts/redstone=4000`

### `oreradar-client.toml`

| Key | Default | Meaning |
|---|---|---|
| **[radar]** | | |
| `activeFrom` | `"HANDS"` | |
| `probeChunks` | -1 | −1 = the server's max |
| **[outlines]** | | |
| `enabled` | true | |
| `color` | `"#E8F2FF"` | |
| `nearestColor` | `"#FFFFFF"` | |
| `lineWidth` | 2.0 | |
| `fillAlpha` | 0.12 | |
| `glow` | true | |
| `maxOutlines` | 256 | |
| **[arrow]** | | |
| `enabled` | true | |
| `showDistance` | true | |
| `showHeight` | true | |
| **[hud]** | | |
| `anchor` | `"CROSSHAIR"` | or `TOP_LEFT` … `BOTTOM_RIGHT`, `HIDDEN` |
| `scale` | 1.0 | |
| `showCharge` | true | |
| **[beep]** | | |
| `enabled` | true | |
| `sound` | `"minecraft:block.note_block.pling"` | |
| `volume` | 0.6 | |
| `pitch` | 1.4 | |

## 11. Commands (mods)

`/oreradar` with:
- `reload` (op level 2): re-reads the server toml and re-sends config to everyone;
- `info`: prints the effective rules, plus the last scan's time in µs, ticks and match count for
  the player running it;
- `target <players> <spec|clear>` (op level 2): sets the target of each player's held radar.
  It is for admins and for tests, and is validated against the rules like a player's choice;
- `charge <players> <amount|full|empty>` (op level 2): sets the charge of the held radar.

Paper's `/oreradar` has the same subcommands, plus `give`. Its `info` also prints "Scan time per
tick over the last 5 s: A µs on average, M µs at most; the budget, shared by all players, is B µs"
(`ScanBudget.recent()`): Paper's `/mspt` cannot show the radar's cost, because queued tasks
between ticks soak up added work.

## 12. Testing (each module proves itself before it is called done)

- Dev servers bind 127.0.0.1 on their vertical's ports: 1.20.1 uses 25611 (Forge), 25612
  (Fabric) and 25613 (a vanilla server, for "no Ore Radar" tests); 1.21.1 uses 25621 (NeoForge)
  and 25622 (Fabric); Paper uses 25631–25633 (`tools/test-paper/server.sh`). The DeceasedCraft
  compat rig uses display `:77` and 127.0.0.1:25601.
- **Never touch `/opt/minecraft`** or the live ports: 25565, 25566, 25575, UDP 24454. Each test
  vertical gets its own ports and X display:

  | Vertical | Ports | Display |
  |---|---|---|
  | 1.20.1 | 25611–25619 | `:81` |
  | 1.21.1 | 25621–25629 | `:82` |
  | Paper | 25631–25639 | `:83` |

- **Run heavy work through `tools/limited.sh`** (CPU weight 20, `nice 19`, 9 GiB cap, no swap).
  A player is online on the live server. Gradle uses `-Xmx2G`; dev servers use 2G, dev clients 3G.
- **Core:** JUnit tests for:
  - TOML round-trip, including comments and unknown keys;
  - designation parsing;
  - encode → decode of every packet;
  - BlockPos packing against vanilla's;
  - the geometry merger (port of 1.0's `GeometryCheck`).
- **Each loader:**
  - The dedicated server boots with the mod, writes `oreradar-server.toml`, and `/oreradar info`
    works.
  - A GameTest (Fabric GameTest API or NeoForge / Forge GameTest) places ores, gives a radar with
    a target, runs a scan, and asserts the nearest match, the marker count, the rounding,
    `maxRadius`, the deny list, the drain and the station's charging.
  - The dev client reaches the title screen under Xvfb.
  - Screenshots, taken with the DeceasedCraft client rig (not in this repository) or
    equivalents, show the radar screen and the outlines in a test world.
- **Paper:**
  - Boots on Paper 1.20.1, 1.21.1 and the latest, and writes its config.
  - `/oreradar give` works.
  - A scan runs, driven by commands from the console.
  - The display entities appear and are cleaned up.
