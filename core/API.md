# Ore Radar core: API guide

`net.loserver.oreradar.core` is pure Java 17 with no dependencies. Every platform module compiles
`core/src/main/java` into its own jar (DESIGN §2), so all five jars share one implementation of the
config files, the rules, the protocol, the scan and the energy math. Run the tests with
`gradle -p core test` (Java 17).

**Threading.** Unless a class says otherwise, it is not thread-safe: call it from the server thread
(server-side classes) or the client/render thread (client-side classes). Immutable value classes
(`ServerConfig`, `ClientConfig`, packets, `ScanResult`, `OutlineGeometry`, a built `TargetRules`)
can be shared freely.

**Positions** are packed longs, bit-identical to vanilla `BlockPos.asLong` (`BlockPosPacking`). On
1.20.1/1.21.1 you can pass `BlockPos.asLong(...)` values straight in and out.

## Packages

| Class | What it is for |
|---|---|
| `ActiveFrom` | `HANDS < HOTBAR < INVENTORY`; `min(a, b)` combines two limits. Ordinal = wire byte. |
| **config** | |
| `ServerConfig` | `oreradar-server.toml`. `load(path, previous, paper)` → `LoadResult`; typed getters for every §10 key; `limits()` for the config packet; `with(setting, value)`. Use `PAPER_SCHEMA`/`paper=true` on Paper (adds `[paper]`, leaves out the mods-only keys, whose getters then return a fixed value). |
| `ClientConfig` | `oreradar-client.toml`. `load(path, previous)` replaces a 1.0 file with a fresh one (the old one is kept as `.bak`). `prefs()` → what to send in hello/prefs. `with(...)` + `save(path)` for the settings screen. Favourites are not in it: they live in `ClientData`. |
| `ClientData` | `config/oreradar-client-data.json` (DESIGN §6): per-server favourites and recents. Key with `serverKey(address)` or `singleplayerKey(worldName)` (`sp:<name>`). `load(path)` → `LoadResult<ClientData>` (never throws; a corrupt file moves to `.bad`, lists start empty). `favourites(key)`, `recents(key)`, `isFavourite(key, spec)`, `setFavourite(key, spec, on)`, `addRecent(key, spec)`; the two setters return true when something changed, so `save(path)` then (atomic). Mutable; client thread. |
| `EffectiveClientSettings` | `of(clientConfig, limits)`: the client's settings capped by the server's. Drive the HUD, outlines and beep from this, never from `ClientConfig` directly. |
| `LoadResult` | `config()`, `warnings()` (log each), `fileWritten()`, `failed()` (parse/IO error: `config()` is the previous one). Loading never throws. |
| `Setting.*`, `ConfigSchema` | Schema of each file. `SCHEMA.settings()` lists every key with `description()`, `constraint()`, `defaultValue()`; `Setting.Int/Real` expose `min()/max()`, `Setting.Choice` exposes `values()`. Build the settings screen from this. |
| `CategoryDef` | A `[categories.<id>]` tab; the categories are also the allow-list (DESIGN §4). `BUILT_IN` holds the six §4 tabs, `DEFAULTS` the one a new file has (`ores`). `toPacket()` sends built-in tabs as translation keys `oreradar.category.<id>` while their English name is unchanged (the six keys are in the lang files). |
| `HudAnchor`, `Colors` | HUD positions; `#RRGGBB` parsing. Getters like `outlinesColor()` already return `0xRRGGBB`. |
| **designation** | |
| `Designation` | `ns:id` or `#ns:tag`, Minecraft's character rules; a bare path means `minecraft:`. |
| `TargetSpec` | What an item stores: `@group`, `#tag`, `ns:id` (≤ 256 chars). 1.0 specs parse unchanged. |
| `FuelEntry` | `item=charge` / `#tag=charge` from `[station] fuels`. Fuel designations are **item** ids/tags. |
| `PackGroupFile` | Parses one `data/<ns>/oreradar/groups.json` or `data/<ns>/veinmining/groups.json`. |
| `GroupSources` | Group ids and merge order: `ores/<m>` (auto, always), `custom/<key>`, `pack/<key>`, where `key(prefix, firstDesignation)` is the first entry without `#` and with `:` → `.` (`#c:ores/zinc` → `c.ores/zinc`). Collisions get `~2`, `~3`, ... plus a warning; exact repeats are dropped. `custom(groups, warnings)`, `pack(files, includeVeinMining, warnings)`, `all(..., warnings)`. |
| `BlockLookup<B>` | **Implement this per platform** (`B` = `Block`, or `Material` on Paper): registry, tags, air, has-item, translation key. |
| `TargetRules<B>` | `build(config, lookup, packGroupFiles, warnings)` at start and on every reload. Then: `resolve(spec)` → allowed `Set<B>` or a refusal status; `specFor(block)` for sneak-use (group, else block, else null = untrackable); `isAllowed(b)`; `configPacket(config)` for clients (trimmed to one packet at build time). |
| **net** | |
| `RadarProtocol` | `VERSION = 1`, `Channel` enum (`oreradar:hello` ... `oreradar:scan`, with direction), slot numbering (0–35, off hand 40), hard decode caps. |
| `RadarBuf` | The codec. `Writer` / `Reader`; `RadarBuf.decode(bytes, X::decode)` requires every byte consumed. |
| `HelloPacket`, `PrefsPacket` (+ `ClientPrefs`), `SelectPacket`, `PowerPacket` | Client → server. |
| `ConfigPacket` (+ `Limits`, `Category`, `CatalogEntry`), `ScanPacket` (+ `Status`, `Found`) | Server → client. |
| `PacketException` | Any malformed payload. `ProtocolMismatchException` (a subclass) means another protocol version: handle it, don't disconnect. |
| `BlockPosPacking` | `asLong/x/y/z/offset`, identical to vanilla. |
| **scan** | |
| `RadarScan` | One server-side scan. Implement `RadarScan.World` (select a loaded chunk, palette pre-filter a section, report matching blocks); call `step(world, budget.deadline())` once per tick until it returns true, then read `result()`. |
| `EarlyScans` | One per player (DESIGN §5 "Early scans"). `want()` when what the radar shows changes; `tick(scanIntervalTicks)` every server tick; `take()` → true (using one of 3, refilled one per `scanIntervalTicks`) means start a scan from scratch now; call it only when no scan is due anyway, and `started()` whenever a scan starts. Server thread only. |
| `ScanBudget` | The scan time of one server tick, shared by every player (DESIGN §5). One per server: `perTick()` (`unlimited()` for GameTests). Each tick `startTick(config.scanBudgetMicros(), players.size())`, visit `players.get(playerAt(i))` for `i` in `0..size-1` (the order rotates every tick), skip new or continued scans once `expired()`, and pass `deadline()` to `step`. The `World` reports each section's time with `sectionScanned(nanos)`, so the deadline keeps room for one more (the longest section lately, decaying, at most a quarter of the budget). Optional: `spent(nanos)` per step and `recent()` → `Load(averageMicros, maxMicros)` over the last 100 ticks, for `/oreradar info`. Server thread only. |
| `ScanResult` | Nearest, distance, outline radius, exact total, markers. `toFound(settings, minY, maxY)` applies what the player may receive and the precision rounding. |
| `ScanSettings` | `of(serverConfig, clientPrefs)`: per-player effective scan settings (use `ClientPrefs.DEFAULT` for a client that never said hello, and on Paper). |
| `ActiveRadar` | `select(selectedHotbarSlot, activeFrom, slot -> State)`: which radar is active, or which one to report as OFF/NO_CHARGE. |
| `Precision`, `Beep` (+ `Beep.Timer`), `ChunkOrder`, `ScanMath`, `NearestSort` | Rounding, beep cadence and pitch, ring order, distances, primitive sort. |
| **energy** | |
| `ChargeMath` | `read(storedOrNull, capacity)` (missing = full), item bar width/colour, minutes left, item energy capability conversion (`receive`). |
| `ChargeDrain` | One per player: `tick(creative, drainPerSecond)` returns the charge to write now, every `WRITE_INTERVAL_TICKS` (100; 0 between). |
| `ChargingStation` | One per block entity: `tick(settings, fuelValue, radarCharge)`, `receiveEnergy(settings, offered, ratio, simulate)`, `comparatorLevel`, `canExtractRadar`. Persist `buffer()`. |
| **geometry** | |
| `OutlineGeometry` | `build(long[] markers)`: merged silhouette edges `(x, y, z, axis)` and exposed faces `(x, y, z, direction)`. Direction/axis ordinals are vanilla's (`DOWN UP NORTH SOUTH WEST EAST`, `X Y Z`); `faceCorners(dir)` gives the quad corners. Build it when a scan packet arrives, not per frame. |
| **toml**, **util** | `TomlReader`/`TomlWriter`/`TomlTable`: the comment-keeping TOML used by the configs. `Json` (`parse`, and `write` for maps, lists, strings, numbers, booleans, null), `LongHashSet`, `Deadline`, `AtomicFiles` (`write`, `backup` → `.bak`, `moveAside(file, suffix)`). |

