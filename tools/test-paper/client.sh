#!/usr/bin/env bash
# Runs a real vanilla Minecraft client against a Paper test server, for looking at what vanilla
# players get from the plugin (DESIGN.md §8) and taking screenshots.
#
#   tools/test-paper/client.sh start <version> [--name NAME] [--server HOST:PORT]   fetch if needed, launch, wait for the world
#   tools/test-paper/client.sh stop <version>
#   tools/test-paper/client.sh status <version>
#   tools/test-paper/client.sh shot OUT.png        screenshot the display
#   tools/test-paper/client.sh xdo ARGS...         xdotool on the display
#
# The client is Mojang's own (vanilla/fetch.py downloads it into $OR_SCRATCH/vanilla/mc, sha1-checked),
# offline as NAME (default Prospector), on the Paper vertical's display :83 (DESIGN.md §12) with
# Mesa's software renderer, in a ${OR_CLIENT_GEOM:-1600x900} window, -Xmx3G, under tools/limited.sh.
# Versions 1.20.1 and 1.21.1 run on GraalVM 21, 26.3 on the JDK 25 in $OR_SCRATCH/jdk25. Without
# --server the client joins that version's test server (server.sh's port). Game dir:
# $OR_SCRATCH/vanilla/runs/<version>, whose options.txt is seeded once (GUI scale 3, no pause on
# focus loss, no first-run screens). The live server's ports and this machine's LAN and Meshnet
# addresses are refused.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
scratch=${OR_SCRATCH:-$repo/runs/paper-test}
export ORERADAR_DISPLAY=${ORERADAR_DISPLAY:-:83}
geom=${OR_CLIENT_GEOM:-1600x900}
export ORERADAR_GEOM=${geom}x24
mc=$scratch/vanilla/mc
heap=3G

usage() { sed -n '2,19s/^# \{0,1\}//p' "$0" >&2; exit 2; }
[[ $# -ge 1 ]] || usage
action=$1
shift

case $action in
    shot) exec "$repo/tools/test/shot.sh" "$@" ;;
    xdo) exec "$repo/tools/test/xdo.sh" "$@" ;;
esac
[[ $# -ge 1 ]] || usage
version=$1
shift
case $version in
    1.20.1) port=25631 java=/opt/graalvm-21/bin/java data_version=3465 ;;
    1.21.1) port=25632 java=/opt/graalvm-21/bin/java data_version=3955 ;;
    26.3) port=25633 java=$scratch/jdk25/bin/java data_version=5023 ;;
    *) echo "client.sh: unknown version $version" >&2; exit 2 ;;
esac
run=$scratch/vanilla/runs/$version
pidfile=$run/client.pid
log=$run/console.log

running() { [[ -f $pidfile ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; }
# The transient scope tools/limited.sh put the client in; stopping it ends the game and anything it started.
scope_of() { sed -n 's#^0::.*/\([^/]*\.scope\)$#\1#p' "/proc/$1/cgroup" 2>/dev/null; }

start() {
    local name=Prospector server=127.0.0.1:$port
    while (($#)); do
        case $1 in
            --name) name=$2; shift 2 ;;
            --server) server=$2; shift 2 ;;
            *) usage ;;
        esac
    done
    [[ $name =~ ^[A-Za-z0-9_]{3,16}$ ]] || { echo "client.sh: bad --name $name" >&2; exit 2; }
    case ${server##*:} in 25565|25566|25575) echo "client.sh: refusing $server, a live server port" >&2; exit 2 ;; esac
    case ${server%:*} in 127.0.0.1|localhost) ;; *) echo "client.sh: refusing $server; use 127.0.0.1" >&2; exit 2 ;; esac
    if running; then echo "client.sh: $version already running" >&2; exit 1; fi
    [[ -x $java ]] || { echo "client.sh: no JDK at $java" >&2; exit 1; }
    local avail_kib
    avail_kib=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
    if (( avail_kib < (3 + 10) * 1024 * 1024 )); then
        echo "client.sh: only $((avail_kib / 1024 / 1024)) GiB available; a 3G client needs 13" >&2
        exit 1
    fi
    [[ -f $mc/versions/$version/$version.jar ]] || python3 -I "$here/vanilla/fetch.py" "$mc" "$version"
    mkdir -p "$run/tmp" "$run/home"
    if [[ ! -f $run/options.txt ]]; then
        # Without a "version" line Minecraft runs its oldest option fixers over the file and starts over with defaults.
        cat > "$run/options.txt" <<OPTIONS
version:$data_version
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
narrator:0
renderDistance:8
simulationDistance:5
maxFps:30
enableVsync:false
guiScale:3
graphicsMode:1
soundCategory_master:0.0
OPTIONS
    fi
    mapfile -t argv < <(python3 -I "$here/vanilla/launch.py" "$mc" "$version" "$run" "$name" "$heap" "$server")
    ((${#argv[@]} > 10)) || { echo "client.sh: launch.py failed" >&2; exit 1; }
    "$repo/tools/test/xvfb.sh" start
    : > "$log"
    (cd "$run" && exec setsid env -i PATH=/usr/bin:/bin HOME="$run/home" DISPLAY="$ORERADAR_DISPLAY" LANG=C.UTF-8 \
        LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=4 ALSOFT_DRIVERS=null \
        "$repo/tools/limited.sh" "$java" "${argv[@]}" --width "${geom%x*}" --height "${geom#*x}" \
        </dev/null >>"$log" 2>&1) &
    echo $! > "$pidfile"
    # In the world once the client logs the server's join message as chat.
    local t0=$SECONDS
    until grep -q "\[CHAT\] $name joined the game" "$log"; do
        running || { echo "client.sh: the client exited; see $log" >&2; tail -20 "$log" >&2; exit 1; }
        (( SECONDS - t0 < 300 )) || { echo "client.sh: not in the world after 300 s; see $log" >&2; exit 1; }
        sleep 2
    done
    echo "client.sh: vanilla $version joined $server as $name in $(( SECONDS - t0 )) s; log $log"
}

stop() {
    if running; then
        local pid scope
        pid=$(cat "$pidfile")
        scope=$(scope_of "$pid")
        if [[ -n $scope ]]; then
            systemctl --user kill --signal=SIGTERM "$scope" 2>/dev/null || true
            for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
            systemctl --user stop "$scope" 2>/dev/null || true
        else
            kill -TERM "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$pidfile"
    echo "client.sh: $version stopped"
}

case $action in
    start) start "$@" ;;
    stop) stop ;;
    status) if running; then echo running; else echo stopped; exit 1; fi ;;
    *) usage ;;
esac
