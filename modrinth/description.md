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
| Forge 1.20.1 | `2.0.0+1.20.1-forge` | the same file |
| Fabric 1.20.1 | `2.0.0+1.20.1-fabric` + Fabric API | the same file and Fabric API |
| NeoForge 1.21.1 | `2.0.0+1.21.1-neoforge` | the same file |
| Fabric 1.21.1 | `2.0.0+1.21.1-fabric` + Fabric API | the same file and Fabric API |
| Paper 1.20.1 to 26.3 (tested: 1.20.1, 1.21.1, 26.3) | `2.0.0+paper` (a plugin) | nothing: plain vanilla clients |

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
- The Paper plugin's test suites pass on Paper 1.20.1, 1.21.1 and 26.3.
- **Not tested:** the other Paper versions in the list (1.20.2 to 1.21.11 and 26.1 to 26.2). The
  plugin uses only the 1.20.1 Paper API, so they should work, but if one does not, please open an
  issue.
- Outlines show with shaders: Iris and Complementary Reimagined (Fabric 1.20.1 and 1.21.1), and
  Oculus in the DeceasedCraft modpack (Forge 1.20.1, about 230 mods).
- In DeceasedCraft, Immersive Engineering's charging station charges the radar, and modded ores
  join the ore tabs on their own.
- The settings screen opens from Mod Menu, Catalogue and Forge's mod list.

## Coming from Ore Radar 1.0

Radars made with 1.0 keep their target and come fully charged, also after the world moves to
1.21.1.

License: MIT.