## Server flow

1. **Start / `/oreradar reload`:**
   ```java
   LoadResult<ServerConfig> r = ServerConfig.load(configDir.resolve(ServerConfig.FILE_NAME), current, isPaper);
   r.warnings().forEach(LOG::warn);
   current = r.config();
   rules = TargetRules.build(current, lookup, packGroupFiles, warnings);   // log warnings too
   ```
   On a reload, send `rules.configPacket(current)` to every player who said hello.
   `packGroupFiles`: for every location of `oreradar/groups.json` and `veinmining/groups.json`,
   each pack's file in load order, parsed with `PackGroupFile.parse(location, source, json, warnings)`.
   `TargetRules` orders and applies `replace` itself.
2. **Hello / prefs:** decode with `HelloPacket.decode(bytes)`. On `ProtocolMismatchException`,
   remember the player as incompatible and send nothing else. Otherwise store `prefs()` and, for
   the session's first hello only, reply with the config packet. A repeated hello or equal prefs
   change nothing.
3. **Every tick**, call `budget.startTick(config.scanBudgetMicros(), online.size())`, then **per
   player**, in the order `online.get(budget.playerAt(i))` (scan every `scanIntervalTicks`,
   staggered by entity id):
   - Get `ScanSettings s = ScanSettings.of(config, prefs)`.
   - Early scans: call `early.tick(config.scanIntervalTicks())`, and `early.want()` whenever what
     the radar shows changed (radar, target, dimension, prefs, a radar becoming active). When no
     cadence scan is due and `early.take()` is true, drop any scan in progress and start a new one;
     call `early.started()` whenever a scan starts.
   - Pick the radar with `ActiveRadar.select(selected, s.activeFrom(), slot -> state)`. A slot is
     READY when it holds a radar whose spec resolves OK, is on, and has charge (or energy is off).
   - Not active: send `ScanPacket` with `selection.idleStatus()` (or `ScanPacket.idle()` once,
     when the radar stops being active).
   - Dimension denied: send status `DIMENSION_DISABLED`.
   - Otherwise run
     `new RadarScan(x, eyeY, z, config.maxRadius(), s.probeChunks(), s.maxMarkers(), minY, maxY)`
     with a `World` backed by `rules.resolve(spec).blocks()` that calls
     `budget.sectionScanned(nanos)` after each section, and call `step(world, budget.deadline())`,
     unless `budget.expired()`: then this tick's time is spent, so start or continue nothing more
     until the next tick. If `step` returns false, the shared budget ran out (DESIGN §5 "Time
     budget"): keep the scan object per player, continue it on a later tick (before starting any
     new scan for that player), and send the previous result meanwhile. A continued scan ends with
     exactly the result of one unbounded step. The `World` may drop its chunk reference between
     ticks: an interrupted chunk is selected again, and skipped if it was unloaded meanwhile.
   - Send `FOUND` with `result.toFound(s, minY, maxY)` if `result.found()`, else `NONE_FOUND`.
   - Drain charge with `ChargeDrain.tick(...)`; write the item when it returns more than 0. A
     missing charge value means full (`ChargeMath.read`).
