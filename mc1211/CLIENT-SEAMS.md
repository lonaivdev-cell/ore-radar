# Ore Radar 1.21.1: how the client side fits together

`mc1211/` is complete on both loaders: common and server (DESIGN §3, 4, 5, 7, 9, 10, 11) and the
client (§6). 26 GameTests per loader cover the server side; the client was proven in dev clients
on Xvfb `:82` (screenshots in `test-shots/1.21.1-{neoforge,fabric}/` and
`test-shots/1.21.1-fabric-iris/`). The client is mc1201's, ported: the same classes, bridge,
packets, menu data slots, screen layout and lang keys. `mc1201/CLIENT-SEAMS.md` maps all of that;
this file lists only what is different on 1.21.1.

## Where the code is

| What | Where |
|---|---|
| Client code shared by both loaders | `common/src/main/java/net/loserver/oreradar/client/`, the same classes as 1.20.1; the ones identical on both versions are only in `../shared/java/` |
| NeoForge client glue | `neoforge/src/main/java/net/loserver/oreradar/neoforge/client/OreRadarNeoForgeClient.java`, called from the mod constructor behind `Dist.CLIENT` |
| Fabric client glue | `fabric/src/main/java/net/loserver/oreradar/fabric/`: `OreRadarFabricClient` (the `client` entrypoint), `FabricClientNetworking`, `OreRadarModMenu` (compile-only ModMenu 11), `mixin/GuiMixin` (`oreradar.mixins.json`) |

**Loader hooks** (both call the same `RadarClient` methods as on 1.20.1):

