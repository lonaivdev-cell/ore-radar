# Ore Radar 1.20.1: how the client side fits together

`mc1201/` is complete on both loaders: common and server (DESIGN §3, 4, 5, 7, 9, 10, 11) and the
client (§6): the radar screen, settings page, HUD, outlines, beep, key binding, station screen and
item-model predicate. 22 GameTests per loader cover the server side; the client was proven in dev
clients on Xvfb `:81` (screenshots in `test-shots/1.20.1-{forge,fabric}/`). This file maps the seams
between the pieces, for anyone changing them and for the 1.21.1 port, which should keep the same
shape.

## Where the code is

| What | Where |
|---|---|
| Client code shared by both loaders | `net/loserver/oreradar/client/` in `common/src/main/java/` and, for the classes identical on 1.21.1 (`RadarClient`, `ClientBridge`, `ScanView`, `StatusText`, `RadarKeys`, `ToggleButton`), in `../shared/java/` (common compiles both against vanilla, client classes included) |
| Forge client glue | `forge/src/main/java/net/loserver/oreradar/forge/client/OreRadarForgeClient.java`, called from the mod constructor behind `Dist.CLIENT` |
| Fabric client glue | `fabric/src/main/java/net/loserver/oreradar/fabric/`: `OreRadarFabricClient` (the `client` entrypoint), `FabricClientNetworking`, `OreRadarModMenu` (compile-only ModMenu), `mixin/GuiMixin` (`oreradar.mixins.json`) |

Common and server code never touch client classes; keep it that way, because the dedicated server
loads every class in `common` that the server code references. The one door between them is
`ClientBridge`.

| Client class | Does |
|---|---|
| `RadarClient` | The client's state, client thread only: `oreradar-client.toml` (re-read on every join, saved 20 quiet ticks after a change), the `ClientData` sidecar, the session (`Connection`: OFFLINE, WAITING, READY, INCOMPATIBLE), hello/prefs, config/scan decoding with malformed payloads logged once per channel and connection, the beep, and the `itemState` model predicate. Installs itself as the `ClientBridge` in `create(configDir, modNames)`. |
| `CatalogModel` | The config packet's catalog: names, stand-in icons for blocks without an item (water, lava → buckets), the search (`@mod`, `#tag`, name, id). A tracked spec the catalog does not list (every 1.0 radar's `#forge:ores/<m>`: 1.0 stored tags, 2.0 lists groups) gets its preview block from `TargetNames.derive`, the block the synced tags say stands for it. |
| `ScanView` | A `FOUND` scan as outline geometry: the vein touching the exact nearest marker (diagonals included) apart from the rest, mined blocks dropped at once. |
| `FrameCapture`, `OutlineRenderer` | 1.0's clip-space projection: the loaders capture the camera each frame; `renderHud` draws the through-walls outlines as a HUD layer, `renderWorld` the depth-tested ones during world rendering. |
| `RadarHud`, `StatusText`, `RadarKeys` | The arrow, distance and charge readout; status lines; the "Open Ore Radar" key (unbound by default). |
| `screen/RadarScreen`, `RadarSettingsScreen`, `ChargingStationScreen`, `ToggleButton`, `GuiSprites` | The §6 screens. The radar screen leaves out "All" when one category tab lists every entry, and reopens on the session's last tab. The settings page is generated from `ClientConfig.SCHEMA` and is the same page from the gear tab, Forge's Config button and ModMenu. |

**Loader hooks** (both call the same `RadarClient` methods):