4. **Select / power packets:** `SelectPacket.decode` checks the slot range and spec length only.
   Check that the slot holds a radar, then `rules.resolve(spec)`; refuse anything not `ok()`.
   An empty spec clears the target.

## Client flow

- Load `ClientConfig` at startup. Send `HelloPacket(config.prefs())` on join, and `PrefsPacket`
  whenever those settings change.
- Load `ClientData` at startup too (log its warnings). On join, pick the key:
  `ClientData.serverKey(serverData.ip)` or `ClientData.singleplayerKey(levelName)`. The screen's
  ★ tab shows `favourites(key)`, the ⟲ tab `recents(key)` (keep only specs present in the current
  catalog). Right-click → `setFavourite(key, spec, !isFavourite(key, spec))`; a tracked entry →
  `addRecent(key, spec)`. Call `save(path)` when either returns true; it is small and atomic.
- On `ConfigPacket`, compute `EffectiveClientSettings.of(config, packet.limits())` and keep the
  catalog for the screen. A catalog entry with `nameIsTranslationKey` is a lang key. Bit `i` of
  `categoryMask` means `categories().get(i)`. Some entries' `iconBlockId` blocks have no item
  (water, lava): map them to a bucket or another stand-in.
- On `ScanPacket` `FOUND`, build `OutlineGeometry.build(found.markers())` once. Draw the nearest
  from `found.nearestPos()` when `hasNearest()`.