| Hook | NeoForge 21.1 | Fabric API 0.116 |
|---|---|---|
| join → `onJoin()`; leave → `onDisconnect()` | `ClientPlayerNetworkEvent.LoggingIn` / `LoggingOut` | `ClientPlayConnectionEvents.JOIN` / `DISCONNECT` (via `minecraft.execute`) |
| `tick()` | `ClientTickEvent.Post` | `END_CLIENT_TICK` |
| camera capture | `RenderLevelStageEvent` `AFTER_PARTICLES` and `AFTER_WEATHER`: `getModelViewMatrix()` (the camera rotation) and `getProjectionMatrix()`. The event's pose stack is a new, empty one on 1.21.1; it holds no camera | `WorldRenderEvents.LAST`: `positionMatrix()` and `projectionMatrix()` |
| depth-tested outlines | `AFTER_WEATHER`, rebinding the main target under Fabulous | `WorldRenderEvents.LAST` |
| through-walls outlines | `RegisterGuiLayersEvent.registerBelowAll` | `GuiMixin`, at the head of `Gui.render(GuiGraphics, DeltaTracker)`: Fabric API for 1.21.1 has no HUD layers, and `HudRenderCallback` runs over the chat |
| HUD | layer above `VanillaGuiLayers.CROSSHAIR` | `HudRenderCallback` |
| key | `RegisterKeyMappingsEvent` | `KeyBindingHelper` |
| station screen | `RegisterMenuScreensEvent` | `MenuScreens.register` (opened by Fabric's transitive access wideners) |
| model predicate | `ItemProperties.register` in `FMLClientSetupEvent.enqueueWork` | `ItemProperties.register` (transitive access wideners) |
| settings entry point | `IConfigScreenFactory`, registered on the mod's `ModContainer` | ModMenu entrypoint |

## What 1.21.1 changed in the shared client code

- **Vertices.** `Tesselator.begin(mode, format)` hands out the `BufferBuilder`; vertices are
  `addVertex(...).setColor(...)`. `build()` returns null when nothing was added, so
  `OutlineRenderer` draws a mesh only when it got one.
- **Model-view stack.** `RenderSystem.getModelViewStack()` is a JOML `Matrix4fStack`
  (`pushMatrix`, `identity`, `popMatrix`).
- **HUD layers** get a `DeltaTracker`. `RadarHud` turns its arrow with
  `getGameTimeDeltaPartialTick(true)`, the partial tick the world was drawn with.
- **Screen backgrounds.** `Screen.render` now draws the background itself, then the widgets, and
  1.21 refuses to blur twice in one frame. So no screen calls `renderBackground` any more:
  `RadarScreen` draws its panel, grid and preview in `renderBackground(graphics, mouseX, mouseY,
  partialTick)`, as a container screen does, over a light dim instead of the blur; the station
  and settings screens use vanilla's background.
- **Nine-slices.** `GuiGraphics.blitNineSliced` is gone since 1.20.2. `GuiSprites` draws each
  nine-slice with nine blits, edges and centre stretched; the sheet's edges and centres are one
  colour along their length, so the pixels are the same as 1.20.1's tiling.
- **Widgets.** `EditBox` has no `tick()`; `mouseScrolled` takes `scrollX` and `scrollY`;
  `ContainerObjectSelectionList` takes `(minecraft, width, height, y, itemHeight)`.
- **Icons.** `Block.getCloneItemStack` takes a `LevelReader`: `CatalogModel` passes the client's
  level.

## Network

Payloads are `network/RadarPayload`, one `CustomPacketPayload` type per channel, whose body is
exactly core's bytes (GameTest `net_wire_bytes`). Server-to-client payloads reach
`ClientBridge.get().receive(...)` on the client thread on both loaders.

`Services.PLATFORM.sendToServer(channel, bytes)` sends hello, prefs, select and power, as on
1.20.1. NeoForge would throw for a channel the server has not offered, so on a server without Ore
Radar it sends nothing; a NeoForge server with Ore Radar offers every channel before the join
event, so hello goes out from `onJoin()`. Fabric always sends, and a server without Ore Radar drops
it. A Paper server, with or without the plugin, counts as a server without Ore Radar: the client
stays in its "This server does not run Ore Radar" state (`test-shots/1.21.1-*/no-server-paper.png`).

## Item data

The radar's state lives in data components, which sync to the client. Read them only through
`RadarItem.target(stack)`, `isOff(stack)`, `storedCharge(stack)` and `charge(stack)`: a 1.20.1
radar that the world upgrader converted keeps `RadarTarget` / `RadarOff` / `RadarCharge` in
`minecraft:custom_data` until the server next writes it, and those accessors read that too.

| | Component | Type |
|---|---|---|
| target spec | `oreradar:target` (`RadarContent.TARGET`) | string, at most 256 chars; absent = no target |
| switched off | `oreradar:off` (`RadarContent.OFF`) | bool, present (true) only while off |
| charge | `oreradar:charge` (`RadarContent.CHARGE`) | non-negative int; absent = full |

A station item keeps its buffer in `block_entity_data` (`ChargingStationItem.buffer(stack)`), with
the block entity's `id`; an empty station's item has no data, so it stacks with crafted ones.

## Running things

| Task | What |
|---|---|
| `./gradlew -p mc1211 :neoforge:runClient`, `:fabric:runClient` | dev clients, `-Xmx3G`, release mod only; `-Poreradar_client_args=...` adds game arguments |
| `:neoforge:runServer`, `:fabric:runServer` | dev servers on 127.0.0.1:25621 (NeoForge) and 25622 (Fabric) |
| `:neoforge:runGameTestServer`, `:fabric:runGametest` | the 26 GameTests; success is `All 26 required tests passed :)` |
| `:neoforge:runBenchServer`, `:fabric:runBenchServer` | scan timings on the dev world (`Ore Radar bench:` lines) |

`tools/test-1211/` is the 1.21.1 rig, on display `:82`; it reuses `tools/test/`'s Xvfb, screenshot
and xdotool helpers:
- `devserver.sh` (neoforge, fabric, or `paper` on 25623 with `PAPER_SERVER_JAR` set), `devclient.sh`,
  `scene.sh` and `capture.sh` work as their 1.20.1 namesakes;
- `fabric-iris.sh` adds Sodium, Iris, ModMenu and Complementary Reimagined, each checked against
  Modrinth's sha512;
- `console-check.sh` boots a dev server and checks `/oreradar info`, `/oreradar reload` and `/reload`.

NeoForge also packs `icon.png` at the jar's root as `oreradar.png`, the `logoFile`, because
Catalogue skips a path with a `/`.
