# Changelog

## 2.0.0

The first public release. Ore Radar 1.0 was a Forge 1.20.1 mod for one server; 2.0 is a rewrite.

- **Loaders:** Forge 1.20.1 (the same jar runs on NeoForge 1.20.1), Fabric 1.20.1, NeoForge 1.21.1, Fabric 1.21.1, and a Paper plugin
  for 1.20.1 to 26.3 that works with plain vanilla clients. Tested on every Paper version in that
  range, and the mods at both ends of each loader's version range.
- **The server scans.** Clients only draw what the server sends. Scans never load chunks and
  share one time budget per server tick (2 ms by default) among all players.
- **Radar screen:** category tabs, search (`@mod`, `#tag`), favourites and recent targets, a
  preview with the scan status and charge, and switches for outlines, arrow and beep.
- **Charge and the Charging Station:** a full radar lasts an hour of searching. The station
  burns redstone, coal, charcoal or glowstone dust, and takes FE (Forge, NeoForge) or Team Reborn
  Energy (Fabric). Other mods' chargers can charge the radar directly.
- **Server rules:** the categories in `oreradar-server.toml` are what radars may track (only ores
  by default); `allowAll`, `denyList`, groups, Vein Mining datapack groups, and per-feature
  switches.
- **Coming from 1.0:** radars made with 1.0 keep their target and come fully charged, also after
  the world is upgraded to 1.21.1. The 1.0 client settings file is kept as
  `oreradar-client.toml.bak` and replaced.
