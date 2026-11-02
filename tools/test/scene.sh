#!/usr/bin/env bash
# scene.sh <forge|fabric> [view] -- builds the radar test scene around Tester through the dev
# server's console (devserver.sh must be up and Tester joined), or turns Tester to one of its views.
#
# The scene floats at y 120, clear of terrain, facing south (+z) from x 0.5, z 2.5:
#   - a stone platform, 4 blocks thick, with an iron vein (3 x 2 x 2) and a deepslate iron ore
#     buried in it just ahead: under the player, seen through the floor;
#   - a stone wall across z 7;
#   - behind the wall an iron vein (3 x 2 x 2), a loose iron ore up and to the left, and a deepslate
#     iron ore to the right.
# The radar in Tester's hand tracks @ores/iron with a partly used charge.
# Views: build (default), front, left, high, wall (looking straight at the wall from close by).
set -euo pipefail
here=$(dirname "$0")
loader=${1:?loader: forge or fabric}
view=${2:-build}
X=0 Y=120 Z=2
cmd() { "$here/devserver.sh" cmd "$loader" "$*"; sleep 0.3; }
case $view in
build)
    for c in 'gamerule sendCommandFeedback false' 'gamerule doDaylightCycle false' 'gamerule doWeatherCycle false' \
             'gamerule doMobSpawning false' 'time set 6000' 'weather clear' 'gamemode creative Tester' \
             "tp Tester $X.5 $Y $Z.5 0 20" \
             "fill $((X-7)) $((Y-4)) $((Z-4)) $((X+7)) $((Y+8)) $((Z+16)) minecraft:air" \
             "fill $((X-7)) $((Y-4)) $((Z-4)) $((X+7)) $((Y-1)) $((Z+16)) minecraft:stone" \
             "fill $((X-1)) $((Y-3)) $((Z+2)) $((X+1)) $((Y-2)) $((Z+3)) minecraft:iron_ore" \
             "setblock $((X+3)) $((Y-3)) $((Z+3)) minecraft:deepslate_iron_ore" \
             "fill $((X-5)) $Y $((Z+5)) $((X+5)) $((Y+4)) $((Z+5)) minecraft:stone" \
             "fill $((X-1)) $Y $((Z+8)) $((X+1)) $((Y+1)) $((Z+9)) minecraft:iron_ore" \
             "setblock $((X+3)) $((Y+2)) $((Z+11)) minecraft:iron_ore" \
             "setblock $((X-4)) $Y $((Z+13)) minecraft:deepslate_iron_ore" \
             "item replace entity Tester weapon.mainhand with oreradar:ore_radar" \
             "item replace entity Tester hotbar.1 with oreradar:charging_station" \
             "item replace entity Tester hotbar.2 with minecraft:redstone 32" \
             'oreradar target Tester @ores/iron' 'oreradar charge Tester 31200'; do
        cmd "$c"
    done ;;
front) cmd "tp Tester $X.5 $Y $Z.5 0 20" ;;
left)  cmd "tp Tester $((X-6)).5 $((Y+3)) $((Z+1)).5 -40 25" ;;
high)  cmd "tp Tester $X.5 $((Y+7)) $((Z-3)).5 0 45" ;;
wall)  cmd "tp Tester $X.5 $Y $((Z+3)).5 0 0" ;;
*) echo "unknown view $view" >&2; exit 2 ;;
esac