| Hook | Forge 47 | Fabric |
|---|---|---|
| join → `onJoin()`; leave → `onDisconnect()` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` | `ClientPlayConnectionEvents.JOIN` / `DISCONNECT` (via `minecraft.execute`: it may fire off-thread) |
| `tick()` | `ClientTickEvent` END | `END_CLIENT_TICK` |
| camera capture | `RenderLevelStageEvent` `AFTER_PARTICLES` and `AFTER_WEATHER` (`AFTER_LEVEL` gets the projection pose and mirrors the overlay) | `WorldRenderEvents.LAST` |
| depth-tested outlines | `AFTER_WEATHER`, rebinding the main target under Fabulous | `WorldRenderEvents.LAST` |
| through-walls outlines | `registerBelowAll` overlay | `GuiMixin`, at the head of `Gui.render` (no Fabric API hook runs before the HUD on 1.20.1) |
| HUD | overlay above `CROSSHAIR` | `HudRenderCallback` |
| key, model predicate, station screen | `RegisterKeyMappingsEvent`; `ItemProperties.register` and `MenuScreens.register` in `FMLClientSetupEvent.enqueueWork` | `KeyBindingHelper`; the same two calls in the entrypoint |
| settings entry point | `ConfigScreenHandler.ConfigScreenFactory` | ModMenu entrypoint |

## The bridge: `net.loserver.oreradar.client.ClientBridge`

One interface with no-op defaults; `ClientBridge.get()` is `ClientBridge.NONE` on a dedicated
server and `RadarClient` on a client. All three methods are called **on the client thread**.

| Method | Called by | `RadarClient` does |
|---|---|---|
| `void openRadarScreen(int slot)` | `RadarItem.use` on the client (right-click without sneaking) | opens the radar screen for that slot |
| `void receive(RadarProtocol.Channel channel, byte[] payload)` | the loader's network glue, for `CONFIG` and `SCAN` | decodes with core; `ProtocolMismatchException` on `CONFIG` makes the session INCOMPATIBLE ("incompatible server version", inert) |
| `Component targetName(String spec)` | `RadarItem`'s tooltip (`TargetNames.of`) | the catalog entry's name, or `null` to let common derive one from the registries |

Slots use the protocol numbering everywhere: 0-8 hotbar, 9-35 main inventory, 40 off hand.
`RadarItem.slotOf(player, hand)` converts a hand; `RadarClient.stackIn(player, slot)` goes back.

## Network

**Server-to-client receivers** forward to the bridge:

- Forge: `ForgeNetworking` (one raw `EventNetworkChannel` per channel id, both sides) hands
  `NetworkEvent.ServerCustomPayloadEvent` payloads to `ClientBridge.get().receive(...)` via
  `enqueueWork`, wrapped so a failure is logged and never thrown (Forge may run the work at once on
  the same thread). The channels' version checks let a client with Ore Radar join any server, and
  make a Forge server refuse, at login, a client without it (DESIGN §6).
- Fabric: `OreRadarFabricClient` calls `FabricClientNetworking.registerClientReceivers()`
  (`ClientPlayNetworking.registerGlobalReceiver`, then `client.execute`).

**Client-to-server:** `Services.PLATFORM.sendToServer(channel, bytes)`, client side only, while
connected; `RadarClient` sends these. The payload is core's bytes, nothing around them, so both
loaders speak the same bytes:

```java
Services.PLATFORM.sendToServer(RadarProtocol.Channel.HELLO, new HelloPacket(clientConfig.prefs()).toBytes());
Services.PLATFORM.sendToServer(RadarProtocol.Channel.PREFS, new PrefsPacket(prefs).toBytes());
Services.PLATFORM.sendToServer(RadarProtocol.Channel.SELECT, new SelectPacket(slot, spec).toBytes()); // "" clears
Services.PLATFORM.sendToServer(RadarProtocol.Channel.POWER, new PowerPacket(slot, on).toBytes());
```

What the server does with them (`server/RadarServer.receive`, `server/PlayerRadar`):

- **hello**, from the join event (Fabric's `ClientPlayConnectionEvents.JOIN`, Forge's
  `ClientPlayerNetworkEvent.LoggingIn`). The server answers the session's first hello with `CONFIG`
  and starts scanning for this player; a repeated hello only carries prefs. **Nothing is sent to a
  client that never said hello.** Another protocol version gets nothing at all (logged once per
  session). A server without Ore Radar, a Paper server with the plugin included, ignores hello.
- **prefs**: applies from the next scan; no new `CONFIG`; the same prefs again change nothing.
  (Prefs from a client that never said hello count as hello.)
- **select**: only if that slot holds a radar, and only if `TargetRules.resolve(spec)` is OK;
  otherwise the player gets an action-bar message ("cannot track", "no target") and nothing changes.
  Choosing a target also switches the radar on (1.0 behaviour).
- **power**: only if that slot holds a radar.

A changed target, radar, dimension or prefs, and a radar switched on or recharged, are scanned for
at once rather than at the player's next slot in the cadence. Core's `EarlyScans` lets three such
early scans run back to back, then one per `scanIntervalTicks`, so a client sending these every
tick cannot make the server scan for it every tick.

`CONFIG` is sent after the first hello and again after `/oreradar reload` and after every datapack
reload.

`SCAN`, per player who said hello:

| Status | When |
|---|---|
| `FOUND` / `NONE_FOUND` | after every finished scan (every `scanIntervalTicks`, staggered by entity id; a heavy scan may span ticks and arrive late) |
| `OFF`, `NO_CHARGE`, `DIMENSION_DISABLED` | when it changes |
| `IDLE` | once, when no radar is active any more |

`charge` in the packet is live: the item's charge minus the drain not yet written to it (the item
is written only every 100 ticks).

On a dimension change, drop a `FOUND` scan (its positions belong to the old level) but keep any
other status: the server sends `OFF`, `NO_CHARGE` and `DIMENSION_DISABLED` only when they change.

On disconnect `RadarClient` forgets the config and catalog and calls `EnergySettings.reset()`.

## Energy and item data

- `RadarClient` calls `EnergySettings.applyLimits(configPacket.limits())` when `CONFIG` arrives: the item bar, tooltip
  and drain estimate then use the server's capacity and drain. While a server runs in the same JVM
  (singleplayer, LAN) its config wins and `applyLimits`/`reset` change nothing: `EnergySettings.apply`
  marks the settings as the server's and `RadarServer.stop` releases them, because the client
  leaves the world before the integrated server writes the radars' last drain.
- Common code reads radar data with `RadarItem.target(stack)` (null = none), `RadarItem.isOff(stack)`,
  `RadarItem.charge(stack)` (missing key = full) and `RadarItem.storedCharge(stack)`; client code
  uses the same. The client never writes them: the server owns them; it sends select/power instead.
  The scan packet's `slot` names the active radar.
- A broken station's item holds `BlockEntityTag` `{Buffer, id}` when its buffer is not empty
  (`ChargingStationBlock.getDrops` adds the `id` that 1.21.1 needs to upgrade it), and no tag when
  it is, so it stacks with crafted ones.

## Item model predicate

`assets/oreradar/models/item/ore_radar.json` already has 1.0's overrides on predicate
`oreradar:state`: `0` idle (off or no target), `0.5` scanning (`ore_radar_scanning`, animated),
`1` found (`ore_radar_found`, animated). `RadarClient.itemState` gives 0 if off or no target, 1
if the last `SCAN` was `FOUND` for the slot holding that very stack, else 0.5. Both loaders register
it with `ItemProperties.register(RadarContent.RADAR.get(), OreRadar.id("state"), client.itemState)`
(Forge inside `FMLClientSetupEvent.enqueueWork`; Fabric's transitive access wideners open the
overload).

## Charging station menu

`station/ChargingStationMenu`, registered as `RadarContent.STATION_MENU` (a plain vanilla
`MenuType`, no extra data), shown by `ChargingStationScreen`.

**Data slots.** 1.20.1 sends each `ContainerData` value as a 16-bit short, so 32-bit values are split
in two. Use the accessors, not raw indices:

| Accessor | Slots |
|---|---|
| `menu.buffer()` | 0 (bits 0-15), 1 (bits 16-31) |
| `menu.bufferCapacity()` | 2, 3 |
| `menu.isCharging()` | 4 (1 while charging; the block's `lit`) |

**Layout** (GUI pixels, vanilla 176 x 166; constants in the menu):

| Element | Position |
|---|---|
| fuel slot item | 44, 35 (`FUEL_SLOT_X/Y`) |
| radar slot item | 116, 35 (`RADAR_SLOT_X/Y`) |
| inventory / hotbar | from 8, 84 / 8, 142 |

**Texture** `oreradar:textures/gui/container/charging_station.png` (256 x 256, by
`tools/textures.py`; slot frames, ghost icons and arrows are drawn in):

| Region | u, v | size | Use |
|---|---|---|---|
| background | 0, 0 | 176 x 166 | blit whole |
| empty charge bar interior | (80, 17 on screen) | 16 x 54 | frame at 79, 16 |
| bar fill | 176, 0 | 16 x 54 | blit the bottom `h = 54 * buffer / capacity` rows to (80, 17 + 54 - h) from v = 54 - h |
| lit arrow | 176, 54 | 13 x 7 | over the bar-to-radar arrow at (99, 39) while `isCharging()` |

The bar tooltip, per DESIGN §3: "Buffer: 12,345 / 200,000 ⚡" (`RadarItem.formatNumber` gives the
digit grouping the server uses).

## Lang keys and assets

`shared/resources/assets/oreradar/` is shared with 1.21.1: lang (`en_us`, `pt_br`; add every key
to both), models, the blockstate, textures and `icon.png`. Key families: `item.`/`block.`/
`container.oreradar.*`, `oreradar.category.*` (built-in categories arrive as these keys),
`oreradar.tooltip.*`, `oreradar.msg.*` (action bar), `oreradar.target.unknown`,
`oreradar.command.*` and `oreradar.info.*` (server), and on the client `oreradar.screen.*`,
`oreradar.hud.*`, `oreradar.settings.*`, `key.oreradar.*`, `key.categories.oreradar`.

Textures are drawn by stdlib Python and come out byte-identical every run:
- `tools/textures.py shared/resources/assets/oreradar`: the item (`ore_radar`, `_scanning`, `_found`,
  animated), the station's block faces (`front_on` animated), `textures/gui/container/charging_station.png`
  and `icon.png` (128 x 128; Fabric `icon`; Forge's `logoFile` is a copy the build packs at the jar's
  root as `oreradar.png`, because Catalogue, the mod list of many Forge packs, skips a path with a `/`).
- `tools/gui_textures.py shared/resources/assets/oreradar`: `textures/gui/radar.png`, the radar screen's nine-slice, tabs and button
  icons (`GuiSprites` holds the regions).

## Other facts worth knowing

- **Running the client.** `./gradlew -p mc1201 :forge:runClient` / `:fabric:runClient` load only the
  release mod; `-Poreradar_client_args=...` adds game arguments (quick-play, username, window size).
  Dev servers listen on 127.0.0.1:25611 (Forge) and 25612 (Fabric); the 1.20.1 X display is `:81`
  (DESIGN §12). `tools/test/` has the rig used for the screenshots: `xvfb.sh` (refuses `:77`),
  `devserver.sh` (forge, fabric, or vanilla on 25613), `devclient.sh`, `scene.sh`, `shot.sh`,
  `capture.sh`, `xdo.sh`.
- **The GameTest mod.** `forge/src/gametest`, `fabric/src/gametest`, `common/src/gametest` and `../shared/gametest-java` build a
  second mod, `oreradar_gametest`, loaded only by `runGameTestServer` / `runGametest` and
  `runBenchServer` (`mc1201/gametest.gradle`). Forge's GameTest run uses a flat level `flat` in
  `runs/mc1201-forge/gametest` (task `flatGameTestWorld`).
- `RadarServer.get()` is non-null on the client thread in singleplayer (the integrated server);
  don't use it for client logic. The client's truth is the last `CONFIG` and `SCAN`.
- The server never x-rays for the client: no `CONFIG` within 5 s of joining means the server does
  not run Ore Radar (a Paper server with the plugin included), and the screen says so (DESIGN §6).