- Beep: keep one `Beep.Timer` and call
  `tick(distanceToNearest or NaN, limits.beepMinIntervalTicks(), limits.beepMaxRange())` every
  client tick. Play at `Beep.pitch(config.beepPitch(), d, maxRange)`.
- On `ProtocolMismatchException` from the config packet, show "incompatible server version" and
  stay inert. No config packet at all means the server does not run Ore Radar.

## Networking glue

- Payloads are whole `byte[]`. Encode with `packet.toBytes()` and decode with `X.decode(bytes)`.
  On 1.20.1, read the `FriendlyByteBuf`'s readable bytes into a `byte[]`. On 1.21.1, use a
  `StreamCodec` that reads the remaining bytes.
- Decoders throw `PacketException` (unchecked) for garbage.
  - Catch `ProtocolMismatchException` inside your codec or handler: letting it escape a 1.21.1
    `StreamCodec` disconnects the player.
- A server-to-client custom payload must stay under vanilla's 1 MiB. `TargetRules` trims the
  catalog to `MAX_CONFIG_PAYLOAD_BYTES`, with a warning, so `configPacket()` always fits.

## Invariants worth knowing

- **Config loading never throws.**
  - A syntax error keeps the previous config and reports `line L, column C`.
  - A complete file is never rewritten. A file that lacks keys is rewritten with the user's values,
    comments and unknown keys kept.
  - Category tables the user deleted are not brought back.
- **Distances** are from the player's eye to the block centre. "Within radius R" (an integer) means
  `ceil(distance) <= R`.
  - `outlineRadius = min(ceil(nearest) + probeChunks * 16, maxRadius)`. Without markers,
    `toFound` measures it to the nearest rounded to `precisionBlocks` instead: the exact radius
    would hand a client the exact distance the rounding hides.
  - `totalMatches` is exact for that radius, however many markers were kept.
- **`truncated`** means more matches exist than markers were sent. It is true whenever outlines are
  off and something was found.
- **Precision rounding** uses a world-aligned grid, so the answer does not move with the player.
  `16` gives chunk centres. Y is clamped into the world.
- **Group ids** are stable across reordering, so a radar's stored `@group` keeps working:
  - `ores/<m>` comes from the material.
  - `custom/<key>` and `pack/<key>` come from the group's first designation (`#c:ores/zinc` →
    `custom/c.ores/zinc`, `minecraft:clay` → `pack/minecraft.clay`). Pack groups are ordered by
    file location, never by resource enumeration.
  - Only colliding keys depend on order: the later group gets `~2`, `~3`, ... and a warning.
    `TargetSpec.isValidGroupId` accepts that suffix.
- **Resolution cache:** `TargetRules.resolve` caches by spec string, bounded to 4096 entries, so
  specs sent by players cannot grow memory.
