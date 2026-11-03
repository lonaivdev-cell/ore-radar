# Publishing Ore Radar 2.0.0 on Modrinth

Everything here is done by hand on modrinth.com while logged in to your own account. Nothing in
this folder uploads anything, and no token is needed.

Files used: `project.json` (the project's fields), `description.md` (the page body), `icon.png`,
`gallery/` with `gallery.json` (titles and captions), `versions.json` (one entry per jar), and the
jars in `../dist/`.

## 0. Before you start

1. **Get the files.** On any computer, open the release
   <https://github.com/lonaivdev-cell/ore-radar/releases/tag/v2.0.0> and download the five jars,
   `SHA256SUMS` and `modrinth-assets.zip` (this folder: icon, gallery, page text and the JSON
   files). Skip step 1 below; the release jars are the ones it builds.
2. Check that the slug is still free: <https://api.modrinth.com/v2/project/ore-radar> must answer
   "404 Not Found" (it did on 2026-11-02).

The author is `GLEYSSON` (`LICENSE`, `mod_authors` in `../gradle.properties`, every jar). The
source is public at <https://github.com/lonaivdev-cell/ore-radar>.

## 1. Build and check the jars

```bash
cd ~/oreradar
./build.sh                      # core tests, all five jars, dist/SHA256SUMS
(cd dist && sha256sum -c SHA256SUMS)
```

Upload only the five jars in `dist/`, and only after this succeeds.

## 2. Create the project

1. On modrinth.com: your avatar → **Projects** → **Create a project**.
2. Name `Ore Radar`; URL `ore-radar`; visibility as you like (it stays a draft until it is
   approved); summary: copy `summary` from `project.json`. Create it.

## 3. Project settings

In the project page, open **Settings**:

1. **General:** upload `icon.png`. Check name and summary. Where it asks on which side the project
   runs: client **optional**, server **required** (the mods need both sides, the Paper plugin only
   the server).
2. **Tags:** categories `Utility` and `Equipment`.
3. **License:** `MIT`.
4. **Links:** Source `https://github.com/lonaivdev-cell/ore-radar`, Issues
   `https://github.com/lonaivdev-cell/ore-radar/issues` (also in `project.json`).
5. **Description:** paste all of `description.md`.

## 4. Gallery

Open **Gallery** → **Upload an image** for each file in `gallery/`, in the order of
`gallery.json`, with its title and description. Mark `1-radar-screen.png` as featured.

## 5. Versions

Open **Versions** → **Create a version**, once per entry in `versions.json`:

| Field | Value |
|---|---|
| File | the `file` of the entry (a jar in `../dist/`) |
| Version name | `name` |
| Version number | `version_number`, e.g. `2.0.0+1.20.1-forge` |
| Release channel | Release |
| Loaders | `loaders` |
| Game versions | `game_versions` (Paper: the 20 in `versions.json`, every Paper server from 1.20.1 to 26.3; Paper has none for 1.20.3, 1.21.2 or 26.1) |
| Environment | the mods: client and server, both required; Paper: server only |
| Dependencies | the Fabric jars: add **Fabric API** as *Required* (Team Reborn Energy is inside the jar, so do not add it) |
| Changelog | `changelog` |

Check that Modrinth shows the uploaded file's name exactly as in `dist/` before saving each one.

## 6. Submit

Open the project page, read it once as a player would, then press **Submit for review**. After
approval, `https://modrinth.com/mod/ore-radar` (the homepage written into the jars) goes live.
