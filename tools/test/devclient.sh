#!/usr/bin/env bash
# devclient.sh start|stop|status <forge|fabric> [HOST:PORT]
#
# Runs the 1.20.1 dev client (Gradle :<loader>:runClient, -Xmx3G, under tools/limited.sh) on the
# vertical's Xvfb display (default :81) with Mesa's software renderer (llvmpipe), offline as
# "Tester", in a 1280x720 window. With HOST:PORT it joins that server at once (quick play); the
# live server's ports and this machine's LAN and Meshnet addresses are refused.
# Seeds runs/mc1201-<loader>/client/options.txt once: no pause on focus loss, no first-run screens,
# GUI scale 3, a low frame cap, and "Open Ore Radar" on R (unbound by default) so tests can press
# it. Its "version" line matters: without one, Minecraft runs 1.12's key fixer over the key lines,
# fails, and starts over with defaults. Log: runs/mc1201-<loader>/devclient.log.
set -euo pipefail
source "$(dirname "$0")/env.sh"
action=${1:?usage: devclient.sh start|stop|status <forge|fabric> [HOST:PORT]}
loader=${2:?loader: forge or fabric}
case $loader in forge|fabric) ;; *) echo "loader must be forge or fabric" >&2; exit 2 ;; esac
RUN=$REPO/runs/mc1201-$loader
LOG=$RUN/devclient.log
PIDS=$RUN/devclient.pids

running() { [[ -f $PIDS ]] && kill -0 "$(cat "$PIDS")" 2>/dev/null; }

# The transient scope tools/limited.sh put the client in; stopping it ends Gradle and the game alike.
scope_of() { sed -n 's#^0::.*/\([^/]*\.scope\)$#\1#p' "/proc/$1/cgroup" 2>/dev/null; }

case $action in
start)
    if running; then echo "already running (log $LOG)"; exit 0; fi
    server=${3:-}
    args="--username Tester --width 1280 --height 720"
    if [[ -n $server ]]; then
        case ${server##*:} in 25565|25566|25575) echo "refusing $server: a live server port" >&2; exit 2 ;; esac
        case ${server%:*} in 127.0.0.1|localhost) ;; *) echo "refusing $server: use 127.0.0.1" >&2; exit 2 ;; esac
        args="$args --quickPlayMultiplayer $server"
    fi
    "$TEST_DIR/xvfb.sh" start
    mkdir -p "$RUN/client"
    if [[ ! -f $RUN/client/options.txt ]]; then
        cat > "$RUN/client/options.txt" <<'OPTIONS'
version:3465
pauseOnLostFocus:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
onboardAccessibility:false
narrator:0
renderDistance:6
simulationDistance:5
maxFps:30
enableVsync:false
guiScale:3
graphicsMode:1
key_key.oreradar.open:key.keyboard.r
OPTIONS
    fi
    setsid nohup env DISPLAY="$DISPLAY_NAME" LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=4 \
        ALSOFT_DRIVERS=null "$REPO/tools/limited.sh" "$REPO/gradlew" -p "$REPO/mc1201" --console=plain \
        ":$loader:runClient" "-Poreradar_client_args=$args" </dev/null >"$LOG" 2>&1 &
    echo $! > "$PIDS"
    echo "started (log $LOG)" ;;
stop)
    if running; then
        pid=$(cat "$PIDS")
        scope=$(scope_of "$pid")
        if [[ -n $scope ]]; then
            systemctl --user kill --signal=SIGTERM "$scope" 2>/dev/null || true
            for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
            systemctl --user stop "$scope" 2>/dev/null || true
        else
            kill -TERM "$pid" 2>/dev/null || true
        fi
    fi
    rm -f "$PIDS"
    echo stopped ;;
status)
    if running; then echo running; else echo stopped; exit 1; fi ;;
*)
    echo "usage: devclient.sh start|stop|status <forge|fabric> [HOST:PORT]" >&2
    exit 2 ;;
esac
