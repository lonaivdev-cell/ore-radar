#!/usr/bin/env bash
# Boots one installed production server (tools/test-compat/matrix.sh) with a release jar in mods/,
# then checks it: "Done", Ore Radar's init line, oreradar-server.toml written, `/oreradar info`
# answered, no Ore Radar error in the log, a clean stop.
#
#   tools/test-compat/boot.sh <server dir> <java> <port> <loader> [log name]
#
# The server binds 127.0.0.1:<port> in offline mode. It is started the way its installer says:
# Forge and NeoForge through libraries/**/unix_args.txt, Fabric through fabric-server-launch.jar.
# Prints one PASS/FAIL line per check; exit 1 if any failed.
set -uo pipefail

dir=$(cd "$1" && pwd) || exit 2
java=$2 port=$3 loader=$4 name=${5:-boot}
repo=$(cd "$(dirname "$0")/../.." && pwd)
log=$dir/$name.log fifo=$dir/console.fifo
cd "$dir" || exit 2

case $port in 25565 | 25566 | 25575) echo "boot.sh: refusing live port $port" >&2; exit 2 ;; esac
echo "eula=true" > eula.txt
if [[ ! -f server.properties ]]; then
    cat > server.properties <<EOF
server-ip=127.0.0.1
server-port=$port
online-mode=false
enable-rcon=false
enable-query=false
level-seed=oreradar
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=2
motd=Ore Radar compat
EOF
fi

if [[ -f fabric-server-launch.jar ]]; then
    launch=(-jar fabric-server-launch.jar nogui)
else
    args=$(find libraries -name unix_args.txt | head -n 1)
    [[ -n $args ]] || { echo "boot.sh: no unix_args.txt in $dir" >&2; exit 2; }
    launch=("@$args" nogui)
fi

rm -f "$fifo"; mkfifo "$fifo"; : > "$log"
sleep infinity > "$fifo" 2>/dev/null &
holder=$!
"$repo/tools/limited.sh" "$java" -Xms1G -Xmx2G "${launch[@]}" < "$fifo" >> "$log" 2>&1 &
server=$!
trap 'kill $holder 2>/dev/null; rm -f "$fifo"' EXIT

fails=0
check() { if [[ $2 == 1 ]]; then echo "PASS $1"; else echo "FAIL $1${3:+: $3}"; fails=$((fails + 1)); fi; }
wait_for() { # pattern timeout
    local t=0
    while (( t < $2 * 2 )); do
        grep -qE "$1" "$log" && return 0
        kill -0 $server 2>/dev/null || return 1
        sleep 0.5; t=$((t + 1))
    done
    return 1
}

if wait_for 'Done \([0-9.,]+s\)!' 600; then up=1; else up=0; fi
check "server finished booting" $up "$(grep -m1 -iE 'exception|error' "$log" | cut -c1-200)"
if (( up )); then
    check "Ore Radar initialised" "$(grep -qE "Ore Radar initialising on $loader" "$log" && echo 1)"
    check "oreradar-server.toml written" "$([[ -s config/oreradar-server.toml ]] && echo 1)"
    before=$(stat -c %s "$log")
    printf 'oreradar info\n' > "$fifo"; sleep 4
    answer=$(tail -c +"$((before + 1))" "$log")
    ok=$(grep -qiE 'unknown|incomplete|exception|error' <<< "$answer" || [[ -z $answer ]] || echo 1)
    check "/oreradar info answered" "$ok" "$(head -c 300 <<< "$answer")"
    grep -E 'Ore Radar|oreradar' <<< "$answer" | head -n 6 | sed 's/^/    /'
    printf 'stop\n' > "$fifo"
fi
t=0
while kill -0 $server 2>/dev/null && (( t < 240 )); do sleep 0.5; t=$((t + 1)); done
if kill -0 $server 2>/dev/null; then kill $server; check "clean stop" 0 "still running after 120 s"; else
    wait $server; rc=$?; check "clean stop" "$([[ $rc == 0 ]] && echo 1)" "exit $rc"; fi
errs=$(grep -E 'ERROR|Exception|Caused by' "$log" | grep -iE 'oreradar|net\.loserver' | head -n 5)
check "no Ore Radar errors in the log" "$([[ -s $log && -z $errs ]] && echo 1)" "${errs:-no log}"
exit $(( fails > 0 ))
