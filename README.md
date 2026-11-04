# Ore Radar

A handheld radar. Choose a kind of block (iron ore, ancient debris, a modded ore) and the radar
finds the nearest one, then guides you to it with three aids:

- **see-through outlines** of the vein,
- an **arrow** with the distance and height difference,
- a **beep** that gets faster as you get closer.

The server does the scanning and sends each player only what they may see. It never loads
chunks, and all players' scans share a small time budget per server tick.

## Which file

| Server | File | Players need |
|---|---|---|
| Forge 1.20.1 (47.0+) or NeoForge 1.20.1 | `oreradar-2.0.0+1.20.1-forge.jar` | the same jar |
| Fabric 1.20.1 | `oreradar-2.0.0+1.20.1-fabric.jar` + Fabric API | the same jar and Fabric API |
| NeoForge 1.21.1 | `oreradar-2.0.0+1.21.1-neoforge.jar` | the same jar |
| Fabric 1.21.1 | `oreradar-2.0.0+1.21.1-fabric.jar` + Fabric API | the same jar and Fabric API |
| Paper 1.20.1 to 26.3 | `oreradar-2.0.0-paper.jar` (a plugin) | nothing: plain vanilla clients |

**Mods: install on the server and on every client.** A player without the mod cannot load the
chunks that hold a Charging Station (Forge turns them away at login; Fabric and NeoForge let
them in, with holes in the world). For players without mods, use the Paper plugin. In
singleplayer, install the mod as usual. Team Reborn Energy comes inside the Fabric jars.

**Paper: install the plugin only.** Every player is served as a vanilla client. A player who has
the mod installed can still join; their mod simply stays idle there.

## Crafting

```
Ore Radar                     Charging Station
 .  L  .    L lightning rod    I  L  I    I iron ingot      L lightning rod
 I  G  I    G glass pane       C  B  C    C copper ingot    B block of redstone
 R  C  R    I iron ingot       I  I  I
            R redstone
            C compass
```

## Using it

- **Use (right-click)** opens the radar screen: pick a target, search (`@mod`, `#tag`), mark
  favourites with a right-click, and switch outlines, arrow and beep. The ⚙ tab holds your own
  settings (colours, HUD position, beep sound and volume, whether the radar works from your
  hands, hotbar or whole inventory).
- **Sneak + use on a block** tracks that block. **Sneak + use in the air** switches the radar off
  or on.
- The **Open Ore Radar** key (unbound by default) opens the screen without a right-click.
- **Charge.** A full radar lasts one hour of searching; the item bar shows what is left. Recharge
  it in a Charging Station with redstone, coal, charcoal or glowstone dust, or with FE (Forge,
  NeoForge) or Team Reborn Energy (Fabric) from cables and other mods' chargers.

**On Paper** the radar is a compass. Right-click opens a chest menu; the needle points to the
nearest match, matches glow, and the action bar shows the distance and charge. The station is
a lodestone with its own menu: radar on the left, fuel on the right.

## For server owners

The settings are in `config/oreradar-server.toml` (Paper: `plugins/OreRadar/oreradar-server.toml`).
Every key has a comment. `/oreradar reload` applies changes.

- `[categories.*]`: the tabs of the radar screen, and **what radars may track**. A new file has
  only `ores`. Stone, wood, nature, fluids and utility (spawners, chests) follow as commented-out
  examples: delete the `# ` in front of a table's lines to add it. To make your own tab:
  ```toml
  [categories.loot]
  name = "Loot"
  icon = "minecraft:chest"
  blocks = ["minecraft:chest", "minecraft:trapped_chest", "#minecraft:shulker_boxes"]
  ```
- `allowAll = true`: every block can be tracked. In a big modpack each player with the mod is then
  sent about 1 MB at join and after every reload.
- `denyList`: blocks and `#tags` that are never trackable. So is the `#oreradar:untrackable` tag.
- `groups`: blocks found together as one target, e.g. `[["#c:ores/zinc", "create:zinc_ore"]]`.
  Ore tags already make one target per ore, and datapacks' Vein Mining groups are read too (mods).
- `[features.*]`: turn outlines, arrow or beep off for everyone; `precisionBlocks` makes the arrow
  point only to the area (16 = the chunk).
- `[radar]`: `maxRadius` (96 blocks) and `dimensionsDeny`, e.g. `["minecraft:the_end"]`.
- `[energy]`: `enabled = false` makes radars free; `capacity` and `drainPerSecond` set how long
  they last. `[station]` `fuels` sets what each fuel item is worth.

Players' own settings are in `config/oreradar-client.toml`, also editable from the ⚙ tab. They
can only turn things down: whatever the server forbids stays off.

**Commands:** `/oreradar info` for everyone; for operators `reload`, `target <players> <spec|clear>`
and `charge <players> <amount|full|empty>`. Paper adds `give <player> [radar|station]` and the
permissions `oreradar.use` (everyone) and `oreradar.admin` (operators).

**Paper notes.**
- The radar is a compass with CustomModelData `7701`, so your own resource pack can give it a
  model. The plugin sends no pack.
- Only the plugin can read what a Charging Station holds. Before removing the plugin, take the
  radars and fuel out of the stations; otherwise they come back only with the plugin.
- Folia is not supported.

## Coming from Ore Radar 1.0

Radars made with 1.0 keep their target and come fully charged, also after the world moves to
1.21.1. The 1.0 client settings file is kept as `oreradar-client.toml.bak` and replaced by the
new one.

## License and building

MIT. To build from source, see [BUILDING.md](BUILDING.md).
