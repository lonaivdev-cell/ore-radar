#!/usr/bin/env bash
# capture.sh <neoforge|fabric> OUTDIR -- with devserver.sh and devclient.sh up, Tester joined and no
# screen open, builds the scene (scene.sh) and takes the 1.21.1 client's screenshots:
#   world-front.png, world-left.png,  outlines and the HUD arrow from three angles
#   world-right.png
#   world-wall.png                    outlines through a wall, up close
#   world-depth-tested.png            the server's throughWalls = false, from the left: only the ore
#                                     in plain view (top right) is outlined
#   radar-screen.png                  the radar screen (opened with the R key) under the default rules:
#                                     the Ores tab only, since "All" would list the same
#   radar-search.png                  a search over the allowAll catalog, with an entry's tooltip
#   settings.png                      the settings page, from the gear tab
#   station.png                       the charging station's screen, buffer tooltip shown
#   radar-tooltip.png                 the radar's tooltip at 5% charge, with the recharge hint
# The server config is switched with sed + /oreradar reload and left as it was found (allowAll
# false, through walls). Screen positions assume the 1280x720 window at GUI scale 3 that
# devclient.sh sets up: a 427 x 240 GUI, where the 5-row panel is 326 x 157 at (64, 41), its tabs
# 21 apart down its left side from y 45.
set -euo pipefail
here=$(dirname "$0")
source "$here/env.sh"
module=${1:?module: neoforge or fabric}
out=${2:?output directory}
mkdir -p "$out"
server_toml=$REPO/runs/mc1211-$module/server/config/oreradar-server.toml
cmd() { "$here/devserver.sh" cmd "$module" "$*"; sleep 0.3; }
xdo() { "$TEST_DIR/xdo.sh" "$@"; }
shot() { sleep "${2:-2}"; "$TEST_DIR/shot.sh" "$out/$1.png" >/dev/null; echo "$out/$1.png"; }
# A key of oreradar-server.toml, then a reload; the value is written as TOML.
server_set() { sed -i "s/^$1 = .*/$1 = $2/" "$server_toml"; cmd 'oreradar reload'; sleep 2; }
# GUI pixels to screen pixels at GUI scale 3.
px() { echo $(( $1 * 3 )); }

server_set allowAll false
server_set throughWalls true
"$here/scene.sh" "$module" build
sleep 3
"$here/scene.sh" "$module" front; shot world-front 4
"$here/scene.sh" "$module" left;  shot world-left 3
"$here/scene.sh" "$module" right; shot world-right 3
"$here/scene.sh" "$module" wall;  shot world-wall 3

# The radar screen under the default rules: tabs Ores, Favourites, Recent and the gear.
"$here/scene.sh" "$module" front
sleep 2
xdo key r
xdo mousemove 1000 690
shot radar-screen 2
xdo key Escape

# allowAll: every block with an item, and the tabs All, Ores, Favourites, Recent and the gear (the 5th).
# The screen reopens on the session's last tab, Ores, so click All first.
server_set allowAll true
xdo key r
sleep 1
xdo mousemove "$(px 50)" "$(px $((45 + 10)))" click 1
sleep 0.5
xdo type --delay 60 glass
xdo mousemove "$(px $((64 + 8 + 18 + 9)))" "$(px $((41 + 34 + 9)))"
shot radar-search 2
xdo key Escape
xdo mousemove "$(px 50)" "$(px $((45 + 4 * 21 + 10)))" click 1
xdo mousemove 1000 300
shot settings 2
xdo mousemove 640 668 click 1
sleep 1
xdo key Escape
server_set allowAll false

# The station, filled through block entity data, in front of Tester; redstone in hand so the
# click opens it.
cmd 'setblock 0 120 4 oreradar:charging_station[facing=north]{Buffer:123456,Items:[{Slot:1b,id:"minecraft:redstone",count:12},{Slot:0b,id:"oreradar:ore_radar",count:1,components:{"oreradar:charge":9000}}]}'
cmd 'tp Tester 0.5 120 2.5 0 35'
sleep 1.5
xdo key 3
sleep 0.3
xdo mousemove 640 360 click 3
sleep 1
xdo mousemove 652 290
shot station 1.5
xdo key Escape
cmd 'setblock 0 120 4 minecraft:air'
xdo key 1

# The radar's tooltip when low, in the survival inventory (176 x 166 at (125, 37); hotbar slot 0 at
# (133, 179)). Charge and game mode go back to the scene's afterwards. The pause lets the server see
# the radar back in hand first.
sleep 1
cmd 'oreradar charge Tester 1800'
cmd 'gamemode survival Tester'
sleep 1
xdo key e
sleep 1
xdo mousemove "$(px $((133 + 8)))" "$(px $((179 + 8)))"
shot radar-tooltip 1.5
xdo key Escape
cmd 'gamemode creative Tester'
cmd 'oreradar charge Tester 31200'

server_set throughWalls false
"$here/scene.sh" "$module" left; shot world-depth-tested 4
server_set throughWalls true
