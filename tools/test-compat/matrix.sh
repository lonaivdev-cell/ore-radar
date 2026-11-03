#!/usr/bin/env bash
# The loader-version compatibility matrix for the release jars in dist/: for each target, install
# a real production server (official installers / Fabric's server launcher), boot it with the jar
# (tools/test-compat/boot.sh), then check the jar's loader API references against that install
# (tools/test-compat/linkcheck.py), with the version the jar was built against as the baseline.
#
#   tools/test-compat/matrix.sh [target id...]      default: every target below
#
# Installs live in runs/compat/<id>/ (git-ignored) and are reused; results in
# runs/compat/results.txt. Needs network for the first install of each target.
set -uo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
out=$repo/runs/compat
mkdir -p "$out"
j17=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin/java
j21=/opt/graalvm-21/bin/java
fabric_installer=1.1.2

# id | kind | Minecraft | loader version | Fabric API | port | jar
# Each kind's baseline (below) comes first, so the targets after it can be compared with it.
targets=(
    "forge-47.4.0|forge|1.20.1|47.4.0||25615|oreradar-2.0.0+1.20.1-forge.jar"
    "forge-47.0.0|forge|1.20.1|47.0.0||25615|oreradar-2.0.0+1.20.1-forge.jar"
    "forge-47.4.28|forge|1.20.1|47.4.28||25615|oreradar-2.0.0+1.20.1-forge.jar"
    "neoforge-47.1.106|neoforge1201|1.20.1|47.1.106||25616|oreradar-2.0.0+1.20.1-forge.jar"
    "fabric1201-0.20.1|fabric|1.20.1|0.20.1|0.92.12+1.20.1|25617|oreradar-2.0.0+1.20.1-fabric.jar"
    "fabric1201-0.15.0|fabric|1.20.1|0.15.0|0.83.0+1.20.1|25617|oreradar-2.0.0+1.20.1-fabric.jar"
    "neoforge-21.1.265|neoforge|1.21.1|21.1.265||25625|oreradar-2.0.0+1.21.1-neoforge.jar"
    "neoforge-21.1.1|neoforge|1.21.1|21.1.1||25625|oreradar-2.0.0+1.21.1-neoforge.jar"
    "fabric1211-0.20.1|fabric|1.21.1|0.20.1|0.116.17+1.21.1|25627|oreradar-2.0.0+1.21.1-fabric.jar"
    "fabric1211-oldest|fabric|1.21.1|auto|0.102.0+1.21.1|25627|oreradar-2.0.0+1.21.1-fabric.jar"
)
# The build version each kind is compared against (linkcheck --baseline).
declare -A baseline=([forge]=forge-47.4.0 [neoforge1201]=forge-47.4.0 [neoforge]=neoforge-21.1.265)
baseline[fabric1.20.1]=fabric1201-0.20.1
baseline[fabric1.21.1]=fabric1211-0.20.1
declare -A api=([forge]=net/minecraftforge/ [neoforge1201]=net/minecraftforge/ [neoforge]=net/neoforged/ [fabric]=net/fabricmc/)
declare -A label=([forge]=Forge [neoforge1201]=Forge [neoforge]=NeoForge [fabric]=Fabric)

fetch() { # url file
    [[ -s $2 ]] && return 0
    curl -fsSL --retry 3 --max-time 300 -o "$2.part" "$1" && mv "$2.part" "$2"
}

