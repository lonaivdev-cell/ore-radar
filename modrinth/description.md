A handheld radar. Choose a kind of block (iron ore, ancient debris, a modded ore) and the radar
finds the nearest one, then guides you to it with three aids:

- **see-through outlines** of the vein,
- an **arrow** with the distance and height difference,
- a **beep** that gets faster as you get closer.

The server does the scanning and sends each player only what they may see. Scans never load
chunks, and all players' scans share one small time budget per server tick (2 ms by default).

## Which file

| Server | File | Players need |
|---|---|---|
| Forge or NeoForge 1.20.1 | `2.0.0+1.20.1-forge` | the same file |
| Fabric 1.20.1 | `2.0.0+1.20.1-fabric` + Fabric API | the same file and Fabric API |
| NeoForge 1.21.1 | `2.0.0+1.21.1-neoforge` | the same file |
| Fabric 1.21.1 | `2.0.0+1.21.1-fabric` + Fabric API | the same file and Fabric API |
| Paper 1.20.1 to 26.3 | `2.0.0+paper` (a plugin) | nothing: plain vanilla clients |

**The mods go on the server and on every client.** A player without the mod cannot load the
chunks that hold a Charging Station. For players without mods, use the Paper plugin. In
singleplayer, install the mod as usual.

## Crafting

- **Ore Radar:** lightning rod on top; iron ingot, glass pane, iron ingot; redstone, compass,
  redstone.
- **Charging Station:** iron ingot, lightning rod, iron ingot; copper ingot, block of redstone,
  copper ingot; three iron ingots.

## Using it

- **Right-click** opens the radar screen: pick a target from the tabs, search by name, `@mod` or
  `#tag`, keep favourites, and switch outlines, arrow and beep. The ⚙ tab holds your own
  settings: colours, HUD position, beep sound and volume.
- **Sneak + right-click a block** tracks that block. **Sneak + right-click the air** switches the
  radar off or on.
- **Charge:** a full radar lasts one hour of searching. Recharge it in a Charging Station with
  redstone, coal, charcoal or glowstone dust, or with FE (Forge, NeoForge) or Team Reborn Energy
  (Fabric).

**On Paper** the radar is a compass: right-click opens a chest menu, the needle points to the
nearest match, matches glow through stone, and the action bar shows the distance and charge.

## For server owners

Everything is in `config/oreradar-server.toml` (Paper: `plugins/OreRadar/oreradar-server.toml`),
with a comment on every key; `/oreradar reload` applies changes.

- The **categories** are the tabs of the radar screen and also what radars may track. Only ores
  by default; stone, wood, nature, fluids and utility (spawners, chests) are ready to uncomment.
- Turn outlines, arrow or beep off for everyone, make the arrow vague (point to the chunk only),
  deny blocks or dimensions, or switch charge off.
- Commands: `/oreradar info`, and for operators `reload`, `target` and `charge` (Paper adds
  `give`).

## Tested

- Every mod jar passes its automated in-game tests (22 on 1.20.1, 26 on 1.21.1): nearest match,
  outline markers, arrow rounding, radius limit, deny list, charge drain, the station's fuel,
  energy input, automation and comparator, and 1.0 radars after a world upgrade.
- **Loader versions, on real servers:** Forge 47.0.0 to 47.4.28 (NeoForge 47.1 for 1.20.1 also
  loads the Forge jar), NeoForge 21.1.1 to 21.1.265, and Fabric from the oldest Fabric API for each
  version (0.83 on 1.20.1, 0.102 on 1.21.1, with Fabric Loader 0.15) to the latest. Each boots with
  the mod, and a bytecode check finds every loader call the jar makes, client code included.
- **Paper, every version listed** (20, from 1.20.1 to 26.3): an API check, then a bot that plays
  through the radar: target, outlines, compass needle, action bar, beep, drain, menus and the
  Charging Station. On 1.20.5 the bot cannot enter the Nether (a bug in the test bot that happens
  without Ore Radar too), so those three checks could not run there; the rest pass.
- Outlines show with shaders: Iris and Complementary Reimagined (Fabric 1.20.1 and 1.21.1), and
  Oculus in the DeceasedCraft modpack (Forge 1.20.1, about 230 mods).
- In DeceasedCraft, Immersive Engineering's charging station charges the radar, and modded ores
  join the ore tabs on their own.
- The settings screen opens from Mod Menu, Catalogue and Forge's mod list.

## Coming from Ore Radar 1.0

Radars made with 1.0 keep their target and come fully charged, also after the world moves to
1.21.1.

License: MIT.
