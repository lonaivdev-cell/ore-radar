#!/usr/bin/env bash
# dc-scene.sh <view> [OUT.png] -- the Ore Radar 2.0 test scene in the DeceasedCraft sandbox
# (~/server-setup/deceasedcraft/addons/test/sandbox.sh start --net ...), driven through its console
# with the rig client joined as Tester. With OUT.png, waits and screenshots the rig's display (:77).
#
# The scene floats at y 160 above the sandbox's spawn, clear of the Lost Cities terrain, facing
# south (+z), laid out like tools/test/scene.sh:
#   - a stone platform, 4 blocks thick, with an iron vein (3 x 2 x 2) and a deepslate iron ore buried
#     in it just ahead: under the player, seen through the floor;
#   - a stone wall across z+5;
#   - behind the wall an iron vein (3 x 2 x 2), a loose iron ore up and to the left, and a deepslate
#     iron ore to the right.
# "build" gives Tester a radar in 1.0's format: only RadarTarget "#forge:ores/iron", no RadarCharge
# (so it must read as full), plus a charging station and redstone in the hotbar.
# Views: build, front, left, high, wall.
set -euo pipefail
SANDBOX=${SANDBOX:-$HOME/server-setup/deceasedcraft/addons/test/sandbox.sh}
RIG=${RIG:-$HOME/server-setup/deceasedcraft/addons/test/client-rig}
X=${SCENE_X:--6158} Y=${SCENE_Y:-160} Z=${SCENE_Z:-614}
view=${1:?view: build, front, left, high or wall}
out=${2:-}
cmd() { "$SANDBOX" cmd "$*"; sleep 0.3; }
case $view in
build)
    for c in 'op Tester' 'gamerule sendCommandFeedback false' 'gamerule doDaylightCycle false' \
             'gamerule doWeatherCycle false' 'time set 6000' 'weather clear' 'gamemode creative Tester' \
             "forceload add $((X-16)) $((Z-16)) $((X+16)) $((Z+32))" \
             "fill $((X-7)) $((Y-4)) $((Z-4)) $((X+7)) $((Y+8)) $((Z+16)) minecraft:air" \
             "fill $((X-7)) $((Y-4)) $((Z-4)) $((X+7)) $((Y-1)) $((Z+16)) minecraft:stone" \
             "tp Tester $X.5 $Y $Z.5 0 20" \
             "fill $((X-1)) $((Y-3)) $((Z+2)) $((X+1)) $((Y-2)) $((Z+3)) minecraft:iron_ore" \
             "setblock $((X+3)) $((Y-3)) $((Z+3)) minecraft:deepslate_iron_ore" \
             "fill $((X-5)) $Y $((Z+5)) $((X+5)) $((Y+4)) $((Z+5)) minecraft:stone" \
             "fill $((X-1)) $Y $((Z+8)) $((X+1)) $((Y+1)) $((Z+9)) minecraft:iron_ore" \
             "setblock $((X+3)) $((Y+2)) $((Z+11)) minecraft:iron_ore" \
             "setblock $((X-4)) $Y $((Z+13)) minecraft:deepslate_iron_ore" \
             'item replace entity Tester weapon.mainhand with oreradar:ore_radar{RadarTarget:"#forge:ores/iron"}' \
             'item replace entity Tester hotbar.1 with oreradar:charging_station' \
             'item replace entity Tester hotbar.2 with minecraft:redstone 32'; do
        cmd "$c"
    done ;;
front) cmd "tp Tester $X.5 $Y $Z.5 0 20" ;;
left)  cmd "tp Tester $((X-6)).5 $((Y+3)) $((Z+1)).5 -40 25" ;;
high)  cmd "tp Tester $X.5 $((Y+7)) $((Z-3)).5 0 45" ;;
wall)  cmd "tp Tester $X.5 $Y $((Z+3)).5 0 0" ;;
*) echo "unknown view $view" >&2; exit 2 ;;
esac
if [[ -n $out ]]; then
    sleep "${SHOT_WAIT:-4}"
    "$RIG/shot.sh" "$out"
fi