install() { # id kind mc loader fapi
    local id=$1 kind=$2 mc=$3 loader=$4 fapi=$5 d=$out/$1 java
    mkdir -p "$d/mods"
    [[ $mc == 1.20.1 ]] && java=$j17 || java=$j21
    case $kind in
        forge)
            fetch "https://maven.minecraftforge.net/net/minecraftforge/forge/$mc-$loader/forge-$mc-$loader-installer.jar" "$d/installer.jar" || return 1 ;;
        neoforge1201)
            fetch "https://maven.neoforged.net/releases/net/neoforged/forge/$mc-$loader/forge-$mc-$loader-installer.jar" "$d/installer.jar" || return 1 ;;
        neoforge)
            fetch "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loader/neoforge-$loader-installer.jar" "$d/installer.jar" || return 1 ;;
        fabric)
            local enc=${fapi//+/%2B}
            fetch "https://cdn.modrinth.com/data/P7dR8mSH/versions/$(curl -fsS "https://api.modrinth.com/v2/project/P7dR8mSH/version/$enc" | python3 -I -c 'import sys,json; print(json.load(sys.stdin)["id"])')/fabric-api-$enc.jar" "$d/mods/fabric-api-$fapi.jar" || return 1
            if [[ $loader == auto ]]; then # the oldest loader this Fabric API accepts, and at least our 0.15
                loader=$(unzip -p "$d/mods/fabric-api-$fapi.jar" fabric.mod.json | python3 -I -c '
import sys, json, re
dep = json.load(sys.stdin).get("depends", {}).get("fabricloader", ">=0.15.0")
dep = dep if isinstance(dep, str) else dep[0]
v = re.search(r"\d+\.\d+\.\d+", dep); v = v.group(0) if v else "0.15.0"
print(max(v, "0.15.0", key=lambda s: tuple(map(int, s.split(".")))))')
                echo "$loader" > "$d/loader-version"
            fi
            fetch "https://meta.fabricmc.net/v2/versions/loader/$mc/$loader/$fabric_installer/server/jar" "$d/fabric-server-launch.jar" || return 1
            return 0 ;;
    esac
    local found
    found=$(find "$d/libraries" -name unix_args.txt 2>/dev/null)
    if [[ -z $found ]]; then
        (cd "$d" && "$repo/tools/limited.sh" "$java" -jar installer.jar --installServer . > install.log 2>&1) || return 1
    fi
}

ids=("$@")
summary=$out/results.txt
: > "$summary"
for t in "${targets[@]}"; do
    IFS='|' read -r id kind mc loader fapi port jar <<< "$t"
    if (( ${#ids[@]} )) && [[ ! " ${ids[*]} " == *" $id "* ]]; then continue; fi
    d=$out/$id
    echo "== $id"
    if ! install "$id" "$kind" "$mc" "$loader" "$fapi"; then
        echo "$id: INSTALL FAILED (see $d/install.log)" | tee -a "$summary"; continue
    fi
    [[ -f $d/loader-version ]] && echo "   Fabric Loader $(cat "$d/loader-version") (the oldest Fabric API $fapi accepts, at least 0.15.0)"
    rm -f "$d"/mods/oreradar-*.jar
    cp "$repo/dist/$jar" "$d/mods/"
    [[ $mc == 1.20.1 ]] && java=$j17 || java=$j21
    "$repo/tools/test-compat/boot.sh" "$d" "$java" "$port" "${label[$kind]}" > "$d/boot-result.txt" 2>&1
    boot=$?
    cat "$d/boot-result.txt"
    key=$kind; [[ $kind == fabric ]] && key=fabric$mc
    base=${baseline[$key]}
    args=(--jar "$repo/dist/$jar" --api "${api[$kind]}" --cp "$d")
    [[ $base != "$id" && -d $out/$base ]] && args+=(--baseline "$out/$base")
    python3 -I "$repo/tools/test-compat/linkcheck.py" "${args[@]}" > "$d/linkcheck.txt" 2>&1
    link=$?
    sed 's/^/   /' "$d/linkcheck.txt" | tail -n 12
    echo "$id: boot $([[ $boot == 0 ]] && echo PASS || echo FAIL), linkage $([[ $link == 0 ]] && echo PASS || echo FAIL)$([[ $base != "$id" && -d $out/$base ]] && echo " (baseline $base)")" | tee -a "$summary"
done
echo "== summary"; cat "$summary"
